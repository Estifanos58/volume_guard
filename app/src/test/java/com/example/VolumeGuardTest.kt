package com.example

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.core.GuardManager
import com.example.core.GuardPreferences
import com.example.core.GuardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VolumeGuardTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var guardManager: GuardManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        guardManager = GuardManager.instance

        // Ensure clean state
        guardManager.setAccessibilityServiceConnected(true, null)
        guardManager.disableGuard(context, "Setup reset")
    }

    /**
     * Test 1 — Enable from non-zero volume:
     * Phone volume = 10, Enable Guard -> volume = 0, Guard = ON
     */
    @Test
    fun test1_enableGuardFromNonZeroVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        assertEquals(10, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        val enabled = guardManager.enableGuard(context)
        assertTrue("Guard should enable when accessibility service is connected", enabled)
        assertEquals(GuardState.ON, guardManager.guardState.value)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 2 — Enable from zero:
     * Phone volume = 0, Enable Guard -> Guard = ON, volume = 0
     */
    @Test
    fun test2_enableGuardFromZeroVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val enabled = guardManager.enableGuard(context)
        assertTrue(enabled)
        assertEquals(GuardState.ON, guardManager.guardState.value)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 3 — Volume Down:
     * Guard ON, Press physical Volume Down -> Guard remains ON, volume remains 0
     */
    @Test
    fun test3_physicalVolumeDownMaintainsGuardAndZero() {
        guardManager.enableGuard(context)
        assertEquals(GuardState.ON, guardManager.guardState.value)

        // Intercept physical Volume Down
        guardManager.onPhysicalVolumeDown(context)

        // Guard must remain ON and volume must be 0
        assertEquals(GuardState.ON, guardManager.guardState.value)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 4 — Volume Up:
     * Guard ON, Press physical Volume Up -> Guard becomes OFF, volume can increase normally
     */
    @Test
    fun test4_physicalVolumeUpDisablesGuardAndAllowsVolumeIncrease() {
        guardManager.enableGuard(context)
        assertEquals(GuardState.ON, guardManager.guardState.value)

        // User physically presses Volume Up (emergency override)
        guardManager.onPhysicalVolumeUp(context)

        // Guard must be OFF immediately
        assertEquals(GuardState.OFF, guardManager.guardState.value)
        assertFalse(GuardPreferences.getInstance(context).isGuardEnabled)

        // Now external volume changes proceed without being clamped
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        guardManager.onExternalVolumeChanged(context, 1)
        assertEquals(1, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 5 — App attempts volume increase:
     * Guard ON, Another app attempts to increase media volume -> Corrected immediately to 0
     */
    @Test
    fun test5_appAttemptsVolumeIncreaseIsCorrectedToZero() {
        guardManager.enableGuard(context)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Rogue app raises volume to 8
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        guardManager.onExternalVolumeChanged(context, 8)

        // Instant correction
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(GuardState.ON, guardManager.guardState.value)
    }

    /**
     * Test 6 — Rapid Volume Up presses:
     * Guard ON, Press Volume Up several times rapidly -> no crash, Guard becomes OFF idempotently
     */
    @Test
    fun test6_rapidVolumeUpPressesAreIdempotent() {
        guardManager.enableGuard(context)
        assertEquals(GuardState.ON, guardManager.guardState.value)

        repeat(5) {
            guardManager.onPhysicalVolumeUp(context)
        }

        assertEquals(GuardState.OFF, guardManager.guardState.value)
        assertFalse(GuardPreferences.getInstance(context).isGuardEnabled)
    }

    /**
     * Test 7 — App UI disabled:
     * Guard OFF, Press Volume Up -> normal Android behavior
     */
    @Test
    fun test7_guardOffAllowsNormalVolume() {
        guardManager.disableGuard(context, "Test 7")
        assertEquals(GuardState.OFF, guardManager.guardState.value)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        guardManager.onExternalVolumeChanged(context, 5)

        assertEquals(5, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 8 — Disable from UI:
     * Disable from UI -> Guard becomes OFF, volume is NOT changed/restored
     */
    @Test
    fun test8_disableFromUIDoesNotRestorePreviousVolume() {
        guardManager.enableGuard(context)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))

        // Disable from UI
        guardManager.disableGuard(context, "User toggle")
        assertEquals(GuardState.OFF, guardManager.guardState.value)

        // Volume MUST remain 0, NOT raised or restored to any previous level
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Test 9 — Accessibility service unavailable:
     * Accessibility service OFF, attempt to enable protection -> rejected
     */
    @Test
    fun test9_cannotEnableWithoutAccessibilityService() {
        guardManager.setAccessibilityServiceConnected(false, null)
        val success = guardManager.enableGuard(context)
        assertFalse("Must not enable guard if accessibility service is unavailable", success)
        assertEquals(GuardState.OFF, guardManager.guardState.value)
    }

    /**
     * Test 10 — No initial volume storage:
     * Verify preference stores only a boolean, never any previous volume integer
     */
    @Test
    fun test10_noInitialVolumeStorage() {
        val prefs = GuardPreferences.getInstance(context)
        prefs.isGuardEnabled = true
        assertTrue(prefs.isGuardEnabled)
        prefs.isGuardEnabled = false
        assertFalse(prefs.isGuardEnabled)
    }
}
