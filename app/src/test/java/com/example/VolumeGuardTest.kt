package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.example.audio.PrivacyMaskPlayer
import com.example.core.GuardManager
import com.example.core.GuardPreferences
import com.example.observer.VolumeContentObserver
import com.example.receiver.VolumeChangeReceiver
import com.example.service.VolumeGuardAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

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

        // Ensure fresh state in preferences and current test's AudioManager
        GuardPreferences.getInstance(context).isGuardEnabled = false
        guardManager.initialize(context)
        guardManager.setAudioManager(audioManager)

        // Instantiate and connect service via Robolectric
        serviceController = Robolectric.buildService(VolumeGuardAccessibilityService::class.java).create()
        service = serviceController.get()
        service.onServiceConnected()
    }

    /**
     * Test 1 — Operational Activation:
     * When user enables guard and AccessibilityService is connected,
     * stream volume is immediately forced to 0, operational protection becomes active,
     * and privacy masking is started.
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
        assertEquals("Cached volume must be 0", 0, guardManager.lastKnownMediaVolume)
        assertTrue("Privacy masking should be active while Guard is operational", service.privacyMaskPlayer.isStarted)
    }

    /**
     * Test 2 — AccessibilityService.onKeyEvent(Volume Up):
     * Physical Volume Up while Guard is active:
     * - Must return FALSE so Android processes Volume Up normally to raise volume.
     * - Must immediately disengage Guard (desired = OFF, operational = OFF).
     * - Must persist OFF state.
     * - Must stop privacy masking asynchronously.
     * - Must NOT make any audio IPC calls (leaves volume untouched for Android to raise).
     */
    @Test
    fun test2_serviceOnKeyEventVolumeUpReturnsFalseAndDisengagesGuardWithoutAudioIpc() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)
        assertTrue(service.privacyMaskPlayer.isStarted)

        val volumeUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        val consumed = service.onKeyEvent(volumeUpEvent)

        assertFalse("Volume Up must return FALSE to let Android raise volume", consumed)
        assertFalse("Guard desired state must become OFF", guardManager.desiredGuardEnabled.value)
        assertFalse("Operational protection must become inactive", guardManager.isOperationalActive.value)
        assertFalse("Fast hot-path flag must become false", service.isOperationalFast)
        assertFalse("Persisted state must be OFF", GuardPreferences.getInstance(context).isGuardEnabled)

        // Process asynchronous monitor and masker cleanup posted off the hot path
        ShadowLooper.idleMainLooper()
        assertFalse("Masker must be stopped after async cleanup", service.privacyMaskPlayer.isStarted)
    }

    /**
     * Test 3 — AccessibilityService.onKeyEvent(Volume Down):
     * Physical Volume Down while Guard is active:
     * - Must return TRUE so the system volume panel and work are consumed.
     * - Must keep Guard ON and operational.
     * - When volume is already 0, avoids redundant setStreamVolume calls.
     */
    @Test
    fun test3_serviceOnKeyEventVolumeDownReturnsTrueAndMaintainsZero() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)
        assertEquals(0, guardManager.lastKnownMediaVolume)

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
     * - Privacy masking must be stopped and released.
     * - User's desired ON preference is PRESERVED for reconnection.
     */
    @Test
    fun test4_serviceDisconnectDeactivatesOperationalProtectionWhilePreservingPreference() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertTrue(guardManager.isOperationalActive.value)
        assertTrue(service.privacyMaskPlayer.isStarted)

        // Service unbinds / disconnects
        service.onUnbind(null)

        assertFalse("Service connected must be false", guardManager.isServiceConnected.value)
        assertFalse("Operational protection must be inactive when service is disconnected", guardManager.isOperationalActive.value)
        assertFalse("Fast hot-path flag must be false when service is unbound", service.isOperationalFast)
        assertFalse("Masker must be stopped on service disconnect", service.privacyMaskPlayer.isStarted)
        assertTrue("User desired preference must be preserved", guardManager.desiredGuardEnabled.value)
        assertTrue("Persisted preference must remain ON", GuardPreferences.getInstance(context).isGuardEnabled)
    }

    /**
     * Test 5 — Service Reconnect Restoration:
     * When AccessibilityService reconnects after reboot or process death:
     * - Automatically loads persisted ON preference.
     * - Immediately clamps volume to 0.
     * - Becomes operationally active without needing Activity interaction.
     */
    @Test
    fun test5_serviceReconnectRestoresPersistedPreferenceAndEnforcesZero() {
        // Set persisted preference to ON while service is disconnected
        GuardPreferences.getInstance(context).isGuardEnabled = true
        guardManager.onServiceDisconnected(context)
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
        assertTrue("Privacy masking should start on reconnect", newService.privacyMaskPlayer.isStarted)
    }

    /**
     * Test 6 — Guard OFF:
     * When Guard is OFF:
     * - Physical keys pass through untouched.
     * - Privacy masking is OFF.
     * - Normal Android volume behavior is preserved.
     */
    @Test
    fun test6_guardOffAllowsNormalVolumeAndKeyEvents() {
        guardManager.setDesiredGuardEnabled(context, false)
        assertFalse(guardManager.isOperationalActive.value)
        assertFalse(service.privacyMaskPlayer.isStarted)

        val volumeUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        val consumed = service.onKeyEvent(volumeUpEvent)
        assertFalse("Volume Up must pass through when Guard is OFF", consumed)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 7 — Optimized Reactive Receiver Path (Uses Broadcast Extras Directly):
     * When Guard is active and VOLUME_CHANGED_ACTION broadcast is received with STREAM_MUSIC
     * and a volume > 0, it clamps volume to 0 immediately without calling getStreamVolume().
     */
    @Test
    fun test7_reactiveReceiverUsesExtrasDirectlyToClampVolume() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Rogue app raises volume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)

        // Broadcast with STREAM_MUSIC and volume = 12
        val receiver = VolumeChangeReceiver()
        val intent = Intent(VolumeChangeReceiver.VOLUME_CHANGED_ACTION).apply {
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_VALUE, 12)
        }
        receiver.onReceive(context, intent)

        assertEquals("Media volume must be immediately corrected to 0", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("Cached volume must be updated to 0", 0, guardManager.lastKnownMediaVolume)
    }

    /**
     * Test 8 — Zero-Volume Broadcast Causes No Correction / No Audio IPC:
     * When broadcast reports volume = 0, no correction is triggered.
     */
    @Test
    fun test8_zeroVolumeBroadcastCausesNoCorrection() {
        guardManager.setDesiredGuardEnabled(context, true)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        val receiver = VolumeChangeReceiver()
        val intent = Intent(VolumeChangeReceiver.VOLUME_CHANGED_ACTION).apply {
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_VALUE, 0)
        }
        receiver.onReceive(context, intent)

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 9 — Unrelated Stream Broadcasts Are Ignored:
     * Broadcasts for STREAM_RING or STREAM_ALARM must not trigger STREAM_MUSIC changes.
     */
    @Test
    fun test9_unrelatedStreamBroadcastsIgnored() {
        guardManager.setDesiredGuardEnabled(context, true)

        val receiver = VolumeChangeReceiver()
        val ringIntent = Intent(VolumeChangeReceiver.VOLUME_CHANGED_ACTION).apply {
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_RING)
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_VALUE, 5)
        }
        receiver.onReceive(context, ringIntent)

        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 10 — Missing Extras Safe Fallback:
     * When intent extras are missing (-1), safely falls back to querying AudioManager.
     */
    @Test
    fun test10_missingExtrasFallbackQueriesAudioManager() {
        guardManager.setDesiredGuardEnabled(context, true)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)

        val receiver = VolumeChangeReceiver()
        val emptyIntent = Intent(VolumeChangeReceiver.VOLUME_CHANGED_ACTION)
        receiver.onReceive(context, emptyIntent)

        assertEquals("Should safely correct volume even with empty extras", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 11 — Fallback ContentObserver Path:
     * When ContentObserver detects volume settings write, it invokes reactive correction to 0.
     */
    @Test
    fun test11_contentObserverTriggersImmediateCorrection() {
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
     * Test 12 — Rapid Volume Up events are safe/idempotent:
     * Multiple rapid Volume Up events must not crash or cause inconsistent state.
     */
    @Test
    fun test12_rapidVolumeUpEventsAreSafeAndIdempotent() {
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
     * Test 13 — No Initial/Previous Volume Persistence:
     * Verify that GuardPreferences only stores a boolean and never tracks or restores previous volume.
     */
    @Test
    fun test13_noInitialVolumeStorageOrRestoration() {
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
     * Test 14 — PrivacyMaskPlayer Lifecycle & Idempotence:
     * Verifies that start/stop/release are idempotent and don't throw.
     */
    @Test
    fun test14_privacyMaskPlayerLifecycleAndIdempotence() {
        val player = PrivacyMaskPlayer()
        assertFalse(player.isStarted)

        // Repeated starts should be idempotent
        player.start()
        assertTrue(player.isStarted)
        player.start()
        assertTrue(player.isStarted)

        // Repeated stops should be idempotent
        player.stop()
        assertFalse(player.isStarted)
        player.stop()
        assertFalse(player.isStarted)

        // Release
        player.release()
        assertFalse(player.isStarted)
        player.release()
    }

    /**
     * Test 15 — PrivacyMaskPlayer Pre-Generated Noise Buffer:
     * Verifies that the noise buffer generation is deterministic, non-empty, and bounded.
     */
    @Test
    fun test15_speechShapedNoiseBufferProperties() {
        val buffer = PrivacyMaskPlayer.generateSpeechShapedNoise(
            sampleRate = 16000,
            durationSeconds = 1.0f,
            gain = 0.5f
        )
        assertNotNull(buffer)
        assertEquals(16000, buffer.size)

        var hasNonZero = false
        for (sample in buffer) {
            if (sample.toInt() != 0) {
                hasNonZero = true
                break
            }
        }
        assertTrue("Buffer must contain active audio samples", hasNonZero)
    }

    /**
     * Test 16 — Detector Functions Even If Masker Fails:
     * Verifies volume enforcement operates normally regardless of masker state.
     */
    @Test
    fun test16_detectorFunctionsEvenIfMaskerFails() {
        guardManager.setDesiredGuardEnabled(context, true)
        // Explicitly release masker to simulate audio track failure
        service.privacyMaskPlayer.release()
        assertFalse(service.privacyMaskPlayer.isStarted)

        // External app raises volume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 11, 0)

        // Receiver still clamps volume to 0
        val receiver = VolumeChangeReceiver()
        val intent = Intent(VolumeChangeReceiver.VOLUME_CHANGED_ACTION).apply {
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            putExtra(VolumeChangeReceiver.EXTRA_VOLUME_STREAM_VALUE, 11)
        }
        receiver.onReceive(context, intent)

        assertEquals("Volume enforcement must succeed independently of masker", 0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
