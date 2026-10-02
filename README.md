# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to eliminate and mitigate unexpected media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Volume Enforcement vs. Privacy Masking:**
  - **Volume Enforcement:** The core protection mechanism. Combines `VOLUME_CHANGED_ACTION`, targeted `ContentObserver`, and a continuous 10 ms safety sampler to detect and force `STREAM_MUSIC` back to `0` within tens of milliseconds.
  - **Privacy Masking:** Supplemental, low-overhead audio layer (`AudioTrack.MODE_STATIC` speech-shaped noise) pre-playing while Guard is active. Inaudible at volume 0; if an app raises volume, the masking noise bursts through simultaneously with the rogue audio to obscure speech comprehension until volume enforcement clamps it back to 0.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
  - Performs **zero audio Binder calls** on the input path.
  - Schedules background monitor, sampler, and masker cleanup asynchronously off the input thread.
  - Returns `false` from `onKeyEvent()` ASAP so Android receives the key event and raises volume normally.
* **Guard Active + Physical Volume Down:**
  - Keeps the guard active.
  - Checks thread-safe volatile cached volume; if already `0`, performs **zero audio Binder IPCs**.
  - Returns `true` from `onKeyEvent()` to consume the event and suppress redundant system volume UI.
* **Guard Inactive:**
  - Passes all hardware volume keys through untouched (`return false`).
  - Normal Android volume behavior.

---

## 2. Multi-Channel Monitoring & Privacy Masking Architecture

```text
                                  Guard ON
                                     ↓
              ┌──────────────────────────────────────────────┐
              │  PrivacyMaskPlayer (AudioTrack.MODE_STATIC)  │
              │  Pre-plays speech-shaped noise (inaudible 0) │
              └──────────────────────────────────────────────┘
                                     ↓
                     External App raises STREAM_MUSIC
                                     ↓
  ┌──────────────────────────────────┼──────────────────────────────────┐
  │                                  │                                  │
  ▼                                  ▼                                  ▼
Channel 1: Primary Broadcast    Channel 2: Fallback Observer     Channel 3: Continuous Sampler
VOLUME_CHANGED_ACTION           Settings.System                  10 ms interval
(instant extras read)           (volume_music_speaker/music)     (active while Guard is ON)
  │                                  │                                  │
  └──────────────────────────────────┼──────────────────────────────────┘
                                     ↓
                   Exactly ONE setStreamVolume(STREAM_MUSIC, 0, 0)
                                     ↓
           Both rogue audio and privacy masking immediately silenced
```

### A. Dedicated Single Monitor HandlerThread
* All reactive monitoring and sampling runs on a single background `HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_AUDIO)`.
* Zero UI thread contention or main looper delay.
* Starts immediately when Guard becomes operational; stops immediately when Guard turns OFF or service disconnects.

### B. Channel 1: Primary `VOLUME_CHANGED_ACTION` Broadcast
* Reads `EXTRA_VOLUME_STREAM_TYPE` and `EXTRA_VOLUME_STREAM_VALUE` directly from broadcast extras.
* When `newVolume > 0`: immediately issues `setStreamVolume(STREAM_MUSIC, 0, 0)` using cached `AudioManager`. **Zero** pre-check `getStreamVolume()` IPC calls.
* Non-music streams (`STREAM_RING`, `STREAM_ALARM`) and zero-volume events are ignored.

### C. Channel 2: Targeted Fallback `ContentObserver`
* Observes specific media volume setting URIs (`volume_music_speaker`, `volume_music`) on the monitor thread.
* Acts as an interrupt-driven fallback if broadcast delivery is delayed by OEM throttling.

### D. Channel 3: Continuous 10 ms Safety Sampler
* Runs continuously on the monitor `HandlerThread` while Guard is operational, using a single reusable `Runnable` (zero per-tick allocations).
* Guarantees that any volume spike is caught and clamped within tens of milliseconds regardless of system or OEM broadcast delays.

### E. Supplemental Privacy Masking (`PrivacyMaskPlayer`)
* **Pre-Generated Noise Buffer:** 1 second of speech-shaped broadband noise (16 kHz mono 16-bit PCM, 32 KB RAM footprint).
* **Static Looping AudioTrack:** Pre-loaded via `AudioTrack.MODE_STATIC` and looped infinitely.
* **Simultaneous Audio Mixing:** Relies on Android's native multi-app stream mixing without requesting audio focus (`AUDIOFOCUS_GAIN`), avoiding background focus restrictions.
* **Independent Enforcement:** The masker is purely supplemental; volume enforcement functions normally even if `AudioTrack` initialization fails.

---

## 3. Physical Hardware Key Hot Path

In `VolumeGuardAccessibilityService.onKeyEvent()`:
* **`@Volatile isOperationalFast`**: Primitive volatile boolean allows O(1) decision making.
* **Physical Volume Up (Override):**
  1. Flips `isOperationalFast = false` immediately.
  2. Transitions desired state to `OFF` synchronously.
  3. Dispatches SharedPreferences write asynchronously (`apply()`).
  4. Posts monitor, sampler, and masker cleanup to background loop.
  5. Returns `false` immediately with **zero audio Binder calls** so Android handles volume up without delay.
* **Physical Volume Down:**
  1. Checks `lastKnownMediaVolume`.
  2. If already `0`, issues **0 Binder calls**.
  3. Returns `true` immediately to consume the event.
* **Zero Allocations & Zero Hot-Path Logging:** Hot path creates no objects and performs no string formatting.

---

## 4. Ultra-Lean Native View UI

* Minimal native Android View layout (`activity_main.xml`).
* **APK Size:** ~1.2 MB.
* **Cold Start & Memory:** <15 MB RAM footprint.
* Displays:
  - Guard ON/OFF toggle switch.
  - Concise operational status (`PROTECTED (Active)`, `GUARD OFF`, or `SERVICE DISCONNECTED`).
  - Current media volume readout (`Media Volume: 0`).
  - Privacy masking status (`Privacy masking: ON while Guard is active`).
  - Direct button to open Android Accessibility Settings when service is not connected.

---

## 5. Platform Limitations on Unrooted Android

* **Pre-Dispatch Hardware Key Interception:** Fully supported. Accessibility services with `flagRequestFilterKeyEvents` receive physical key events before the window manager or audio service.
* **Programmatic Volume Interception:** On unrooted Android, third-party apps cannot pre-veto another application's IPC call to `AudioService`. The combination of `VOLUME_CHANGED_ACTION`, targeted `ContentObserver`, continuous 10 ms safety sampler, and the privacy masking layer provides defense-in-depth to minimize and obscure any momentary audio exposure.

---

## 6. How to Build & Install

```bash
# Build the debug APK:
gradle assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk

# Install via ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
