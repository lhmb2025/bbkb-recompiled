package dev.bbkb.ime.core.device.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins {@link DeviceProfile#installForTest(DeviceCapabilities)} (a JVM scan always answers "VKB, no
 * physical keyboard") and that the two force-VKB levers behave the same over an installed shape as
 * over a detected one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class DeviceProfileShapeTest {
    /** A CKB handset: physical keyboard with a capacitive overlay. */
    private static DeviceCapabilities ckbHandset() {
        return DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
                true, true, true, "qwerty", "4row");
    }

    private static DeviceCapabilities touchscreenOnly() {
        return DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.VKB,
                false, false, false, "qwerty", "none");
    }

    @After
    public void tearDown() {
        // The runtime state is a process-wide singleton and initialize(null) does not clear it.
        DeviceProfile.setForceVkbMode(false);
        DeviceProfile.setDebugForceVkbMode(false);
        DeviceProfile.setOnScreenKeyboardShowing(false);
        DeviceProfile.initialize(null);
    }

    @Test
    public void installedPkbShapeIsWhatCurrentReports() {
        DeviceProfile.installForTest(ckbHandset());

        assertTrue(DeviceProfile.current().isPkbDevice());
        assertFalse(DeviceProfile.current().isVkbDevice());
        assertTrue(DeviceProfile.current().hasPhysicalKeyboard());
        assertTrue(DeviceProfile.current().hasTouchKeypad());
        assertTrue(DeviceProfile.current().isBlackBerryDevice());
        assertEquals("qwerty", DeviceProfile.current().getKeypadLayout());
        assertEquals("4row", DeviceProfile.current().getKeypadVariant());
        // The derived answers the settings screens branch on.
        assertFalse(DeviceProfile.current().supportsSwipeTyping());
        assertFalse(DeviceProfile.current().supportsSlideboard());
        assertTrue(DeviceProfile.isPkb());
    }

    @Test
    public void installedVkbShapeIsWhatCurrentReports() {
        DeviceProfile.installForTest(touchscreenOnly());

        assertFalse(DeviceProfile.current().isPkbDevice());
        assertTrue(DeviceProfile.current().isVkbDevice());
        assertFalse(DeviceProfile.current().hasPhysicalKeyboard());
        assertFalse(DeviceProfile.current().hasTouchKeypad());
        assertTrue(DeviceProfile.current().supportsSwipeTyping());
        assertTrue(DeviceProfile.current().supportsSlideboard());
    }

    @Test
    public void aHybridShapeCountsAsPkb() {
        // An external keyboard attached to a touchscreen phone.
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.HYBRID, true, false, false, "qwerty", "none"));
        assertTrue(DeviceProfile.current().isPkbDevice());
        assertTrue(DeviceProfile.current().hasPhysicalKeyboard());
    }

    @Test
    public void initializeDiscardsAnInstalledShape() {
        DeviceProfile.installForTest(ckbHandset());
        assertTrue(DeviceProfile.current().isPkbDevice());

        DeviceProfile.initialize(null);

        // Back to detection, which on a JVM finds no input devices at all.
        assertFalse(DeviceProfile.current().isPkbDevice());
        assertFalse(DeviceProfile.current().hasPhysicalKeyboard());
    }

    @Test
    public void anInstalledShapeCarriesNoDeviceMapping() {
        DeviceProfile.installForTest(ckbHandset());
        assertFalse(DeviceProfile.current().hasDeviceMapping());
        assertFalse(DeviceProfile.current().hasCustomAltMappings());
        assertFalse(DeviceProfile.current().isForcedCkbDevice());
    }

    // The two force flags differ:
    //   * setForceVkbMode: transient, per window, behind the show-VKB key. isPkbDevice() consults
    //     it, so it changes the device shape.
    //   * setDebugForceVkbMode: the standing "Force touchscreen-only mode" toggle, reported by
    //     DeviceProfile.isForceVkbMode() (read by the IME and switcher) but not by isPkbDevice(),
    //     so the profile stays PKB. That is intended: onShowInputRequested()'s PKB branch shows the
    //     keyboard on an implicit show without show_ime_with_hard_keyboard, and
    //     isOnScreenKeyboardVisible() still honours the toggle via refreshOnScreenKeyboardShowing().

    @Test
    public void transientForceVkbModeTurnsAPkbShapeIntoAVkbOne() {
        DeviceProfile.installForTest(ckbHandset());
        assertTrue(DeviceProfile.current().isPkbDevice());

        DeviceProfile.setForceVkbMode(true);

        assertTrue(DeviceProfile.isForceVkbMode());
        assertFalse(DeviceProfile.current().isPkbDevice());
        assertTrue(DeviceProfile.current().isVkbDevice());
        assertTrue(DeviceProfile.current().supportsSwipeTyping());
        // hasPhysicalKeyboard is hardware, not mode: forcing VKB must not deny the hardware.
        assertTrue(DeviceProfile.current().hasPhysicalKeyboard());
    }

    @Test
    public void transientForceVkbModeIsReversible() {
        DeviceProfile.installForTest(ckbHandset());
        DeviceProfile.setForceVkbMode(true);
        assertFalse(DeviceProfile.current().isPkbDevice());

        DeviceProfile.setForceVkbMode(false);

        assertFalse(DeviceProfile.isForceVkbMode());
        assertTrue(DeviceProfile.current().isPkbDevice());
    }

    @Test
    public void debugForceVkbModeIsReportedButDoesNotChangeTheShape() {
        DeviceProfile.installForTest(ckbHandset());

        DeviceProfile.setDebugForceVkbMode(true);

        assertTrue("the debug toggle is what isForceVkbMode() reports",
                DeviceProfile.isForceVkbMode());
        assertTrue("isPkbDevice() reads only the transient force, not the debug toggle",
                DeviceProfile.current().isPkbDevice());

        DeviceProfile.setDebugForceVkbMode(false);
        assertFalse(DeviceProfile.isForceVkbMode());
    }

    @Test
    public void debugForceVkbModeSurvivesAReinstall() {
        // The debug override lives in DeviceRuntimeState, not the profile, so the Debug toggle takes
        // effect without an IME restart.
        DeviceProfile.setDebugForceVkbMode(true);
        DeviceProfile.installForTest(ckbHandset());
        assertTrue(DeviceProfile.isForceVkbMode());
    }

    @Test
    public void theTwoForceFlagsAreIndependent() {
        // The transient flag is cleared on every hideWindow(); the debug one must not be.
        DeviceProfile.installForTest(ckbHandset());
        DeviceProfile.setDebugForceVkbMode(true);
        DeviceProfile.setForceVkbMode(true);

        DeviceProfile.setForceVkbMode(false);

        assertTrue("clearing the transient force must not clear the debug override",
                DeviceProfile.isForceVkbMode());
        assertTrue("...and the shape follows the transient flag alone",
                DeviceProfile.current().isPkbDevice());
    }

    @Test
    public void onScreenKeyboardIsAlwaysVisibleOnAVkbShape() {
        DeviceProfile.installForTest(touchscreenOnly());
        assertTrue(DeviceProfile.isOnScreenKeyboardVisible());
    }

    @Test
    public void onScreenKeyboardOnAPkbShapeFollowsTheShowingFlag() {
        DeviceProfile.installForTest(ckbHandset());
        assertFalse(DeviceProfile.isOnScreenKeyboardVisible());

        DeviceProfile.setOnScreenKeyboardShowing(true);
        assertTrue(DeviceProfile.isOnScreenKeyboardVisible());

        DeviceProfile.setOnScreenKeyboardShowing(false);
        assertFalse(DeviceProfile.isOnScreenKeyboardVisible());
    }

    @Test
    public void theTransientForceAlsoMakesTheOnScreenKeyboardVisible() {
        // The shape term goes through isVkbDevice(): it follows the transient force, not the debug
        // toggle (which reaches visibility via the showing flag instead).
        DeviceProfile.installForTest(ckbHandset());
        DeviceProfile.setForceVkbMode(true);
        assertTrue(DeviceProfile.isOnScreenKeyboardVisible());

        DeviceProfile.setForceVkbMode(false);
        DeviceProfile.setDebugForceVkbMode(true);
        assertFalse(DeviceProfile.isOnScreenKeyboardVisible());
    }

    @Test
    public void vkbForcedPackagesAreTrackedIndependentlyOfTheShape() {
        DeviceProfile.installForTest(ckbHandset());
        assertFalse(DeviceProfile.isVkbForcedForPackage("com.example.app"));
        assertFalse(DeviceProfile.isVkbForcedForPackage(null));

        DeviceProfile.addVkbForcedPackage("com.example.app");
        assertTrue(DeviceProfile.isVkbForcedForPackage("com.example.app"));
        assertFalse(DeviceProfile.isVkbForcedForPackage("com.example.other"));
        // The per-package list does not itself change the device shape.
        assertTrue(DeviceProfile.current().isPkbDevice());

        DeviceProfile.removeVkbForcedPackage("com.example.app");
        assertFalse(DeviceProfile.isVkbForcedForPackage("com.example.app"));
    }
}
