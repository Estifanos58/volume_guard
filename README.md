# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to prevent and mitigate unexpected media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Fastest Practical Reactive Correction:**
  - When an external application attempts to increase media volume, Volume Guard reactively catches the system `VOLUME_CHANGED_ACTION` broadcast and issues exactly **one** `setStreamVolume(STREAM_MUSIC, 0, 0)`.
  - Disabling the Guard never restores any previous volume.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
  - Performs **zero audio Binder calls** on the input path.
  - Schedules background monitor cleanup asynchronously off the input thread.
  - Returns `false` from `onKeyEvent()` ASAP so Android receives the key event and raises volume normally.
* **Guard Active + Physical Volume Down:**
  - Keeps the guard active.
  - Checks the thread-safe volatile cached volume; if already `0`, performs **zero audio Binder IPCs**.
  - Returns `true` from `onKeyEvent()` to consume the event and suppress redundant system volume UI.
* **Guard Inactive:**
  - Passes all hardware volume keys through untouched (`return false`).
  - Normal Android volume behavior.

---

## 2. Ultra-Lean Monitoring Architecture

```text
                  STREAM_MUSIC change
                         ↓
              VOLUME_CHANGED_ACTION
     (dispatched on background HandlerThread)
                         ↓
              verify MUSIC + newValue
                         ↓
                   newValue > 0
                         ↓
                setStreamVolume(0)
             [single Binder IPC call]
```

### A. Primary Reactive Path (`VOLUME_CHANGED_ACTION`)
1. Validates action is `android.media.VOLUME_CHANGED_ACTION`.
2. Validates stream is `AudioManager.STREAM_MUSIC` via `EXTRA_VOLUME_STREAM_TYPE`. Non-music streams (`STREAM_RING`, `STREAM_ALARM`) are ignored.
3. Reads the reported volume directly from `EXTRA_VOLUME_STREAM_VALUE`.
4. **No Pre-IPC Query:** If `newValue > 0`, immediately issues `setStreamVolume(STREAM_MUSIC, 0, 0)` using a cached `AudioManager` instance without a redundant `getStreamVolume()` query.
5. If `newValue == 0`, updates cached volume without issuing any audio IPC.
6. Safe fallback: If extras are missing/invalid, safely falls back to querying `AudioManager`.

### B. Dedicated Normal-Priority `HandlerThread`
* Reactive monitoring runs on a single lightweight `HandlerThread("VolumeGuardMonitor", Process.THREAD_PRIORITY_DEFAULT)` active **strictly while Guard is operational**.
* Eliminates main-thread queue delay while avoiding unnecessary real-time audio thread priority.
* Quits immediately when Guard is turned OFF or service disconnects.

### C. Removed Redundant Fallback Monitors
* **`AudioPlaybackCallback`**: Removed. It triggers on every media playback track state change rather than volume changes, adding unnecessary wakeups.
* **`VolumeContentObserver`**: Removed. Observing `Settings.System` is redundant because `VOLUME_CHANGED_ACTION` is fired directly by the Android framework for all media volume adjustments.
* **`AudioDeviceCallback`**: Removed. Device routing changes trigger system volume broadcasts on modern Android and Tecno HiOS, rendering a separate device listener unnecessary.
* **Proactive Mute (`ADJUST_MUTE`)**: Removed. Relies strictly on the clean, deterministic reactive volume index correction (`setStreamVolume(0)`).

---

## 3. Physical Hardware Key Hot Path

In `VolumeGuardAccessibilityService.onKeyEvent()`:
* **`@Volatile isOperationalFast`**: Primitive volatile boolean allows O(1) decision making.
* **Physical Volume Up (Override):**
  1. Flips `isOperationalFast = false` immediately.
  2. Transitions desired state to `OFF` synchronously.
  3. Dispatches SharedPreferences write asynchronously (`apply()`).
  4. Posts monitor cleanup to background loop.
  5. Returns `false` immediately with **zero audio Binder calls** so Android handles volume up without delay.
* **Physical Volume Down:**
  1. Checks `lastKnownMediaVolume`.
  2. If already `0`, issues **0 Binder calls**.
  3. Returns `true` immediately to consume the event.
* **Zero Allocations & Zero Hot-Path Logging:** Hot path creates no objects and performs no string formatting.

---

## 4. Platform Limitations on Unrooted Android

* **Pre-Dispatch Hardware Key Interception:** Fully supported. Accessibility services with `flagRequestFilterKeyEvents` receive physical key events before the window manager or audio service.
* **Programmatic Volume Interception:** On unrooted Android, third-party apps cannot pre-veto another application's IPC call to `AudioService`. Volume Guard provides the fastest practical reactive correction available using public platform APIs without claiming impossible kernel-level vetoes.

---

## 5. Tecno / OEM Considerations (e.g. Tecno POP 7)

1. **Battery Management:** Configure **Settings → Battery Lab / Power Management → Volume Guard** to allow unrestricted background activity so HiOS does not stop the accessibility service.
2. **Volume Sliders:** Consuming `KEYCODE_VOLUME_DOWN` (`return true`) prevents HiOS's on-screen volume panel from popping up.
3. **Reactive Broadcast Delivery:** On Tecno POP 7, `VOLUME_CHANGED_ACTION` broadcasts are delivered promptly to the background `HandlerThread`, providing sub-millisecond reactive correction.

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

---

## 7. Automated Test Suite

The project includes 13 Robolectric JVM unit tests in `VolumeGuardTest.kt` verifying:
1. Operational activation clamps volume to 0.
2. `onKeyEvent(VOLUME_UP)` returns `false`, disengages guard, persists `OFF`, and makes zero audio IPC calls.
3. `onKeyEvent(VOLUME_DOWN)` returns `true` and avoids IPC when volume is already 0.
4. Service disconnect immediately deactivates operational protection and preserves user preference.
5. Service reconnect automatically restores persisted `ON` preference and forces volume 0.
6. Guard `OFF` allows normal volume and passes through keys.
7. Reactive receiver path uses broadcast extras directly to clamp volume without `getStreamVolume()`.
8. Zero-volume broadcast causes no audio correction or IPC.
9. Unrelated stream broadcasts (`STREAM_RING`, `STREAM_ALARM`) are ignored.
10. Missing extras safely fall back to `AudioManager` query.
11. Rapid Volume Up events are safe and idempotent.
12. Confirmed zero initial or previous volume storage or restoration.
13. Reactive monitor and fast flag are active strictly while operational.
