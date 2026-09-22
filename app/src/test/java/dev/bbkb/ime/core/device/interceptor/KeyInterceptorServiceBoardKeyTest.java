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

import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Beta triage #10, Minimal Phone MP01: "Sym key does nothing".
 *
 * <p>The MP01's ROM maps the Sym key's scancode 249 to {@code KEY_RIGHTALT}, so a Sym press
 * reaches {@link KeyInterceptorService#onKeyEvent} as {@code scanCode=249 +
 * KEYCODE_ALT_RIGHT}. The service used to run its Alt-keycode block first and consume that
 * event as a sticky Alt, so the scancode was never looked at and the symbol board never opened.
 *
 * <p>The invariant these tests pin is the ordering: <em>what a key is</em> comes from the device
 * config (or, absent one, the legacy scancode table), not from whichever keycode the ROM's key
 * layout attached to it. The negative half matters as much as the positive half — a Sym press
 * must not reach the all-keys callback, because that is the path that feeds
 * {@code PhysicalKeyboardStateTracker} and latches Alt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class KeyInterceptorServiceBoardKeyTest {

    /** The MP01 Sym key's raw hardware scancode. */
    private static final int SCANCODE_SYM = 249;
    /** A scancode with no meaning to either the MP01 config or the legacy table. */
    private static final int SCANCODE_PLAIN_ALT = 100;

    private KeyInterceptorService service;
    private RecordingSpecialKeyCallback specialKeys;
    private RecordingAllKeysCallback allKeys;

    // ── callbacks ────────────────────────────────────────────────────────────

    private static final class RecordingSpecialKeyCallback
            implements KeyInterceptorService.KeyEventCallback {
        final List<KeyInterceptorService.SpecialKeyType> types = new ArrayList<>();
        final List<ScancodeMapping> mappings = new ArrayList<>();
        /** What the IME claims to have done with the key. */
        boolean handled = true;

        @Override
        public boolean onSpecialKeyPressed(KeyInterceptorService.SpecialKeyType keyType, int metaState) {
            types.add(keyType);
            mappings.add(null);
            return handled;
        }

        @Override
        public boolean onSpecialKeyPressed(KeyInterceptorService.SpecialKeyType keyType, int metaState,
                ScancodeMapping mapping) {
            types.add(keyType);
            mappings.add(mapping);
            return handled;
        }
    }

    private static final class RecordingAllKeysCallback
            implements KeyInterceptorService.AllKeysCallback {
        final List<KeyEvent> events = new ArrayList<>();

        @Override
        public boolean onKeyEvent(KeyEvent event) {
            events.add(event);
            return false;
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    @Before
    public void setUp() {
        service = new KeyInterceptorService();
        specialKeys = new RecordingSpecialKeyCallback();
        allKeys = new RecordingAllKeysCallback();
        KeyInterceptorService.setCallback(specialKeys);
        KeyInterceptorService.setAllKeysCallback(allKeys);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(false);
        KeyInterceptorService.setPreprocessAllKeysEnabled(false);
        ScancodeMappingResolver.getInstance().reset();
    }

    @After
    public void tearDown() {
        KeyInterceptorService.setCallback(null);
        KeyInterceptorService.setAllKeysCallback(null);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(false);
        KeyInterceptorService.setPreprocessAllKeysEnabled(false);
        ScancodeMappingResolver.getInstance().reset();
    }

    /** Load the shipped MP01 config and arm the resolver with it, as the IME does on that device. */
    private void installMinimalPhoneConfig() {
        Context context = ApplicationProvider.getApplicationContext();
        DeviceInputConfig config =
                DeviceInputMappingParser.parseConfigFromXmlResource(context, R.xml.device_config_minimal);
        assertNotNull("device_config_minimal.xml failed to parse", config);
        DeviceInputMapping mapping = config.findMappingForDevice("aw9523b-key");
        assertNotNull("device_config_minimal.xml no longer matches the device name aw9523b-key",
                mapping);
        ScancodeMappingResolver.getInstance().initialize(mapping);
        KeyInterceptorService.setUnifiedKeyMappingEnabled(true);
    }

    /** A Sym press exactly as the MP01 ROM delivers it. */
    private static KeyEvent symEvent(int action) {
        return rawEvent(action, KeyEvent.KEYCODE_ALT_RIGHT, SCANCODE_SYM);
    }

    private static KeyEvent rawEvent(int action, int keyCode, int scanCode) {
        return new KeyEvent(0L, 0L, action, keyCode, /* repeat */ 0, /* metaState */ 0,
                /* deviceId */ 3, scanCode, /* flags */ 0, InputDevice.SOURCE_KEYBOARD);
    }

    // ── the MP01 Sym key, via the device config ──────────────────────────────

    @Test
    public void symScancodeFromTheDeviceConfigBeatsItsAltKeycode() {
        installMinimalPhoneConfig();

        assertTrue("the Sym press should be consumed by the service",
                service.onKeyEvent(symEvent(KeyEvent.ACTION_DOWN)));

        assertEquals(1, specialKeys.types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, specialKeys.types.get(0));
        // The config's mapping is handed to the callback, so board id / alt char stay data.
        assertNotNull("the resolved mapping should reach the callback", specialKeys.mappings.get(0));
        assertEquals(SCANCODE_SYM, specialKeys.mappings.get(0).rawScanCode);

        assertTrue("a Sym press must never reach the all-keys callback: that is the path that"
                        + " feeds the modifier tracker and latches Alt",
                allKeys.events.isEmpty());
    }

    @Test
    public void theSymReleaseIsConsumedTooSoTheSystemNeverSeesAStrayAltUp() {
        installMinimalPhoneConfig();

        assertTrue(service.onKeyEvent(symEvent(KeyEvent.ACTION_DOWN)));
        assertTrue("the release of a consumed board key must be consumed as well",
                service.onKeyEvent(symEvent(KeyEvent.ACTION_UP)));
        assertTrue(allKeys.events.isEmpty());

        // …and only that one: a second, unpaired release is not swallowed.
        assertFalse(service.onKeyEvent(symEvent(KeyEvent.ACTION_UP)));
    }

    // ── the same key with the unified pipeline off (the shipped default) ─────

    @Test
    public void symScancodeStillBeatsItsAltKeycodeWithoutTheUnifiedPipeline() {
        // pref_use_unified_key_mapping defaults to false, so this is the shipped configuration.
        // The legacy hardcoded scancode table knows 249 just as well, and it too must be
        // consulted before the Alt block.
        assertTrue(service.onKeyEvent(symEvent(KeyEvent.ACTION_DOWN)));

        assertEquals(1, specialKeys.types.size());
        assertEquals(KeyInterceptorService.SpecialKeyType.SYM, specialKeys.types.get(0));
        assertNull("no device config is armed, so there is no mapping to pass",
                specialKeys.mappings.get(0));
        assertTrue(allKeys.events.isEmpty());
    }

    // ── the callback's answer is honoured ────────────────────────────────────

    @Test
    public void aDeclinedSymPressIsNotConsumed() {
        installMinimalPhoneConfig();
        specialKeys.handled = false;

        assertFalse("if the IME did not handle the key, the system should still get it",
                service.onKeyEvent(symEvent(KeyEvent.ACTION_DOWN)));
        // Not consumed on the way down, so the release is not swallowed either.
        assertFalse(service.onKeyEvent(symEvent(KeyEvent.ACTION_UP)));
    }

    @Test
    public void withSpecialKeySupportOffTheSymKeyFallsThroughToTheSystem() {
        installMinimalPhoneConfig();
        KeyInterceptorService.setCallback(null);

        // It used to be consumed here and dropped on the floor — the key was dead rather than
        // merely un-enhanced. Now it reaches the system, and from there the IME's ordinary
        // onKeyDown pipeline, which has its own SYM handling.
        assertFalse(service.onKeyEvent(symEvent(KeyEvent.ACTION_DOWN)));
        assertTrue("and it still must not be mistaken for an Alt press", allKeys.events.isEmpty());
    }

    // ── an ordinary Alt key is untouched ─────────────────────────────────────

    @Test
    public void aRealAltKeyStillGoesThroughTheAltBleedThroughBlock() {
        installMinimalPhoneConfig();

        KeyEvent alt = rawEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, SCANCODE_PLAIN_ALT);
        assertTrue("real hardware Alt is still consumed so the system does not track it",
                service.onKeyEvent(alt));

        assertEquals("…and forwarded so the IME's tracker stays in sync", 1, allKeys.events.size());
        assertTrue("a real Alt is not a board key", specialKeys.types.isEmpty());
    }
}
