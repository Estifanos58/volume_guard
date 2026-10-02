# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to prevent unwanted media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
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
                         ↓
              verify MUSIC + newValue
                         ↓
                   newValue > 0
                         ↓
                setStreamVolume(0)
```

### A. Primary Reactive Path (`VOLUME_CHANGED_ACTION`)
1. Validates action is `android.media.VOLUME_CHANGED_ACTION`.
2. Validates stream is `AudioManager.STREAM_MUSIC` via `EXTRA_VOLUME_STREAM_TYPE`.
3. Reads the reported volume directly from `EXTRA_VOLUME_STREAM_VALUE`.
4. **No Pre-IPC Query:** If `newValue > 0`, immediately issues `setStreamVolume(STREAM_MUSIC, 0, 0)`. It does **not** call `getStreamVolume()` first, cutting an entire Binder round-trip off the critical correction path.
5. If `newValue == 0`, updates the internal cached volume without issuing any audio IPC.

### B. Fallback Monitors
1. **Narrowly Scoped `VolumeContentObserver`:** Observes specific media volume setting URIs (`volume_music_speaker`, `volume_music`) rather than the broad `Settings.System.CONTENT_URI`, acting as a safety net on OEM devices (e.g. Tecno/HiOS) without wakeups from brightness or screen timeout changes.
2. **`AudioDeviceCallback` (API 23+):** Low-frequency safety listener that triggers only when an output route changes (e.g., Bluetooth headphones connected or 3.5mm jack plugged in). Reasserts volume `0` to protect against OEM device-specific volume profile switches.

### C. Removed Redundant Mechanisms
* **`AudioPlaybackCallback`:** Removed because it triggered on every playback state transition rather than actual volume modifications.
* **`adjustStreamVolume(ADJUST_MUTE)`:** Removed in favor of a single, clean `setStreamVolume(STREAM_MUSIC, 0, 0)` call.
* **`isSelfAdjustingVolume` Lock:** Removed because broadcasts reporting volume `0` are naturally no-ops, eliminating lock overhead and ensuring legitimate external volume changes are never masked.

---

## 3. Physical Hardware Key Hot Path

In `VolumeGuardAccessibilityService.onKeyEvent()`:
* **`@Volatile isOperationalFast`**: Primitive volatile boolean allows O(1) decision making.
* **Physical Volume Up (Override):**
  1. Flips `isOperationalFast = false` immediately.
  2. Updates manager state synchronously.
  3. Dispatches SharedPreferences write asynchronously (`apply()`).
  4. Posts monitor cleanup to background Handler loop.
  5. Returns `false` immediately so Android handles volume up without delay.
* **Physical Volume Down:**
  1. Checks `lastKnownMediaVolume`.
  2. If already `0`, issues **0 Binder calls**.
  3. Returns `true` immediately to consume the event.
* **Zero Allocations & Zero Logging:** Hot path creates no objects, performs no string formatting, and emits no logs.

---

## 4. Platform Limitations & Realities of Unrooted Android

* **Pre-Dispatch Hardware Key Interception:** Fully supported. Accessibility services with `flagRequestFilterKeyEvents` receive physical key events before the window manager or audio service.
* **Programmatic Volume Interception:** On unrooted Android, third-party apps cannot pre-veto another application's IPC call to `AudioService`. Volume Guard provides the fastest possible reactive correction using public APIs without claiming impossible kernel-level vetoes.

---

## 5. Tecno / OEM Considerations (e.g. Tecno POP 7)

1. **Battery Management:** Configure **Settings → Battery Lab / Power Management → Volume Guard** to allow unrestricted background activity so HiOS does not kill the accessibility service.
2. **Audio Routing:** The included `AudioDeviceCallback` handles Tecno POP 7 volume profile switching when connecting/disconnecting Bluetooth headsets.
3. **Volume Sliders:** Consuming `KEYCODE_VOLUME_DOWN` (`return true`) prevents HiOS's on-screen volume panel from popping up.

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

## 7. Automated & Manual Test Checklist

The project includes 13 Robolectric JVM unit tests in `VolumeGuardTest.kt` verifying:
1. Operational activation clamps volume to 0.
2. `onKeyEvent(VOLUME_UP)` returns `false`, disengages guard, and persists `OFF`.
3. `onKeyEvent(VOLUME_DOWN)` returns `true` and avoids IPC when volume is already 0.
4. Service disconnect immediately deactivates operational protection while preserving user preference.
5. Service reconnect automatically restores persisted `ON` preference and enforces volume 0.
6. Guard `OFF` allows normal volume and passes through keys.
7. Reactive receiver path uses broadcast extras directly to clamp volume without `getStreamVolume()`.
8. Zero-volume broadcast causes no audio correction or IPC.
9. Unrelated stream broadcasts (`STREAM_RING`, `STREAM_ALARM`) are ignored.
10. Rapid Volume Up events are safe and idempotent.
11. Confirmed zero initial or previous volume storage or restoration.
12. Narrowly scoped ContentObserver triggers fallback volume correction.
13. Reactive monitors and fast flags are active strictly while operational.
