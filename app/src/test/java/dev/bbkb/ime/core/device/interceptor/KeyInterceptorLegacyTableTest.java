package dev.bbkb.ime.core.device.interceptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Which keys the helper treats as Sym, Emoji and Mic: the device profile's role for a key is the
 * answer, and the built-in scancode table (249 / 250 / 251 = the Minimal Phone's Sym, Emoji and
 * Mic) answers only for a key the profile does not name.
 *
 * <p>On a Unihertz Titan the table's scancodes are Func1, Func2 and Fn and must not open boards.
 * On the Minimal Phone, whose profile names them as exactly those board keys, the result is what
 * the table alone would give. A profile's own board keys are caught whatever their scancode.
 *
 * <p>The profile used to be asked first only behind a "Unified key mapping" switch, off by
 * default. These tests cover what that switch's two positions covered between them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class KeyInterceptorLegacyTableTest {

    private KeyInterceptorService service;
    private final List<KeyInterceptorService.SpecialKeyType> types = new ArrayList<>();
    private final List<ScancodeMapping> mappings = new ArrayList<>();
    private final List<KeyEvent> altEvents = new ArrayList<>();

    private final KeyInterceptorService.KeyEventCallback callback =
            new KeyInterceptorService.KeyEventCallback() {
                @Override
                public boolean onSpecialKeyPressed(KeyInterceptorService.SpecialKeyType keyType,
                        int metaState) {
                    types.add(keyType);
                    mappings.add(null);
                    return true;
                }

                @Override
                public boolean onSpecialKeyPressed(KeyInterceptorService.SpecialKeyType keyType,
                        int metaState, ScancodeMapping mapping) {
                    types.add(keyType);
                    mappings.add(mapping);
                    return true;
                }
            };

    @Before
    public void setUp() {
        service = new KeyInterceptorService();
        KeyInterceptorService.setCallback(callback);
        KeyInterceptorService.setAllKeysCallback(event -> {
            altEvents.add(event);
            return false;
        });
        KeyInterceptorService.setPreprocessAllKeysEnabled(false);
        DeviceInputResolver.resetConfig();
    }

    @After
    public void tearDown() {
        KeyInterceptorService.setCallback(null);
        KeyInterceptorService.setAllKeysCallback(null);
        DeviceInputResolver.resetConfig();
    }

    private void install(int configRes) {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceInputMapping mapping = DeviceInputMappingParser
                .parseConfigFromXmlResource(context, configRes).mappings.get(0);
        DeviceInputResolver.installPhoneProfileForTest(mapping);
    }

    private static KeyEvent key(int action, int keyCode, int scanCode, int deviceId) {
        return new KeyEvent(0L, 0L, action, keyCode, 0, 0, deviceId, scanCode, 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    /** Press and release one key; returns whether the press was consumed. */
    private boolean press(int keyCode, int scanCode) {
        boolean consumed = service.onKeyEvent(key(KeyEvent.ACTION_DOWN, keyCode, scanCode, 3));
        service.onKeyEvent(key(KeyEvent.ACTION_UP, keyCode, scanCode, 3));
        return consumed;
    }

    // ── the Minimal Phone ────────────────────────────────────────────────────

    @Test
    public void minimalPhone_boardKeysAreCaughtWithTheirProfileEntries() {
        install(R.xml.device_config_minimal);

        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 249));
        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 250));
        assertTrue(press(KeyEvent.KEYCODE_UNKNOWN, 251));
        assertEquals(3, types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertEquals(KeyInterceptorService.SpecialKeyType.EMOJI, types.get(1));
        assertEquals(KeyInterceptorService.SpecialKeyType.MIC, types.get(2));
        for (ScancodeMapping m : mappings) {
            assertNotNull("the profile's entry is handed over", m);
        }
        // The entries carry what the built-in defaults and the Alt table would have given.
        assertEquals(-11, mappings.get(1).boardId);
        assertEquals('0', mappings.get(1).altChar);
        assertEquals(-27, mappings.get(2).boardId);
        assertEquals('.', mappings.get(2).altChar);
        assertTrue("a board key is never taken for Alt", altEvents.isEmpty());
    }

    @Test
    public void minimalPhone_eventsTheProfileDoesNotNameStillReachTheBuiltInTable() {
        // The profile's fallback entries need scancode AND key code to be 666 / 667. The ROM
        // can attach another key code to those scancodes; the table needs only the scancode.
        install(R.xml.device_config_minimal);

        assertTrue(press(KeyEvent.KEYCODE_UNKNOWN, 666));
        assertTrue(press(KeyEvent.KEYCODE_VOICE_ASSIST, 667));
        assertEquals(KeyInterceptorService.SpecialKeyType.EMOJI, types.get(0));
        assertEquals(KeyInterceptorService.SpecialKeyType.MIC, types.get(1));
        assertNull("no profile entry matched, so none is handed over", mappings.get(0));
        assertNull(mappings.get(1));
    }

    // ── no profile ───────────────────────────────────────────────────────────

    @Test
    public void noConfig_theBuiltInTableAnswers() {
        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 249));
        assertTrue(press(KeyEvent.KEYCODE_UNKNOWN, 250));
        assertTrue(press(KeyEvent.KEYCODE_UNKNOWN, 251));
        assertTrue(press(KeyEvent.KEYCODE_SYM, 0));
        assertTrue(press(KeyEvent.KEYCODE_F5, 0));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertEquals(KeyInterceptorService.SpecialKeyType.EMOJI, types.get(1));
        assertEquals(KeyInterceptorService.SpecialKeyType.MIC, types.get(2));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(3));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(4));
        for (ScancodeMapping m : mappings) {
            assertNull(m);
        }
    }

    @Test
    public void noConfig_anAltEventFromNoDeviceIsTheSymKey() {
        // The table's last rule: an Alt-left event with no device and no scancode. A profile
        // has no way to name such an event.
        assertTrue(service.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, -1)));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertTrue(altEvents.isEmpty());
    }

    @Test
    public void noConfig_ordinaryKeysAreLeftAlone() {
        assertFalse(press(KeyEvent.KEYCODE_0, 11));
        assertFalse(press(KeyEvent.KEYCODE_A, 30));
        assertTrue(types.isEmpty());
    }

    // ── the Titans: the profile's roles win over the table ───────────────────

    @Test
    public void titan2_funcAndFnAreOrdinaryKeys() {
        install(R.xml.device_config_titan2);

        assertFalse("Func1 is not the Sym key", press(KeyEvent.KEYCODE_UNKNOWN, 249));
        assertFalse("Func2 is not the emoji key", press(KeyEvent.KEYCODE_UNKNOWN, 250));
        assertFalse("Fn is not the mic key", press(KeyEvent.KEYCODE_CTRL_LEFT, 251));
        assertFalse("the firmware's swipe key is not a board key", press(322, 0));
        assertTrue(types.isEmpty());

        // Its real Sym key still opens the board.
        assertTrue(press(KeyEvent.KEYCODE_SYM, 253));
        assertEquals(1, types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
    }

    @Test
    public void titan2019_redKeyIsNotABoardKey() {
        install(R.xml.device_config_titan);
        assertFalse(press(KeyEvent.KEYCODE_UNKNOWN, 249));
        assertFalse(press(KeyEvent.KEYCODE_UNKNOWN, 250));
        assertTrue(types.isEmpty());
    }

    // ── a profile's board keys the table does not know ───────────────────────

    @Test
    public void titanPocket_symIsCaughtByItsScancodeWhateverKeyCodeTheRomAttaches() {
        // Scancode 127 is KEY_COMPOSE, which Android's generic key layout calls MENU. The table
        // knows neither; the profile names the scancode.
        install(R.xml.device_config_titan_pocket);

        assertTrue(press(KeyEvent.KEYCODE_MENU, 127));
        assertTrue(press(KeyEvent.KEYCODE_SYM, 127));
        assertEquals(2, types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(1));

        assertFalse("its F13 key is an ordinary key", press(KeyEvent.KEYCODE_F13, 183));
        assertFalse(press(KeyEvent.KEYCODE_UNKNOWN, 249));
    }

    @Test
    public void q25_symIsCaughtAsSymAndNotTakenForAlt() {
        // The Q25's Sym key arrives with Android's right-Alt key code. It used to go through the
        // Alt handling unless the "Unified key mapping" switch was on.
        install(R.xml.device_config_q25);

        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 100));
        assertEquals(1, types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertTrue("a Sym press must not reach the Alt tracking", altEvents.isEmpty());

        // Its real Alt key is still Alt.
        assertTrue(press(KeyEvent.KEYCODE_ALT_LEFT, 56));
        assertEquals(1, types.size());
        assertEquals("down and up are both forwarded", 2, altEvents.size());
    }

    @Test
    public void q25_micKeyIsCaughtWithItsDigit() {
        // The 0 / mic key. Its entry's Alt character is what HardwareKeyBridge types in a number
        // field instead of starting voice input (micKeyTypesDigit).
        install(R.xml.device_config_q25);

        assertTrue(press(KeyEvent.KEYCODE_0, 11));
        assertEquals(KeyInterceptorService.SpecialKeyType.MIC, types.get(0));
        assertNotNull(mappings.get(0));
        assertEquals('0', mappings.get(0).altChar);
        assertEquals(-27, mappings.get(0).boardId);
    }

    // ── the KEY2 profile (the helper is switched off on a real KEY2) ──────────

    @Test
    public void key2Profile_theConvenienceKeyIsNeverCaught() {
        // MULTIFUNCTION: its action may be a held Ctrl, so it must reach the ordinary key path.
        install(R.xml.device_config_athena);

        assertFalse(press(KeyEvent.KEYCODE_FUNCTION, 110));
        assertTrue(types.isEmpty());
        assertTrue(altEvents.isEmpty());
    }

    @Test
    public void key2Profile_keysItDoesNotNameKeepTheTablesAnswer() {
        // athena names only key code 7 and the 119/110 key.
        install(R.xml.device_config_athena);

        assertTrue(press(KeyEvent.KEYCODE_SYM, 0));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertNull(mappings.get(0));
    }
}
