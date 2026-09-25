package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
import dev.bbkb.ime.core.ime.HardwareKeyBridge;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * "Alt+Sym shortcut does nothing" — owner report, BlackBerry KEY2, 2026-09-21, with
 * {@code pref_alt_sym_shortcut_action = emoji_picker} stored on the device. Sym alone toggled the
 * symbol board; Alt+Sym did not open the emoji board.
 *
 * <p>Three independent reasons the chord was dropped, one per Sym entry point:
 *
 * <ol>
 *   <li><b>The ordinary hardware key path never asked.</b>
 *       {@link KeyEventProcessor#onKeyDownInternal} routed the KEY2's real {@code KEYCODE_SYM}
 *       straight to {@code KeyEventConverter}, which turns it into the symbol-board toggle
 *       ({@code -22}) with no Alt+Sym check anywhere. The two call sites that did have one both
 *       require the accessibility service, and one of them additionally requires
 *       {@code pref_key_interceptor_enabled}, which defaults to false — so on a stock KEY2 the
 *       chord was consulted by nobody.</li>
 *   <li><b>A held Alt was invisible to the state-only path.</b>
 *       {@link KeyEventProcessor#processKeyEventForState} passed only
 *       {@code tracker.getInternalMetaState()} and never the event's own meta state, and it read
 *       that state <em>after</em> {@code tracker.handleKeyDown()} — which resets the internal Alt
 *       span for key code 63 on every profile whose {@code usesMetaSymHandling()} is false. So
 *       held Alt was never seen, and sticky Alt was wiped a line before it was read.</li>
 *   <li><b>The accessibility path dispatched into a closed window.</b> Covered by
 *       {@link AltSymShortcutBoardGateTest}.</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class AltSymShortcutChordTest {

    private Context context;
    private BlackBerryIME ime;
    private KeyboardSwitcher keyboardSwitcher;
    private PhysicalKeyboardStateTracker tracker;
    private AltSymShortcutHandler handler;
    private KeyEventProcessor processor;
    private RecordingActions actions;

    /** Records which configured action the chord dispatched, if any. */
    private static final class RecordingActions implements AltSymShortcutHandler.ActionCallback {
        int symbolKeyboard;
        int clipboard;
        int fcc;
        int numberPad;
        int languageSwitch;
        int emojiPicker;

        @Override public void openSymbolKeyboard() { symbolKeyboard++; }
        @Override public void switchLanguage() { languageSwitch++; }
        @Override public void toggleEmojiPicker() { emojiPicker++; }
        @Override public void toggleClipboard() { clipboard++; }
        @Override public void toggleFcc() { fcc++; }
        @Override public void toggleNumberPad() { numberPad++; }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();

        // A KEY2-shaped device: PKB, physical keyboard, BlackBerry hardware, and — the part that
        // matters here — no custom alt-mappings file, so usesMetaSymHandling() is false and
        // PhysicalKeyboardStateTracker.handleKeyDown(63) resets the Alt span. (On the KEY2 the
        // Alt layer comes from the firmware's .kcm, not from a table in the app.)
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ true,
                /* isBlackBerryDevice */ true, "qwerty", "4row"));
        assertFalse("these tests assert nothing unless the tracker really does reset Alt on a"
                        + " SYM down, which is what usesMetaSymHandling()==false buys",
                DeviceProfile.current().usesMetaSymHandling());

        KeyEventDeviceClassifier.getInstance().clearCache();

        PrefsManager.INSTANCE.getPrefs(context).edit().clear()
                .putString(AltSymShortcutHandler.PREF_KEY, AltSymShortcutHandler.ACTION_EMOJI_BOARD)
                .commit();
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
        assertEquals(AltSymShortcutHandler.ACTION_EMOJI_BOARD,
                SettingsManager.getInstance().getSettingsValues().altSymShortcutAction);

        keyboardSwitcher = mock(KeyboardSwitcher.class);
        tracker = new PhysicalKeyboardStateTracker(null);
        ime = mock(BlackBerryIME.class);

        HardwareKeyBridge bridge = new HardwareKeyBridge(ime);
        handler = bridge.getAltSymShortcutHandler();
        actions = new RecordingActions();
        handler.setCallback(actions);

        when(ime.getHardwareKeys()).thenReturn(bridge);
        when(ime.getPhysicalKeyboardStateTracker()).thenReturn(tracker);
        when(ime.getKeyboardSwitcher()).thenReturn(keyboardSwitcher);
        when(ime.getApplicationContext()).thenReturn(context);
        when(ime.getCurrentInputType()).thenReturn(0);
        when(ime.getCurrentImeOptions()).thenReturn(0);
        when(ime.isInputViewShown()).thenReturn(false);

        processor = new KeyEventProcessor(ime);
    }

    @After
    public void tearDown() {
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
        PrefsManager.INSTANCE.getPrefs(context).edit().clear().commit();
    }

    /**
     * deviceId 0 with a profile that has a physical keyboard is what
     * {@code KeyEventDeviceClassifier} calls a physical event.
     */
    private static KeyEvent physical(int action, int keyCode, int metaState) {
        return new KeyEvent(0L, 0L, action, keyCode, /* repeat */ 0, metaState,
                /* deviceId */ 0, /* scanCode */ 0, /* flags */ 0, InputDevice.SOURCE_KEYBOARD);
    }

    private void verifySymbolBoardNotToggled() {
        verify(keyboardSwitcher, never())
                .onSymbolShiftToggle(anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    // ==================== processKeyEventForState (accessibility pre-processing) ====================

    /**
     * Alt physically held: the only place that says so is the event's own meta state, which this
     * path used to ignore entirely.
     */
    @Test
    public void heldAltInTheEventMetaStateFiresTheChordOnTheStateOnlyPath() {
        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, KeyEvent.META_ALT_ON));

        assertEquals("Alt+Sym must dispatch the configured emoji action", 1, actions.emojiPicker);
        verifySymbolBoardNotToggled();
    }

    /**
     * Alt tapped (sticky), then Sym: the event carries no Alt at all, so the chord can only be
     * seen in the tracker's span state — and only if it is read before the tracker processes the
     * Sym down, which resets it.
     */
    @Test
    public void stickyAltSurvivesTheTrackersSymResetOnTheStateOnlyPath() {
        pressAndReleaseAlt();
        assertTrue("premise: a tapped Alt leaves META_ALT_ON in the internal state",
                (tracker.getInternalMetaState() & KeyEvent.META_ALT_ON) != 0);

        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0));

        assertEquals("sticky Alt + Sym must dispatch the configured action", 1, actions.emojiPicker);
        verifySymbolBoardNotToggled();
    }

    /** No Alt at all: the Sym key keeps its ordinary meaning. */
    @Test
    public void aBareSymStillTogglesTheSymbolBoardOnTheStateOnlyPath() {
        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0));

        assertEquals("no Alt is not a chord", 0, actions.emojiPicker);
        verify(keyboardSwitcher).onSymbolShiftToggle(0, 0, false, true);
    }

    /**
     * The release of a Sym press the chord consumed is not a second Sym press. Without this the
     * emoji board opened on the down and the symbol board opened on the up.
     */
    @Test
    public void theSymKeyUpAfterAChordIsConsumed() {
        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, KeyEvent.META_ALT_ON));
        assertEquals(1, actions.emojiPicker);

        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM, /* metaState */ 0));

        assertFalse("the chord's key-up flag must be spent by the key-up that follows it",
                handler.consumeSymKeyUp());
        assertEquals("the key-up must not re-dispatch the action", 1, actions.emojiPicker);
        verifySymbolBoardNotToggled();
    }

    /** A Sym press that did NOT fire the chord owns its own key-up. */
    @Test
    public void aBareSymLeavesNoOutstandingKeyUpClaim() {
        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0));

        assertFalse("a bare Sym must not claim its key-up", handler.consumeSymKeyUp());
    }

    // ==================== onKeyDownInternal (the ordinary hardware key path) ====================

    /**
     * The KEY2's own path. {@code pref_key_interceptor_enabled} defaults to false, so this is
     * where a real {@code KEYCODE_SYM} lands, and it had no chord handling at all.
     */
    @Test
    public void heldAltFiresTheChordOnTheOrdinaryHardwareKeyPath() {
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, KeyEvent.META_ALT_ON);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        boolean consumed = processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);

        assertTrue("Alt+Sym must be consumed here, not handed to KeyEventConverter", consumed);
        assertEquals(1, actions.emojiPicker);
        verifySymbolBoardNotToggled();
    }

    /** Sticky Alt, same path — and again the tracker's Sym reset must not get there first. */
    @Test
    public void stickyAltFiresTheChordOnTheOrdinaryHardwareKeyPath() {
        pressAndReleaseAlt();
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        boolean consumed = processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);

        assertTrue(consumed);
        assertEquals(1, actions.emojiPicker);
    }

    /**
     * The chord clears the Alt it used, so the next keystroke is not silently alt-shifted.
     */
    @Test
    public void theChordConsumesTheAltItUsed() {
        pressAndReleaseAlt();
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);

        assertEquals("Alt must not survive the chord", 0,
                tracker.getInternalMetaState() & KeyEvent.META_ALT_ON);
    }

    // ==================== the symbol board's own Alt page is not a chord ====================

    /**
     * Owner report, KEY2, 2026-09-21, right after the chord landed: "the emoji board is treated
     * like the third SYM board" — Sym, Sym, Sym opened the emoji picker. The second Sym turns the
     * PKB symbol board to its Alt page, and that page is a {@link KeyCharacterInterpreter.MetaMask}
     * that adds {@code META_ALT_ON} to {@code getInternalMetaState()} so letter keys type their
     * Alt-layer symbols. The chord read that masked state and took the board's own page for a held
     * Alt. No Alt key was ever pressed, so no chord: the third Sym must keep paging/closing.
     */
    private void turnTheSymbolBoardToItsAltPage() {
        // What KeyboardSwitcher.getKeyCharacterMap() hands the tracker on the Alt page: a mask
        // that switches META_ALT_ON on. The user's modifier keys are untouched.
        tracker.updateFilterAndAltGr(0, new KeyCharacterInterpreter.MetaMask(KeyEvent.META_ALT_ON, 0));
        assertTrue("premise: the masked internal state now carries Alt",
                (tracker.getInternalMetaState() & KeyEvent.META_ALT_ON) != 0);
        assertEquals("premise: the modifier KEYS have set nothing",
                0, tracker.getModifierKeyMetaState() & HardwareKeyBridge.ALT_ANY_MASK);
    }

    @Test
    public void theSymbolBoardsAltPageIsNotAChordOnTheStateOnlyPath() {
        turnTheSymbolBoardToItsAltPage();

        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0));

        assertEquals("the board's Alt page is paging, not Alt+Sym", 0, actions.emojiPicker);
        verify(keyboardSwitcher).onSymbolShiftToggle(0, 0, false, true);
    }

    @Test
    public void theSymbolBoardsAltPageIsNotAChordOnTheOrdinaryHardwareKeyPath() {
        turnTheSymbolBoardToItsAltPage();
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        try {
            processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);
        } catch (NullPointerException expectedPastTheChordBlock) {
            // Not a chord, so the press goes on into the ordinary key pipeline, which this fixture
            // does not stand up (no InputLogic). Everything this test asserts happened, or did
            // not, before that point.
        }

        assertEquals("the board's Alt page is paging, not Alt+Sym", 0, actions.emojiPicker);
        assertFalse("no chord means no claim on the Sym key-up", handler.consumeSymKeyUp());
    }

    /** A real held Alt on the Alt page is still the chord: the key state says so, not the mask. */
    @Test
    public void heldAltOnTheSymbolBoardsAltPageIsStillTheChord() {
        turnTheSymbolBoardToItsAltPage();
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, KeyEvent.META_ALT_ON);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        assertTrue(processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down));
        assertEquals(1, actions.emojiPicker);
    }

    // ==================== the shared "is this the Sym key" test ====================

    @Test
    public void aRealSymKeycodeWithNoDeviceConfigIsTheSymKey() {
        assertTrue(KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_SYM, /* mapping */ null));
        assertFalse(KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_A, /* mapping */ null));
        assertFalse("KEYCODE_ALT_RIGHT alone is an Alt key, not the MP01's Sym key",
                KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_ALT_RIGHT, /* mapping */ null));
    }

    /** Tap-and-release Alt, leaving the one-shot (sticky) Alt the tracker models with a span. */
    private void pressAndReleaseAlt() {
        KeyEvent altDown = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0);
        KeyEvent altUp = physical(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0);
        tracker.handleKeyDown(KeyEvent.KEYCODE_ALT_LEFT, altDown);
        tracker.handleKeyUp(KeyEvent.KEYCODE_ALT_LEFT, altUp);
    }
}
