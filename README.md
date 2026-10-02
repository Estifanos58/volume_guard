# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to eliminate and mitigate unexpected media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Invariant: Guard ON = Privacy Mask Continuously Active**
  - **Volume Enforcement:** Primary protection mechanism. Combines `VOLUME_CHANGED_ACTION` broadcast, targeted `ContentObserver`, and a continuous 10 ms safety sampler to detect and force `STREAM_MUSIC` back to `0` within tens of milliseconds.
  - **Privacy Masking:** Dedicated foreground service (`PrivacyMaskService`) continuously looping pre-generated speech-shaped noise via static `AudioTrack`. Inaudible at volume 0; if an app raises volume, masking noise bursts through simultaneously to obscure speech comprehension until volume enforcement clamps it back to 0.
  - **Independence:** If volume detection is delayed or misses an event, the user hears masking audio rather than clear content for as long as Guard remains ON.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
  - Performs **zero audio Binder calls** on the input path.
  - Schedules background monitor and privacy mask cleanup asynchronously off the input thread.
  - Returns `false` from `onKeyEvent()` ASAP so Android receives the key event and raises volume normally.
* **Guard Active + Physical Volume Down:**
  - Keeps the guard active.
  - Checks thread-safe volatile cached volume; if already `0`, performs **zero audio Binder IPCs**.
  - Returns `true` from `onKeyEvent()` to consume the event and suppress redundant system volume UI.
* **Guard Inactive:**
  - Passes all hardware volume keys through untouched (`return false`).
  - Normal Android volume behavior.

---

## 2. Decoupled Multi-Service Architecture

```text
                                Guard ON
                                   │
                      ┌────────────┴────────────┐
                      │                         │
                      ▼                         ▼
             PrivacyMaskService           AccessibilityService
             (:mask process)             (main process)
                      │                         │
                 foreground                volume detector
                 (mediaPlayback)        ┌───────┼────────┐
                      │                 │       │        │
                  AudioTrack        broadcast observer sampler
                  continuous            │       │        │
                  masking               └───────┼────────┘
                      │                         ▼
                      │                    volume > 0
                      │                         │
                      │                         ▼
                      │                 setStreamVolume(0)
                      │
                      ▼
               masking continues even
               when detector is delayed
               or misses the event
```

### A. Dedicated Privacy Mask Service (`PrivacyMaskService`)
* **Process Isolation (`android:process=":mask"`):** Runs in an isolated app-local process to decouple audio playback from the main Activity and AccessibilityService lifecycles.
* **Foreground Service (`mediaPlayback`):** Declared with `foregroundServiceType="mediaPlayback"` and low-importance ongoing notification ("Volume Guard Privacy Mask").
* **Bundled PCM Asset:** Pre-loads a 1-second 16 kHz 16-bit mono speech-shaped noise asset (`privacy_mask_16k.pcm`, 32 KB footprint) using `AudioTrack.MODE_STATIC` looped infinitely.
* **Formal State Machine:** Explicit transitions: `STOPPED` → `STARTING` → `PLAYING` (verified) or `FAILED`.
* **AudioTrack Health Watchdog (200 ms):** Low-frequency watchdog verifies track health and recovers if playback stalls due to audio routing or audio server resets.
* **No Audio Focus Required:** Relies on Android's native stream mixing rather than requesting audio focus, avoiding background focus conflicts.

### B. Volume Enforcement Engine (`VolumeGuardAccessibilityService`)
* **Channel 1 (Primary Broadcast):** Reads `EXTRA_VOLUME_STREAM_VALUE` directly from `VOLUME_CHANGED_ACTION` extras with zero pre-check query.
* **Channel 2 (Targeted ContentObserver):** Observes `volume_music_speaker` and `volume_music`.
* **Channel 3 (Continuous 10 ms Sampler):** Runs continuously on the monitor thread while Guard is active, guaranteeing deterministic tens-of-milliseconds recovery.
* **Thread Priority:** Dedicated `HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_AUDIO)`.

---

## 3. Physical Hardware Key Hot Path

In `VolumeGuardAccessibilityService.onKeyEvent()`:
* **`@Volatile isOperationalFast`**: Primitive volatile boolean allows O(1) decision making.
* **Physical Volume Up (Override):**
  1. Flips `isOperationalFast = false` immediately.
  2. Transitions desired state to `OFF` synchronously.
  3. Dispatches SharedPreferences write asynchronously (`apply()`).
  4. Posts monitor and `PrivacyMaskService` shutdown asynchronously to background loop.
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
  - Privacy masking status indicator (`Privacy masking: ON while Guard is active`).
  - Direct button to open Android Accessibility Settings when service is not connected.

---

## 5. Platform Realities & Limitations on Unrooted Android

* **Pre-Dispatch Hardware Key Interception:** Fully supported. Accessibility services with `flagRequestFilterKeyEvents` receive physical key events before the window manager or audio service.
* **Programmatic Volume Interception:** On unrooted Android, third-party apps cannot pre-veto another application's IPC call to `AudioService`. The combination of `VOLUME_CHANGED_ACTION`, targeted `ContentObserver`, continuous 10 ms safety sampler, and the continuous privacy masking layer provides maximum defense-in-depth:
  - Volume enforcement force-clamps the volume back to 0.
  - The privacy mask minimizes speech intelligibility during any brief gap.
* **Device / OEM Considerations:** Masking effectiveness depends on the hardware mixer, speaker/headphones frequency response, and relative audio levels. Android may terminate background processes under extreme memory pressure or aggressive OEM battery savers (e.g. HiOS Battery Lab).

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
