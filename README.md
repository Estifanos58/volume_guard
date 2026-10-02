# Volume Guard

**Volume Guard** is a lightweight, defensive Android utility designed to protect users against rogue, prank, or malicious applications that attempt to unexpectedly raise media volume to maximum.

---

## 1. What the App Does

- **Volume Locking:** When the guard is enabled, media volume (`AudioManager.STREAM_MUSIC`) is immediately forced to **0**.
- **Hardware Button Interception:** Utilizes Android's `AccessibilityService` (`FLAG_REQUEST_FILTER_KEY_EVENTS`) to inspect physical hardware volume keys *before* they are processed by the system window manager:
  - **Physical Volume Up:** Serves as the user's intentional emergency override. It immediately disables the guard and allows the Volume Up event to pass through untouched so Android raises the volume normally.
  - **Physical Volume Down:** Consumed to prevent unnecessary volume popups while keeping the guard strictly active and volume locked to 0.
- **Immediate Reactive Correction:** Listens for system volume change broadcasts (`android.media.VOLUME_CHANGED_ACTION`) and system settings content updates (`Settings.System.CONTENT_URI`). If any external app attempts to programmatic raise volume, Volume Guard immediately forces it back to 0 without high-frequency polling.
- **Low Footprint:** 0% idle CPU usage, zero network traffic, zero analytics/telemetry, no external database, and minimal memory usage.

---

## 2. Extremely Important Operational Rules

1. **Guard OFF:**
   - Normal Android behavior.
   - Physical volume buttons operate normally.
   - Other apps can modify media volume normally.

2. **Guard ON:**
   - Protected media volume is **ALWAYS 0**.
   - No initial volume is ever saved or tracked.
   - No old volume is ever restored when disabled.
   - Turning the guard OFF keeps the volume at whatever level Android currently has at that moment; it will never automatically raise the volume.

3. **Physical Volume Up (Override):**
   - Pressing Volume Up while Guard is ON immediately switches the guard state to **OFF**.
   - The event is **not consumed**; Android receives it and raises the volume.
   - Volume Guard will NOT force volume back to 0 after this override.

4. **Physical Volume Down:**
   - Guard remains **ON**.
   - Volume remains **0**.

---

## 3. Architecture & Technical Design

### Interception Model

```text
                             [ Physical Volume Key Pressed ]
                                            │
                                            ▼
                           [ VolumeGuardAccessibilityService ]
                              (canRequestFilterKeyEvents=true)
                                            │
                     ┌──────────────────────┴──────────────────────┐
                     ▼                                             ▼
             [ KEYCODE_VOLUME_UP ]                         [ KEYCODE_VOLUME_DOWN ]
                     │                                             │
      ┌──────────────┴──────────────┐                              │
      │ Guard is ON                 │ Guard is OFF                 │ Guard is ON
      ▼                             ▼                              ▼
 1. Guard State -> OFF         Pass through                  1. Keep Guard ON
 2. Return FALSE               (Return FALSE)                2. Enforce Volume 0
 (Android raises volume)                                     3. Return TRUE (Consumed)
```

### Unrooted Android Platform Limitations (Technical Notice)

In standard Android architecture (AOSP):
1. **Physical Keys:** Preemptable via `AccessibilityService.onKeyEvent()`. Because our service registers `flagRequestFilterKeyEvents`, physical volume buttons are delivered to Volume Guard *before* the window manager or audio service processes them.
2. **Programmatic Volume Changes:** Third-party applications invoke `AudioManager.setStreamVolume()` directly via IPC to system `AudioService`. On an unrooted Android OS, third-party apps without platform system signature cannot intercept or pre-veto IPC calls made by other applications before they reach `AudioService`.
3. **Reactive Defense:** Rather than claiming impossible pre-dispatch vetoes, Volume Guard implements the strongest supported unrooted defense:
   - Dynamic BroadcastReceiver for `android.media.VOLUME_CHANGED_ACTION`.
   - ContentObserver for `Settings.System.CONTENT_URI`.
   - These interrupt-driven triggers react within milliseconds when another application attempts a volume modification, instantly executing `setStreamVolume(STREAM_MUSIC, 0, 0)` without battery-draining polling loops.

---

## 4. How to Build the APK

In Android Studio or from the command line:

```bash
# Build the debug APK:
gradle assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. How to Install the APK

1. Transfer `app-debug.apk` to your Android device via USB, ADB, or file transfer:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. If prompted on the device, allow installation from unknown sources.

---

## 6. How to Enable the Accessibility Service in Android Settings

An unrooted Android application cannot silently grant itself accessibility capabilities. You must enable it once:

1. Open **Volume Guard**.
2. If the Accessibility Service is not active, a warning card will appear. Tap **"Open Accessibility Settings"**.
3. In the system Accessibility menu:
   - Tap **Installed apps** / **Downloaded apps** (or **Accessibility Services**).
   - Tap **Volume Guard Key Interceptor**.
   - Toggle the switch to **ON** and confirm Android's permission prompt.
4. Return to Volume Guard. The status will now show **CONNECTED**.

---

## 7. Tecno / OEM-Specific Considerations (e.g. Tecno POP 7)

On devices running custom Android skins such as **Tecno HiOS**:
1. **Aggressive Background Killing:**
   - Go to device **Settings → Battery Lab / Power Management → App Optimization / Auto-start**.
   - Ensure **Volume Guard** is set to "Allow background activity" or "No restrictions".
2. **Accessibility Permission Revocation:**
   - Some OEM battery managers disable accessibility services when an app is unused for days.
   - Volume Guard's UI detects this state immediately and guides you to re-enable it if needed.
3. **Volume Panel Interception:**
   - Consuming `KEYCODE_VOLUME_DOWN` prevents HiOS's on-screen volume slider from flashing on screen.

---

## 8. Verification & Test Checklist

| # | Test Scenario | Steps | Expected Result |
|---|---|---|---|
| 1 | Enable from non-zero | Set volume to 10. Open app. Toggle Guard ON. | Volume becomes 0. Status shows ACTIVE. |
| 2 | Enable from zero | Set volume to 0. Toggle Guard ON. | Guard activates. Volume stays 0. |
| 3 | Physical Volume Down | Guard ON. Press physical Volume Down button. | Guard remains ON. Volume remains 0. |
| 4 | Physical Volume Up | Guard ON. Press physical Volume Up button. | Guard immediately disengages (OFF). Android handles Volume Up and volume increases. |
| 5 | Rogue app volume raise | Guard ON. Another app calls `setStreamVolume(5)`. | Volume Guard detects change and immediately slams volume back to 0. |
| 6 | Rapid Volume Up | Guard ON. Tap Volume Up rapidly 3 times. | Guard stays OFF. No crashes, idempotent handling. |
| 7 | Guard OFF | Guard OFF. Press volume buttons. | Normal Android volume control. |
| 8 | UI Disable | Guard ON (vol 0). Toggle switch to OFF. | Guard becomes OFF. Volume remains 0 (not restored). |
| 9 | Accessibility missing | Turn off accessibility in OS. Open app. | UI informs user that Accessibility is required; Guard cannot activate until granted. |
| 10 | Screen locked | Guard ON. Lock screen. Press Volume Down. | Volume remains 0. Guard remains ON. |
