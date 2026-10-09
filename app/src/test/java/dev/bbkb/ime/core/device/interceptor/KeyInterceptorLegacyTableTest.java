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
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The interceptor's legacy scancode table (249 / 250 / 251 = the Minimal Phone's Sym, Emoji and
 * Mic) yields to a matched config that declares those scancodes. On a Unihertz Titan they are
 * Func1, Func2 and Fn and must not open boards; on the Minimal Phone, whose own config declares
 * them as exactly those board keys, nothing changes — with the unified pipeline on or off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class KeyInterceptorLegacyTableTest {

    private KeyInterceptorService service;
    private final List<KeyInterceptorService.SpecialKeyType> types = new ArrayList<>();
    private final List<ScancodeMapping> mappings = new ArrayList<>();

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
        KeyInterceptorService.setAllKeysCallback(event -> false);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(false);
        KeyInterceptorService.setPreprocessAllKeysEnabled(false);
        ScancodeMappingResolver.getInstance().reset();
    }

    @After
    public void tearDown() {
        KeyInterceptorService.setCallback(null);
        KeyInterceptorService.setAllKeysCallback(null);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(false);
        ScancodeMappingResolver.getInstance().reset();
    }

    private void install(int configRes) {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceInputMapping mapping = DeviceInputMappingParser
                .parseConfigFromXmlResource(context, configRes).mappings.get(0);
        ScancodeMappingResolver.getInstance().initialize(mapping);
    }

    private static KeyEvent key(int action, int keyCode, int scanCode) {
        return new KeyEvent(0L, 0L, action, keyCode, 0, 0, 3, scanCode, 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    /** Press and release one key; returns whether the press was consumed. */
    private boolean press(int keyCode, int scanCode) {
        boolean consumed = service.onKeyEvent(key(KeyEvent.ACTION_DOWN, keyCode, scanCode));
        service.onKeyEvent(key(KeyEvent.ACTION_UP, keyCode, scanCode));
        return consumed;
    }

    // ── the Minimal Phone: unchanged ─────────────────────────────────────────

    private void assertMinimalPhoneBoardKeys(boolean unified) {
        install(R.xml.device_config_minimal);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(unified);

        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 249));
        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 250));
        assertTrue(press(KeyEvent.KEYCODE_UNKNOWN, 251));
        assertEquals(3, types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertEquals(KeyInterceptorService.SpecialKeyType.EMOJI, types.get(1));
        assertEquals(KeyInterceptorService.SpecialKeyType.MIC, types.get(2));
        for (ScancodeMapping m : mappings) {
            if (unified) {
                assertNotNull("the unified pipeline hands the config's mapping over", m);
            } else {
                assertNull("the legacy path hands no mapping over, as before", m);
            }
        }
    }

    @Test
    public void minimalPhone_unifiedOff_boardKeysAsBefore() {
        assertMinimalPhoneBoardKeys(false);
    }

    @Test
    public void minimalPhone_unifiedOn_boardKeysAsBefore() {
        assertMinimalPhoneBoardKeys(true);
    }

    @Test
    public void noConfig_theLegacyTableStillAnswers() {
        assertTrue(press(KeyEvent.KEYCODE_ALT_RIGHT, 249));
        assertTrue(press(KeyEvent.KEYCODE_UNKNOWN, 251));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        assertEquals(KeyInterceptorService.SpecialKeyType.MIC, types.get(1));
    }

    // ── a Titan: the config's roles win ──────────────────────────────────────

    private void assertTitan2FuncAndFnAreNotBoardKeys(boolean unified) {
        install(R.xml.device_config_titan2);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(unified);

        assertFalse("Func1 is not the Sym key", press(KeyEvent.KEYCODE_UNKNOWN, 249));
        assertFalse("Func2 is not the emoji key", press(KeyEvent.KEYCODE_UNKNOWN, 250));
        assertFalse("Fn is not the mic key", press(KeyEvent.KEYCODE_CTRL_LEFT, 251));
        assertTrue(types.isEmpty());

        // Its real Sym key still opens the board.
        assertTrue(press(KeyEvent.KEYCODE_SYM, 253));
        assertEquals(1, types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
    }

    @Test
    public void titan2_unifiedOff_funcAndFnAreOrdinaryKeys() {
        assertTitan2FuncAndFnAreNotBoardKeys(false);
    }

    @Test
    public void titan2_unifiedOn_funcAndFnAreOrdinaryKeys() {
        assertTitan2FuncAndFnAreNotBoardKeys(true);
    }

    @Test
    public void titan2019_redKeyIsNotABoardKey() {
        install(R.xml.device_config_titan);
        assertFalse(press(KeyEvent.KEYCODE_UNKNOWN, 249));
        assertFalse(press(KeyEvent.KEYCODE_UNKNOWN, 250));
        assertTrue(types.isEmpty());
    }

    @Test
    public void key2_untouched_aConfigWithoutTheLegacyScancodesKeepsTheTable() {
        // athena declares only keycode 7 and the 119/110 key; the table's answers stand.
        install(R.xml.device_config_athena);
        assertTrue(press(KeyEvent.KEYCODE_SYM, 0));
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, types.get(0));
        // The KEY2 mic key (keycode 7) is not consumed here with the unified pipeline off.
        assertFalse(press(KeyEvent.KEYCODE_0, 11));
    }
}
