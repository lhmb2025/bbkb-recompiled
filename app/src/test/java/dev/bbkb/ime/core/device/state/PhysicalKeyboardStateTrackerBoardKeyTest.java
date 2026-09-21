package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A board key is never a modifier, whatever keycode the ROM gave it.
 *
 * <p>The Minimal Phone MP01 maps its Sym key's scancode 249 to {@code KEY_RIGHTALT}, so every Sym
 * press arrived at {@link PhysicalKeyboardStateTracker#handleKeyDown} as a
 * {@code KEYCODE_ALT_RIGHT} down and latched sticky Alt. That is the other half of "Sym key does
 * nothing" (beta triage #10): the key not only failed to open the symbol board, it silently armed
 * Alt for the next keystroke. The scancode identifies the key; the keycode is only what the
 * device's key layout file says about it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PhysicalKeyboardStateTrackerBoardKeyTest {

    private static final int SCANCODE_SYM = 249;
    private static final int SCANCODE_PLAIN_ALT = 100;

    private PhysicalKeyboardStateTracker tracker;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();

        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ false,
                /* isBlackBerryDevice */ false, "qwerty", "4row"));

        DeviceInputConfig config =
                DeviceInputMappingParser.parseConfigFromXmlResource(context, R.xml.device_config_minimal);
        assertNotNull("device_config_minimal.xml failed to parse", config);
        DeviceInputMapping mapping = config.findMappingForDevice("aw9523b-key");
        assertNotNull("device_config_minimal.xml no longer matches the device name aw9523b-key",
                mapping);
        ScancodeMappingResolver.getInstance().initialize(mapping);

        KeyEventDeviceClassifier.getInstance().clearCache();
        tracker = new PhysicalKeyboardStateTracker(null);
    }

    @After
    public void tearDown() {
        ScancodeMappingResolver.getInstance().reset();
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
    }

    /** deviceId 0 on a profile with a physical keyboard is a physical event to the classifier. */
    private static KeyEvent event(int action, int keyCode, int scanCode) {
        return new KeyEvent(0L, 0L, action, keyCode, /* repeat */ 0, /* metaState */ 0,
                /* deviceId */ 0, scanCode, /* flags */ 0, InputDevice.SOURCE_KEYBOARD);
    }

    // ── the classification itself ────────────────────────────────────────────

    @Test
    public void anAltKeycodeCarryingABoardScancodeIsNotAModifierEvent() {
        KeyEvent sym = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM);

        assertTrue("the bare keycode test still says 'modifier' — that is the trap",
                PhysicalKeyboardStateTracker.isModifierKey(KeyEvent.KEYCODE_ALT_RIGHT));
        assertTrue(PhysicalKeyboardStateTracker.isBoardRoleEvent(sym));
        assertFalse(PhysicalKeyboardStateTracker.isModifierKeyEvent(
                KeyEvent.KEYCODE_ALT_RIGHT, sym));
    }

    @Test
    public void anOrdinaryAltKeyIsStillAModifierEvent() {
        KeyEvent alt = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, SCANCODE_PLAIN_ALT);

        assertFalse(PhysicalKeyboardStateTracker.isBoardRoleEvent(alt));
        assertTrue(PhysicalKeyboardStateTracker.isModifierKeyEvent(
                KeyEvent.KEYCODE_ALT_LEFT, alt));
    }

    @Test
    public void withNoDeviceConfigArmedNothingChanges() {
        // A device whose config has no <scancode-mappings> must behave exactly as before.
        ScancodeMappingResolver.getInstance().reset();
        KeyEvent sym = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM);

        assertFalse(PhysicalKeyboardStateTracker.isBoardRoleEvent(sym));
        assertTrue(PhysicalKeyboardStateTracker.isModifierKeyEvent(
                KeyEvent.KEYCODE_ALT_RIGHT, sym));
    }

    // ── and what the tracker does with it ────────────────────────────────────

    @Test
    public void pressingSymDoesNotLatchAlt() {
        tracker.handleKeyDown(KeyEvent.KEYCODE_ALT_RIGHT,
                event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));

        assertFalse("the MP01 Sym key armed Alt instead of opening the symbol board",
                tracker.isAltPressed());

        tracker.handleKeyUp(KeyEvent.KEYCODE_ALT_RIGHT,
                event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));

        assertFalse("…and it must not leave Alt sticky after release either",
                tracker.isAltPressed());
        assertEquals("no Alt bit should be set in the internal meta state",
                0, tracker.getInternalMetaState() & KeyEvent.META_ALT_MASK);
    }

    @Test
    public void theSymPressIsStillCountedAsAKeyDown() {
        // The exemption is from *modifier* treatment only. Everything else the tracker does for
        // a key — the keys-down count the FCC and board coordinators read — must still happen,
        // or the key becomes invisible rather than merely non-modifying.
        assertEquals(0, tracker.getNumberOfKeysDown());

        tracker.handleKeyDown(KeyEvent.KEYCODE_ALT_RIGHT,
                event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));
        assertEquals(1, tracker.getNumberOfKeysDown());

        tracker.handleKeyUp(KeyEvent.KEYCODE_ALT_RIGHT,
                event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));
        assertEquals(0, tracker.getNumberOfKeysDown());
    }

    // The positive control — "a real Alt is still tracked as a modifier" — is
    // {@link #anOrdinaryAltKeyIsStillAModifierEvent} rather than a handleKeyDown() round trip.
    // The modifier arm delegates to MetaKeyKeyListener and then arms the long-press timer, which
    // reads SettingsManager.getSettingsValues(); standing a whole SettingsManager up here would
    // test the timer, not this change. The decision is what moved, so the decision is what is
    // pinned, and pressingSymDoesNotLatchAlt proves the modifier arm is not entered at all.
}
