# Volume Guard

**Volume Guard** is a lightweight, defensive Android utility designed to mitigate rogue, prank, or malicious applications that attempt to unexpectedly raise media volume to maximum.

---

## 1. What the App Does

- **Volume Locking:** When operational protection is active, media volume (`AudioManager.STREAM_MUSIC`) is forced to **0**.
- **Hardware Button Interception:** Utilizes Android's `AccessibilityService` (`FLAG_REQUEST_FILTER_KEY_EVENTS`) to inspect physical hardware volume keys *before* they are processed by the system window manager:
  - **Physical Volume Up:** Serves as the user's intentional emergency override. It immediately disables the guard and returns `false` so Android processes the Volume Up event normally and raises the volume.
  - **Physical Volume Down:** Consumed (`return true`) to prevent unnecessary volume sliders while keeping the guard strictly active and volume locked to 0.
- **Dynamic Reactive Monitoring:** While protection is operational, listens for system volume broadcasts (`android.media.VOLUME_CHANGED_ACTION`) and system settings updates (`Settings.System.CONTENT_URI`). If an external app changes volume, Volume Guard reactively forces it back to 0.
- **Resource Efficient:** Reactive monitors are unregistered whenever protection is inactive or the service is disconnected. Zero background polling loops, zero network permissions, zero wake locks.

---

## 2. Desired State vs. Operational State

To prevent misleading status displays and race conditions:

1. **User Desired State (`desiredGuardEnabled`):**
   - The user's persisted preference in `SharedPreferences`.
   - Preserved across service disconnects and process lifecycles.

2. **Accessibility Service Status (`isServiceConnected`):**
   - Tracks whether `VolumeGuardAccessibilityService` is actively running and bound by Android.

3. **Operational Protection (`isOperationalActive`):**
   - Protection is active **ONLY** when:
     ```text
     desiredGuardEnabled == true
     AND
     isServiceConnected == true
     ```
   - If the AccessibilityService disconnects, operational protection is marked **INACTIVE** immediately. The UI clearly reports "Protection Inactive — Accessibility Service Disconnected" and never falsely claims hardware-key protection is active.
   - When the AccessibilityService reconnects (e.g. after reboot or process restart), it automatically checks the persisted preference and re-establishes volume 0 protection without requiring the Activity to be opened.

---

## 3. Physical Volume Button Contract

1. **Guard Active + Physical Volume Up:**
   - Detects Volume Up key press.
   - Disables guard immediately (sets user desired state to OFF, updates persistent state).
   - Does NOT programmatically alter or reset volume.
   - Returns `false` so Android receives the hardware key event and increases volume normally.

2. **Guard Active + Physical Volume Down:**
   - Detects Volume Down key press.
   - Keeps guard active.
   - Ensures media volume is 0.
   - Returns `true` to consume the event and suppress redundant system volume UI.

3. **Guard Inactive:**
   - Passes all hardware key events through untouched (`return false`).
   - Normal Android volume behavior.

4. **Zero Volume Target:**
   - Protected volume is **always 0**.
   - No initial volume or previous volume is ever recorded or restored.
   - Disabling the guard leaves volume at whatever level Android currently has at that moment.

---

## 4. Platform Limitations & Programmatic Volume Changes

### Realities of Unrooted Android
On an unrooted, standard Android device:
- **Hardware Keys:** Preemptable via `AccessibilityService.onKeyEvent()`. Because our service registers `flagRequestFilterKeyEvents`, physical volume buttons are delivered to Volume Guard *before* the window manager or audio service processes them.
- **Programmatic Volume Changes:** Third-party applications invoke `AudioManager.setStreamVolume()` directly via IPC to the system `AudioService`. On unrooted Android without system signatures or hidden platform privileges, no third-party application can intercept or pre-veto IPC calls made by other applications before they reach `AudioService`.

### Best-Effort Reactive Correction
Rather than claiming an impossible pre-dispatch veto:
- Volume Guard dynamically registers a broadcast receiver for `android.media.VOLUME_CHANGED_ACTION` and a `ContentObserver` on `Settings.System.CONTENT_URI` **only while operational protection is active**.
- When an external application raises volume, Android updates its internal settings; Volume Guard receives this system event and issues an immediate reactive correction (`setStreamVolume(STREAM_MUSIC, 0, 0)`).
- When protection is inactive, all broadcast receivers and content observers are completely unregistered.

---

## 5. How to Build the APK

In Android Studio or from the command line:

```bash
# Build the debug APK:
gradle assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. How to Install the APK

1. Transfer `app-debug.apk` to your Android device via USB or ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. If prompted on the device, allow installation from unknown sources.

---

## 7. Enabling the Accessibility Service

1. Open **Volume Guard**.
2. Tap **"Open Accessibility Settings"**.
3. Under **Installed apps** (or **Accessibility Services**), select **Volume Guard Key Interceptor**.
4. Enable the service and accept Android's confirmation prompt.
5. Return to Volume Guard; the status will now show **CONNECTED**.

---

## 8. Tecno / OEM-Specific Considerations (e.g. Tecno POP 7)

On devices running customized Android distributions (such as Tecno HiOS):
1. **Background Management:**
   - In **Settings → Battery Lab / Power Management**, configure Volume Guard to allow unrestricted background activity.
2. **Accessibility Permission Preservation:**
   - If the OEM system suspends accessibility permissions after extended inactivity, Volume Guard detects the disconnection immediately and prompts the user to re-enable it.
3. **Volume Popups:**
   - Consuming `KEYCODE_VOLUME_DOWN` (`return true`) prevents HiOS's custom on-screen volume slider from displaying when the button is pressed.

---

## 9. Automated & Manual Test Checklist

The project includes local Robolectric JVM tests in `VolumeGuardTest.kt` verifying:
1. Operational activation clamps volume to 0.
2. `AccessibilityService.onKeyEvent(Volume Up)` returns `false`, disengages guard, and persists OFF.
3. `AccessibilityService.onKeyEvent(Volume Down)` returns `true` and maintains volume 0.
4. Service disconnect immediately changes operational state to inactive while preserving user preference.
5. Service reconnect restores persisted ON preference and clamps volume to 0.
6. Guard OFF allows normal volume and passes through keys.
7. Reactive receiver path clamps external volume changes to 0.
8. Rapid Volume Up events are safe and idempotent.
9. No initial or previous volume is stored or restored.
