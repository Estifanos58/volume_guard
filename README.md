# Volume Guard

**Volume Guard** is a specialized, zero-overhead defensive Android utility designed to prevent unwanted media volume spikes caused by rogue, prank, or malfunctioning applications.

---

## 1. Core Operational Contract

* **Target Volume:** Strictly **`0`** (`AudioManager.STREAM_MUSIC`).
* **Zero Initial/Previous Volume Storage:** The app never records, remembers, or restores previous volume levels. Disabling the guard leaves volume at whatever value the system currently has.
* **Guard Active + Physical Volume Up:**
  - Disengages the guard immediately (desired state -> `OFF`, operational state -> `inactive`).
  - Does **not** programmatically modify volume.
  - Returns `false` from `onKeyEvent()` so Android receives the key event and raises volume normally.
* **Guard Active + Physical Volume Down:**
  - Keeps the guard active.
  - Ensures media volume remains `0`.
  - Returns `true` from `onKeyEvent()` to consume the event and suppress redundant system volume UI.
* **Guard Inactive:**
  - Passes all hardware volume keys through untouched (`return false`).
  - Normal Android volume behavior.

---

## 2. Technical Architecture: Interception vs. Reactive Correction

### A. Physical Hardware Keys: True Pre-Dispatch Interception
Android allows an accessibility service declaring `flagRequestFilterKeyEvents` and `canRequestFilterKeyEvents="true"` to observe raw hardware key events **before** they are dispatched to the window manager or the audio subsystem.
- In `VolumeGuardAccessibilityService.onKeyEvent()`, an `@Volatile` flag (`isOperationalFast`) enables O(1) key filtering on the hot path with:
  - **Zero object allocations**
  - **Zero logging overhead**
  - **Zero StateFlow or coroutine hops**

### B. Programmatic Volume Changes: Best-Effort Reactive Correction
**Platform Reality on Unrooted Android:**
- When an application executes `AudioManager.setStreamVolume()`, it communicates directly with `AudioService` via IPC/Binder.
- **No public API exists for an unrooted third-party app to pre-veto or block another app's IPC call before Android applies it.** Claims of universal preemption on unrooted Android are technically impossible without system signature privileges or root.
- **Volume Guard's Defensive Response:**
  Volume Guard implements immediate, interrupt-driven reactive correction active **only while protection is operational**:
  1. **Hardened `VolumeChangeReceiver`:** Listens for `android.media.VOLUME_CHANGED_ACTION`. Verifies the true hardware volume directly via `AudioManager` (immune to spoofed intent extras) and immediately clamps to `0`. Registered with `Context.RECEIVER_EXPORTED` on API 33+ as required for system broadcasts.
  2. **Narrowly Scoped `VolumeContentObserver`:** Observes specific media volume setting URIs (`volume_music_speaker`, `volume_music`) rather than the broad `Settings.System.CONTENT_URI`, avoiding spurious wakeups from brightness or screen timeout changes.
  3. **`AudioPlaybackCallback` (API 26+):** Detects when rogue media tracks spin up and verifies volume is clamped to `0`.
  4. **Double Enforcement:** Applies both `setStreamVolume(STREAM_MUSIC, 0, 0)` and `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)`.

When the guard is toggled `OFF` or the service disconnects, all three reactive monitors are immediately unregistered, leaving **0% background CPU consumption and zero polling loops**.

---

## 3. Desired State vs. Operational State

To prevent race conditions, false security indicators, and boot loops:

1. **`desiredGuardEnabled` (User Preference):**
   - Stored in `SharedPreferences`. Preserved across process restarts, service disconnects, and reboots.
2. **`isServiceConnected` (Service Lifecycle):**
   - Tracks live connection status of `VolumeGuardAccessibilityService`.
3. **`isOperationalActive` (Operational Status):**
   - Active **only** when `desiredGuardEnabled == true && isServiceConnected == true`.
   - If the accessibility service disconnects or dies, operational protection is immediately marked **inactive**. The UI warns that the service is disconnected and never falsely displays "Guard Active".
   - When the service connects or reconnects (e.g. after reboot), it reads the persisted preference and re-establishes volume `0` protection automatically.

---

## 4. Resource Usage & Minimal Dependencies

- **Platform Framework First:** Relies directly on Android system APIs (`AudioManager`, `AccessibilityService`, `BroadcastReceiver`, `ContentObserver`).
- **Cleaned Dependencies:** Leftover template libraries (Firebase, Google Services, Secrets, Camera, Room, Retrofit, OkHttp) have been completely removed.
- **Network Permissions:** Strictly **0 network permissions** (`INTERNET` is not requested).
- **Logging:** All diagnostic logs are gated behind `BuildConfig.DEBUG`. Production release builds emit zero diagnostic log spam.

---

## 5. Tecno / OEM Considerations (e.g. Tecno POP 7)

On devices running custom Android distributions such as Tecno HiOS:
1. **Aggressive Battery Management:** Custom OS battery managers may stop background accessibility services. Configure **Settings → Battery Lab / Power Management → Volume Guard** to allow unrestricted background activity.
2. **Accessibility Permission Re-check:** If the OS disables accessibility after prolonged idle time, Volume Guard detects this on activity resume and prompts the user to re-enable it.
3. **Volume Sliders:** Consuming `KEYCODE_VOLUME_DOWN` (`return true`) successfully suppresses the on-screen HiOS volume slider popup.

---

## 6. How to Build & Install

```bash
# Build the debug APK:
gradle assembleDebug

# Output APK:
# app/build/outputs/apk/debug/app-debug.apk

# Install via ADB:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. Manual Test Checklist (Physical Device)

| # | Test Scenario | Steps | Expected Result |
|---|---|---|---|
| 1 | Enable from non-zero | Set media volume to 10. Open app. Toggle Guard ON. | Volume immediately drops to 0. Status shows ACTIVE. |
| 2 | Enable from zero | Set volume to 0. Toggle Guard ON. | Guard activates. Volume remains 0. |
| 3 | Physical Volume Down | With Guard ON, press physical Volume Down. | Volume remains 0. Guard remains ON. |
| 4 | Physical Volume Up | With Guard ON, press physical Volume Up. | Guard disengages immediately. Phone volume increases normally. |
| 5 | Background / App Switch | Guard ON. Open YouTube/Telegram/Browser. Play media. | Volume remains locked at 0. |
| 6 | Screen Locked | Guard ON. Lock screen. Press Volume Down. | Volume remains 0. |
| 7 | Screen Locked + Volume Up | Guard ON. Lock screen. Press Volume Up. | Guard disengages. Volume increases. |
| 8 | Rapid Button Presses | Guard ON. Rapidly press Volume Up 5 times. | Guard stays OFF. No crashes or lockups. |
| 9 | UI Disable | Guard ON (vol 0). Toggle switch OFF in app. | Guard becomes OFF. Volume remains 0 (not restored). |
| 10 | Service Disconnection | In Android Settings, disable Volume Guard accessibility. | App UI updates immediately to "Protection Inactive". |
| 11 | Service Reconnection | Re-enable accessibility in Settings. | Guard automatically restores volume 0 protection. |
