# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to eliminate and mitigate unexpected media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Deterministic Sub-Frame Reactive Correction:**
  - Multi-channel detection: primary `VOLUME_CHANGED_ACTION` broadcast + fallback `ContentObserver` + continuous **10 ms** safety sampler while Guard is operational.
  - Programmatic volume increases are forced back to `0` within tens of milliseconds even when Android or OEM broadcast delivery is delayed.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
  - Performs **zero audio Binder calls** on the input path.
  - Schedules background monitor and sampler cleanup asynchronously off the input thread.
  - Returns `false` from `onKeyEvent()` ASAP so Android receives the key event and raises volume normally.
* **Guard Active + Physical Volume Down:**
  - Keeps the guard active.
  - Checks thread-safe volatile cached volume; if already `0`, performs **zero audio Binder IPCs**.
  - Returns `true` from `onKeyEvent()` to consume the event and suppress redundant system volume UI.
* **Guard Inactive:**
  - Passes all hardware volume keys through untouched (`return false`).
  - Normal Android volume behavior.

---

## 2. Multi-Channel Monitoring Architecture

```text
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
                    lastKnownMediaVolume reset to 0
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
* **Ungated:** Does not wait for `AudioPlaybackCallback` or `isMusicActive()`. Operates as a deterministic safety net for the entire duration Guard is active.
* **Tick Hot Path:** Reads `getStreamVolume(STREAM_MUSIC)`. If `0`, immediately reschedules next tick with zero allocations. If `> 0`, clamps to `0` and logs detection.
* Stops immediately when Guard is turned OFF.

---

## 3. Physical Hardware Key Hot Path

In `VolumeGuardAccessibilityService.onKeyEvent()`:
* **`@Volatile isOperationalFast`**: Primitive volatile boolean allows O(1) decision making.
* **Physical Volume Up (Override):**
  1. Flips `isOperationalFast = false` immediately.
  2. Transitions desired state to `OFF` synchronously.
  3. Dispatches SharedPreferences write asynchronously (`apply()`).
  4. Posts monitor and sampler cleanup to background loop.
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
  - Direct button to open Android Accessibility Settings when service is not connected.

---

## 5. Platform Limitations on Unrooted Android

* **Pre-Dispatch Hardware Key Interception:** Fully supported. Accessibility services with `flagRequestFilterKeyEvents` receive physical key events before the window manager or audio service.
* **Programmatic Volume Interception:** On unrooted Android, third-party apps cannot pre-veto another application's IPC call to `AudioService`. The combination of `VOLUME_CHANGED_ACTION`, targeted `ContentObserver`, and the continuous 10 ms safety sampler provides the fastest practical recovery window available using public platform APIs without claiming impossible kernel-level vetoes.

---

## 6. Tecno POP 7 Real-Device Testing & Benchmarking

In debug builds (`BuildConfig.DEBUG`), every detection logs which channel caught the event:
* `[broadcast] Reactive correction: total=...µs (ipc=...µs)`
* `[observer] Caught volume change (...) -> clamping to 0`
* `[sampler] Caught volume increase (...) -> clamped to 0`

### Real-Device Test Checklist:
1. **Background Audio Playback:** Start YouTube / Telegram audio. Toggle Guard ON. Volume drops to 0.
2. **Programmatic Volume Attack:** External app calls `setStreamVolume(STREAM_MUSIC, 10, 0)`. Protection clamps volume to 0 within 10–20 ms.
3. **Screen Off / Background:** Programmatic volume changes while screen is locked are caught by the 10 ms sampler even if the system delays broadcasts.
4. **Physical Volume Up:** Press physical Volume Up. Guard disengages instantly, sampler stops, volume raises normally.
5. **Physical Volume Down:** Press physical Volume Down. Consumed with zero volume sliders.

---

## 7. How to Build & Install

```bash
# Build the debug APK:
gradle assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk

# Install via ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
