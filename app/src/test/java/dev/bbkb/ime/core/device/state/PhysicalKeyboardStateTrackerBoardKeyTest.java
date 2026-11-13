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
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
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
 * Pins that a board key is never a modifier, whatever keycode the ROM gave it. The MP01 maps its
 * Sym scancode 249 to {@code KEY_RIGHTALT}; the scancode identifies the key, so Sym must not latch Alt.
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
        DeviceInputResolver.installPhoneProfileForTest(mapping);

        KeyEventDeviceClassifier.getInstance().clearCache();
        tracker = new PhysicalKeyboardStateTracker(null);
    }

    @After
    public void tearDown() {
        DeviceInputResolver.resetConfig();
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
    }

    /** deviceId 0 on a profile with a physical keyboard is a physical event to the classifier. */
    private static KeyEvent event(int action, int keyCode, int scanCode) {
        return new KeyEvent(0L, 0L, action, keyCode, /* repeat */ 0, /* metaState */ 0,
                /* deviceId */ 0, scanCode, /* flags */ 0, InputDevice.SOURCE_KEYBOARD);
    }

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
        // A device config without <scancode-mappings> keeps keycode-based classification.
        DeviceInputResolver.resetConfig();
        KeyEvent sym = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM);

        assertFalse(PhysicalKeyboardStateTracker.isBoardRoleEvent(sym));
        assertTrue(PhysicalKeyboardStateTracker.isModifierKeyEvent(
                KeyEvent.KEYCODE_ALT_RIGHT, sym));
    }

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
        // Only modifier treatment is skipped; the keys-down count (read by FCC and the board
        // coordinator) must still see the key.
        assertEquals(0, tracker.getNumberOfKeysDown());

        tracker.handleKeyDown(KeyEvent.KEYCODE_ALT_RIGHT,
                event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));
        assertEquals(1, tracker.getNumberOfKeysDown());

        tracker.handleKeyUp(KeyEvent.KEYCODE_ALT_RIGHT,
                event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));
        assertEquals(0, tracker.getNumberOfKeysDown());
    }

    // The positive control is anOrdinaryAltKeyIsStillAModifierEvent, not a handleKeyDown() round
    // trip: the modifier arm's long-press timer would need a full SettingsManager.
}
