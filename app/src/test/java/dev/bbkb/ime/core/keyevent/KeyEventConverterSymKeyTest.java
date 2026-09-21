package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.InputType;
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
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport;
import dev.bbkb.ime.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The second half of "Sym key does nothing" on the Minimal Phone MP01 (beta triage #10).
 *
 * <p>{@link KeyEventConverter} had two places where a SYM press on a third-party PKB device was
 * turned into {@code InputEvent.createEmptyEvent()} — the {@code KEYCODE_SYM} site and the
 * {@code BOARD_SYM} site — both guarded by {@code DeviceProfile.usesMetaSymHandling()}. The
 * theory was that {@code SymKeyStateTracker} would pick the key up via {@code META_SYM_ON}; that
 * tracker had zero callers and was deleted in 2026-09, leaving the key swallowed and handled by
 * nobody. A KNOWN GAP comment recorded the situation and deferred the fix to this work.
 *
 * <p>This is the path that matters most in practice, because it needs no accessibility service:
 * it is what happens on an MP01 straight out of the box, where {@code
 * pref_key_interceptor_enabled} defaults to false.
 *
 * <p>{@code -22} is the project's functional key code for the symbol-board toggle: it reaches
 * {@code InputLogic.handleFunctionalKeyEvent} → {@code BlackBerryIME.updateSymbolShift} →
 * {@code KeyboardSwitcher.onSymbolShiftToggle}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class KeyEventConverterSymKeyTest {

    /** The project's functional key code for "toggle the symbol board". */
    private static final int CODE_SYMBOL_TOGGLE = -22;
    /** The MP01 Sym key's raw hardware scancode. */
    private static final int SCANCODE_SYM = 249;

    private KeyEventConverter converter;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();

        // A Minimal-Phone-shaped device: PKB, physical keyboard, not BlackBerry hardware.
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
        DeviceProfileTestSupport.installMapping(mapping);
        ScancodeMappingResolver.getInstance().initialize(mapping);

        // Guards the premise of every test below: this used to be the flag that swallowed SYM.
        assertTrue("an MP01-shaped profile must still be a usesMetaSymHandling() device,"
                        + " or these tests are asserting nothing",
                DeviceProfile.current().usesMetaSymHandling());

        KeyEventDeviceClassifier.getInstance().clearCache();

        converter = new KeyEventConverter(
                /* deviceId */ 0,
                // No special interpretation: fall through to the key-code handling under test.
                (keyEvent, meta) -> null,
                context,
                new AuxCharacterResolver.Builder().build());
    }

    @After
    public void tearDown() {
        ScancodeMappingResolver.getInstance().reset();
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
    }

    /**
     * deviceId 0 with a profile that has a physical keyboard is what
     * {@code KeyEventDeviceClassifier} calls a physical event (its "legacy built-in keyboard"
     * rule), which the SYM branches require.
     */
    private static KeyEvent physicalDown(int keyCode, int scanCode) {
        return new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, /* repeat */ 0,
                /* metaState */ 0, /* deviceId */ 0, scanCode, /* flags */ 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    private InputEvent convert(KeyEvent event) {
        return converter.convertKeyEvent(event, /* computed meta */ 0, InputType.TYPE_CLASS_TEXT);
    }

    @Test
    public void aRealSymKeycodeIsNoLongerSwallowedIntoAnEmptyEvent() {
        InputEvent result = convert(physicalDown(KeyEvent.KEYCODE_SYM, /* scanCode */ 0));

        assertTrue("SYM produced an empty event — the KNOWN GAP is back", result.hasData());
        assertTrue(result.isFunctionalKeyEvent());
        assertEquals(CODE_SYMBOL_TOGGLE, result.mKeyCode);
    }

    @Test
    public void theMp01SymScancodeReachesTheSymbolToggleDespiteItsAltKeycode() {
        // scanCode 249 + KEYCODE_ALT_RIGHT: what the MP01 ROM sends for Sym. The config's
        // <key rawScanCode="249" role="BOARD_SYM"/> leaves rawKeyCode unset, i.e. "don't care",
        // so it matches whatever keycode the .kl file attached.
        InputEvent result = convert(physicalDown(KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM));

        assertTrue("the MP01 Sym key produced an empty event", result.hasData());
        assertTrue(result.isFunctionalKeyEvent());
        assertEquals(CODE_SYMBOL_TOGGLE, result.mKeyCode);
        assertTrue("…and it must not be reported as a modifier press", !result.isModifierKey());
    }

    @Test
    public void aHeldSymIsMarkedAsARepeatSoTheBoardDoesNotFlap() {
        // updateSymbolShift() ignores key repeats; that only works if the flag survives here.
        KeyEvent repeat = new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM,
                /* repeat */ 1, /* metaState */ 0, /* deviceId */ 0, /* scanCode */ 0,
                /* flags */ 0, InputDevice.SOURCE_KEYBOARD);

        InputEvent result = convert(repeat);

        assertEquals(CODE_SYMBOL_TOGGLE, result.mKeyCode);
        assertTrue(result.isKeyRepeat());
    }
}
