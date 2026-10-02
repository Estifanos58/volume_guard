package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.core.GuardManager
import com.example.core.GuardPreferences
import com.example.observer.VolumeContentObserver
import com.example.receiver.VolumeChangeReceiver
import com.example.service.VolumeGuardAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VolumeGuardTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var guardManager: GuardManager
    private lateinit var serviceController: ServiceController<VolumeGuardAccessibilityService>
    private lateinit var service: VolumeGuardAccessibilityService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        guardManager = GuardManager.instance

        // Ensure fresh state in preferences
        GuardPreferences.getInstance(context).isGuardEnabled = false

        // Instantiate and connect service via Robolectric
        serviceController = Robolectric.buildService(VolumeGuardAccessibilityService::class.java).create()
        service = serviceController.get()
        service.onServiceConnected()
    }

    /**
     * Test 1 — Operational Activation:
     * When user enables guard and AccessibilityService is connected,
     * stream volume is immediately forced to 0 and operational protection becomes active.
     */
    @Test
    fun test1_enableGuardForcesVolumeZeroAndActivatesOperationalState() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        assertEquals(10, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        guardManager.setDesiredGuardEnabled(context, true)

        assertTrue("Desired state must be ON", guardManager.desiredGuardEnabled.value)
        assertTrue("Service must be connected", guardManager.isServiceConnected.value)
        assertTrue("Protection must be operational", guardManager.isOperationalActive.value)
        assertTrue("Fast hot-path flag must be true", service.isOperationalFast)
        assertEquals("Volume must be immediately forced to 0", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 2 — AccessibilityService.onKeyEvent(Volume Up):
     * Physical Volume Up while Guard is active:
     * - Must return FALSE so Android processes Volume Up normally to raise volume.
     * - Must immediately disengage Guard (desired = OFF, operational = OFF).
     * - Must persist OFF state.
     * - Must not programmatically alter volume.
     */
    @Test
    fun test2_serviceOnKeyEventVolumeUpReturnsFalseAndDisengagesGuard() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)

        val volumeUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        val consumed = service.onKeyEvent(volumeUpEvent)

        assertFalse("Volume Up must return FALSE to let Android raise volume", consumed)
        assertFalse("Guard desired state must become OFF", guardManager.desiredGuardEnabled.value)
        assertFalse("Operational protection must become inactive", guardManager.isOperationalActive.value)
        assertFalse("Fast hot-path flag must become false", service.isOperationalFast)
        assertFalse("Persisted state must be OFF", GuardPreferences.getInstance(context).isGuardEnabled)
    }

    /**
     * Test 3 — AccessibilityService.onKeyEvent(Volume Down):
     * Physical Volume Down while Guard is active:
     * - Must return TRUE so the system volume panel and work are consumed.
     * - Must keep Guard ON and operational.
     * - Must ensure volume remains 0.
     */
    @Test
    fun test3_serviceOnKeyEventVolumeDownReturnsTrueAndMaintainsZero() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)

        val volumeDownEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
        val consumed = service.onKeyEvent(volumeDownEvent)

        assertTrue("Volume Down must return TRUE to consume the event", consumed)
        assertTrue("Guard must remain operational", guardManager.isOperationalActive.value)
        assertEquals("Volume must remain 0", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 4 — Service Disconnect:
     * When AccessibilityService disconnects:
     * - Operational protection must immediately become INACTIVE.
     * - Hardware key protection must not be falsely claimed.
     * - User's desired ON preference must be PRESERVED so it can recover on reconnect.
     */
    @Test
    fun test4_serviceDisconnectDeactivatesOperationalProtectionWhilePreservingPreference() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)

        // Service unbinds / disconnects
        service.onUnbind(null)

        assertFalse("Service connected must be false", guardManager.isServiceConnected.value)
        assertFalse("Operational protection must be inactive when service is disconnected", guardManager.isOperationalActive.value)
        assertFalse("Fast hot-path flag must be false when service is unbound", service.isOperationalFast)
        assertTrue("User desired preference must be preserved", guardManager.desiredGuardEnabled.value)
        assertTrue("Persisted preference must remain ON", GuardPreferences.getInstance(context).isGuardEnabled)
    }

    /**
     * Test 5 — Service Reconnect Restoration:
     * When AccessibilityService reconnects after reboot or process death:
     * - Automatically loads persisted ON preference.
     * - Immediately clamps media volume to 0.
     * - Becomes operationally active without needing Activity interaction.
     */
    @Test
    fun test5_serviceReconnectRestoresPersistedPreferenceAndEnforcesZero() {
        // Set persisted preference to ON while service is disconnected
        GuardPreferences.getInstance(context).isGuardEnabled = true
        guardManager.onServiceDisconnected()
        assertFalse(guardManager.isOperationalActive.value)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        assertEquals(8, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // New service connects (simulating OS starting/reconnecting the service)
        val newServiceController = Robolectric.buildService(VolumeGuardAccessibilityService::class.java).create()
        val newService = newServiceController.get()
        newService.onServiceConnected()

        assertTrue("Service connected should be true", guardManager.isServiceConnected.value)
        assertTrue("Operational protection should recover to ACTIVE", guardManager.isOperationalActive.value)
        assertTrue("Fast hot-path flag must be active", newService.isOperationalFast)
        assertEquals("Media volume must be forced to 0 on reconnection", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 6 — Guard OFF:
     * When Guard is OFF:
     * - Physical keys pass through untouched.
     * - Normal Android volume behavior is preserved.
     */
    @Test
    fun test6_guardOffAllowsNormalVolumeAndKeyEvents() {
        guardManager.setDesiredGuardEnabled(context, false)
        assertFalse(guardManager.isOperationalActive.value)

        val volumeUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        val consumed = service.onKeyEvent(volumeUpEvent)
        assertFalse("Volume Up must pass through when Guard is OFF", consumed)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 7 — Reactive Receiver Path:
     * When Guard is active and a rogue app triggers an external volume change broadcast,
     * VolumeChangeReceiver immediately clamps volume back to 0.
     */
    @Test
    fun test7_reactiveReceiverClampsExternalVolumeToZero() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Rogue app raises volume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)

        // Broadcast received
        val receiver = VolumeChangeReceiver()
        val intent = Intent(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
        receiver.onReceive(context, intent)

        assertEquals("Media volume must be immediately corrected to 0", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 8 — Rapid Volume Up events are safe/idempotent:
     * Multiple rapid Volume Up events must not crash or cause inconsistent state.
     */
    @Test
    fun test8_rapidVolumeUpEventsAreSafeAndIdempotent() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        repeat(5) {
            val consumed = service.onKeyEvent(event)
            assertFalse(consumed)
        }

        assertFalse(guardManager.desiredGuardEnabled.value)
        assertFalse(guardManager.isOperationalActive.value)
    }

    /**
     * Test 9 — No Initial/Previous Volume Persistence:
     * Verify that GuardPreferences only stores a boolean and never tracks or restores previous volume.
     */
    @Test
    fun test9_noInitialVolumeStorageOrRestoration() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)

        // Enable guard
        guardManager.setDesiredGuardEnabled(context, true)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Disable guard from UI
        guardManager.setDesiredGuardEnabled(context, false)

        // Volume MUST remain at 0, not restored to 9
        assertEquals("Disabling guard must NOT restore previous volume", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 10 — Narrowly Scoped ContentObserver Path:
     * When ContentObserver detects volume settings write, it invokes reactive correction to 0.
     */
    @Test
    fun test10_contentObserverTriggersImmediateCorrection() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Another app changes volume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)

        // ContentObserver fires
        val observer = VolumeContentObserver(context)
        observer.onChange(false, null)

        assertEquals("Media volume must be corrected back to 0", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 11 — Monitors Active Only When Operational:
     * Verifies that fast operational flag and monitors are deactivated when Guard is toggled OFF.
     */
    @Test
    fun test11_monitorsAreActiveOnlyWhenOperational() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(service.isOperationalFast)

        guardManager.setDesiredGuardEnabled(context, false)
        assertFalse(service.isOperationalFast)
    }
}
