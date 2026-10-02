# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to prevent and mitigate unexpected media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Proactive Mute Strategy (Auditory Leakage Mitigation):**
  - When Guard becomes operational, it sets `STREAM_MUSIC = 0` **and** applies `AudioManager.ADJUST_MUTE`. This pre-mutes the hardware audio output stream so that if another app programmatically increases the volume index, audio leakage is minimized/prevented before reactive correction takes place.
  - Reactive corrections use exactly **one** `setStreamVolume(STREAM_MUSIC, 0, 0)` without reissuing `ADJUST_MUTE`.
  - When Guard is disabled (via UI toggle, physical Volume Up, or service disconnect), `AudioManager.ADJUST_UNMUTE` is immediately called so normal volume adjustment functions smoothly.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
  - Lifts proactive mute (`ADJUST_UNMUTE`).
  - Schedules background monitor cleanup asynchronously off the input thread.
  - Does **not** programmatically modify volume.
  - Returns `false` from `onKeyEvent()` ASAP so Android receives the key event and raises volume normally.
* **Guard Active + Physical Volume Down:**
  - Keeps the guard active.
  - Checks thread-safe volatile cached volume; if already `0`, avoids redundant audio Binder IPC.
  - Returns `true` from `onKeyEvent()` to consume the event and suppress redundant system volume UI.
* **Guard Inactive:**
  - Passes all hardware volume keys through untouched (`return false`).
  - Normal Android volume behavior.

---

## 2. Ultra-Low Latency Reactive Architecture

```text
                  STREAM_MUSIC change
                         ↓
              VOLUME_CHANGED_ACTION
      (dispatched on dedicated HandlerThread)
                         ↓
              verify MUSIC + newValue
                         ↓
                   newValue > 0
                         ↓
                setStreamVolume(0)
             [single Binder IPC call]
```

### A. Dedicated High-Priority HandlerThread
* To eliminate main looper queue latency and avoid UI contention, reactive monitors are registered on a dedicated `HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_URGENT_AUDIO)`.
* Both `VolumeChangeReceiver` and `VolumeContentObserver` execute on this background handler.

### B. Primary Reactive Path (`VOLUME_CHANGED_ACTION`)
1. Validates action is `android.media.VOLUME_CHANGED_ACTION`.
2. Validates stream is `AudioManager.STREAM_MUSIC` via `EXTRA_VOLUME_STREAM_TYPE`.
3. Reads the reported volume directly from `EXTRA_VOLUME_STREAM_VALUE`.
4. **No Pre-IPC Query:** If `newValue > 0`, immediately issues `setStreamVolume(STREAM_MUSIC, 0, 0)` directly from broadcast extras without a redundant `getStreamVolume()` query.
5. If `newValue == 0`, updates cached volume without issuing any audio IPC.
6. Safe fallback: If extras are missing/invalid, safely falls back to querying `AudioManager`.

### C. Fallback Monitors
1. **Narrowly Scoped `VolumeContentObserver`:** Observes specific media volume setting URIs (`volume_music_speaker`, `volume_music`) on the dedicated background thread, acting as an interrupt-driven safety net on OEM devices without spurious wakeups from brightness or screen timeout changes.
2. **`AudioDeviceCallback` (API 23+):** Low-frequency safety listener that triggers only when an audio route changes (e.g., Bluetooth headphones connected or 3.5mm jack plugged in). Reasserts volume `0` to protect against OEM device-specific volume profile switches.

---

## 3. Physical Hardware Key Hot Path

In `VolumeGuardAccessibilityService.onKeyEvent()`:
* **`@Volatile isOperationalFast`**: Primitive volatile boolean allows O(1) decision making.
* **Physical Volume Up (Override):**
  1. Flips `isOperationalFast = false` immediately.
  2. Updates manager state synchronously and lifts proactive mute.
  3. Dispatches SharedPreferences write asynchronously (`apply()`).
  4. Posts monitor cleanup to background loop.
  5. Returns `false` immediately so Android handles volume up without delay.
* **Physical Volume Down:**
  1. Checks `lastKnownMediaVolume`.
  2. If already `0`, issues **0 Binder calls**.
  3. Returns `true` immediately to consume the event.
* **Zero Allocations & Zero Logging:** Hot path creates no objects, performs no string formatting, and emits no logs.

---

## 4. Latency Benchmarking (Debug Builds)

In debug builds (`BuildConfig.DEBUG`), `VolumeChangeReceiver` measures exact execution timing in microseconds ($\mu\text{s}$):
1. **`tReceived`**: Timestamp when the broadcast reaches `onReceive()`.
2. **`tCorrectionStart`**: Timestamp right before `setStreamVolume(STREAM_MUSIC, 0, 0)`.
3. **`tCorrectionEnd`**: Timestamp when `setStreamVolume()` Binder call completes.

Sample benchmark output on device:
```text
D/VolumeChangeReceiver: Reactive volume correction: total=382µs (audio_ipc=215µs)
```
In release builds, all benchmarking code and logs are compiled out.

---

## 5. Platform Limitations & Realities of Unrooted Android

* **Pre-Dispatch Hardware Key Interception:** Fully supported. Accessibility services with `flagRequestFilterKeyEvents` receive physical key events before the window manager or audio service.
* **Programmatic Volume Interception:** On unrooted Android, third-party apps cannot pre-veto another application's IPC call to `AudioService`. Volume Guard provides the fastest possible reactive correction combined with proactive stream muting to prevent/minimize audible leaks.

---

## 6. Tecno / OEM Considerations (e.g. Tecno POP 7)

1. **Battery Management:** Configure **Settings → Battery Lab / Power Management → Volume Guard** to allow unrestricted background activity so HiOS does not kill the accessibility service.
2. **Audio Routing:** The included `AudioDeviceCallback` handles Tecno POP 7 volume profile switching when connecting/disconnecting Bluetooth headsets.
3. **Volume Sliders:** Consuming `KEYCODE_VOLUME_DOWN` (`return true`) prevents HiOS's on-screen volume panel from popping up.
4. **Proactive Mute Support:** On Tecno HiOS, `ADJUST_MUTE` is honored by the audio HAL for `STREAM_MUSIC`, ensuring sound output is suppressed even if an app raises the software slider index before correction.

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

---

## 8. Automated Test Suite

The project includes 14 Robolectric JVM unit tests in `VolumeGuardTest.kt` verifying:
1. Operational activation clamps volume to 0 and applies proactive mute.
2. `onKeyEvent(VOLUME_UP)` returns `false`, lifts mute, disengages guard, and persists `OFF`.
3. `onKeyEvent(VOLUME_DOWN)` returns `true` and avoids IPC when volume is already 0.
4. Service disconnect immediately deactivates operational protection, lifts mute, and preserves user preference.
5. Service reconnect automatically restores persisted `ON` preference, forces volume 0, and re-applies proactive mute.
6. Guard `OFF` allows normal volume and passes through keys.
7. Reactive receiver path uses broadcast extras directly to clamp volume without `getStreamVolume()`.
8. Zero-volume broadcast causes no audio correction or IPC.
9. Unrelated stream broadcasts (`STREAM_RING`, `STREAM_ALARM`) are ignored.
10. Missing extras safely fall back to AudioManager query.
11. Rapid Volume Up events are safe and idempotent.
12. Confirmed zero initial or previous volume storage or restoration.
13. Narrowly scoped ContentObserver triggers fallback volume correction.
14. Reactive monitors and fast flags are active strictly while operational.
