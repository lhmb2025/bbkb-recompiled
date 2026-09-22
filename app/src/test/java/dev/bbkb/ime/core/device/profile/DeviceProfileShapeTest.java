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
 * W3-D: {@link DeviceProfile#current()} is a static singleton fed by a hardware scan, and on a JVM
 * that scan always answers "VKB, no physical keyboard". Every question the app asks about the
 * device shape therefore had exactly one testable answer, which is why the physical-keyboard
 * rendering of the settings screens is untested.
 *
 * <p>{@link DeviceProfile#installForTest(DeviceCapabilities)} is the seam. This pins what it does,
 * and — more importantly — pins the two force-VKB levers behaving the same way over an installed
 * shape as they do over a detected one. {@code debug_force_vkb_mode} and
 * {@code DeviceProfile.setForceVkbMode} are how this project verifies the on-screen keyboard on a
 * Mac emulator; before this they had no unit coverage at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class DeviceProfileShapeTest {

    /** A KEY2-like handset: physical keyboard with a capacitive overlay over it. */
    private static DeviceCapabilities ckbHandset() {
        return DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
                true, true, true, "qwerty", "4row");
    }

    /** A plain touchscreen handset. */
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

    // ── the seam ─────────────────────────────────────────────────────────────

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

    // ── the force-VKB levers, over an installed physical-keyboard shape ───────
    //
    // These two flags are NOT interchangeable, and the difference is easy to lose:
    //   * setForceVkbMode      — the transient, per-window force behind the show-VKB key. This is
    //                            the one isPkbDevice() consults, so it changes the device shape.
    //   * setDebugForceVkbMode — the standing "Force touchscreen-only mode" debug toggle. It is
    //                            reported by the static DeviceProfile.isForceVkbMode(), which is
    //                            what the IME and the keyboard switcher read, but isPkbDevice()
    //                            does NOT look at it, so the profile still reports PKB.
    // Intentional (FIX-C): the debug toggle shows the on-screen keyboard over PKB hardware, and
    // the emulator/KEY2 recipe needs the device to stay PKB. onShowInputRequested()'s PKB branch
    // is what shows the keyboard on an implicit show (e.g. SENDTO auto-focus) without Android's
    // show_ime_with_hard_keyboard. isOnScreenKeyboardVisible() still honours the toggle, through
    // refreshOnScreenKeyboardShowing()'s isForceVkbMode() term. The original APK kept device
    // identity (ad.a()) free of every force flag.

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
        // The standing debug override lives in DeviceRuntimeState, not in the profile, which is
        // why flipping it in Debug settings takes effect without an IME restart.
        DeviceProfile.setDebugForceVkbMode(true);
        DeviceProfile.installForTest(ckbHandset());
        assertTrue(DeviceProfile.isForceVkbMode());
    }

    @Test
    public void theTwoForceFlagsAreIndependent() {
        // The transient flag is cleared on every hideWindow(); the debug one must not be, or the
        // Debug toggle would only hold for one window after a process restart.
        DeviceProfile.installForTest(ckbHandset());
        DeviceProfile.setDebugForceVkbMode(true);
        DeviceProfile.setForceVkbMode(true);

        DeviceProfile.setForceVkbMode(false);

        assertTrue("clearing the transient force must not clear the debug override",
                DeviceProfile.isForceVkbMode());
        assertTrue("...and the shape follows the transient flag alone",
                DeviceProfile.current().isPkbDevice());
    }

    // ── on-screen-keyboard visibility, which reads the shape ─────────────────

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
        // isOnScreenKeyboardVisible()'s shape term goes through isVkbDevice(), so it follows the
        // transient force and is unmoved by the debug toggle. On a device the toggle still
        // reaches it via the showing flag (refreshOnScreenKeyboardShowing ORs isForceVkbMode()).
        DeviceProfile.installForTest(ckbHandset());
        DeviceProfile.setForceVkbMode(true);
        assertTrue(DeviceProfile.isOnScreenKeyboardVisible());

        DeviceProfile.setForceVkbMode(false);
        DeviceProfile.setDebugForceVkbMode(true);
        assertFalse(DeviceProfile.isOnScreenKeyboardVisible());
    }

    // ── per-package force list ───────────────────────────────────────────────

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
