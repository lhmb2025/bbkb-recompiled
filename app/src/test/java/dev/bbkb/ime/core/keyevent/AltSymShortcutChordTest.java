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
 * Pins the Alt+Sym chord (configured action, e.g. emoji picker) on both Sym entry points: the
 * ordinary hardware key path ({@link KeyEventProcessor#onKeyDownInternal}) and the state-only
 * accessibility path ({@link KeyEventProcessor#processKeyEventForState}). Held Alt comes from the
 * event's meta state; sticky Alt must be read before {@code tracker.handleKeyDown(63)} resets it.
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
        int inputMenu;
        int ctrlTaps;

        @Override public void openSymbolKeyboard() { symbolKeyboard++; }
        @Override public void switchLanguage() { languageSwitch++; }
        @Override public void quickSwitchLanguage() { languageSwitch++; }
        @Override public void toggleEmojiPicker() { emojiPicker++; }
        @Override public void toggleClipboard() { clipboard++; }
        @Override public void toggleFcc() { fcc++; }
        @Override public void toggleNumberPad() { numberPad++; }
        @Override public void toggleInputMenu() { inputMenu++; }
        @Override public void tapCtrl() { ctrlTaps++; }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();

        // KEY2-shaped: no custom alt-mappings file, so usesMetaSymHandling() is false and
        // handleKeyDown(63) resets the Alt span. (The KEY2 Alt layer comes from the firmware .kcm.)
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

    /** Alt physically held: only the event's own meta state says so. */
    @Test
    public void heldAltInTheEventMetaStateFiresTheChordOnTheStateOnlyPath() {
        processor.processKeyEventForState(
                physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, KeyEvent.META_ALT_ON));

        assertEquals("Alt+Sym must dispatch the configured emoji action", 1, actions.emojiPicker);
        verifySymbolBoardNotToggled();
    }

    /**
     * Sticky Alt, then Sym: the event carries no Alt, so the chord is only in the tracker's span
     * state, and only if read before the tracker processes the Sym down (which resets it).
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
     * The release of a Sym press the chord consumed is not a second Sym press; otherwise the
     * symbol board would open on the up.
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

    /**
     * The KEY2's own path: {@code pref_key_interceptor_enabled} defaults to false, so a real
     * {@code KEYCODE_SYM} lands here.
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

    /** Sticky Alt, same path: the tracker's Sym reset must not get there first. */
    @Test
    public void stickyAltFiresTheChordOnTheOrdinaryHardwareKeyPath() {
        pressAndReleaseAlt();
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        boolean consumed = processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);

        assertTrue(consumed);
        assertEquals(1, actions.emojiPicker);
    }

    /** The chord clears the Alt it used, so the next keystroke is not silently alt-shifted. */
    @Test
    public void theChordConsumesTheAltItUsed() {
        pressAndReleaseAlt();
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);

        processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);

        assertEquals("Alt must not survive the chord", 0,
                tracker.getInternalMetaState() & KeyEvent.META_ALT_ON);
    }

    /**
     * The PKB symbol board's Alt page is a {@link KeyCharacterInterpreter.MetaMask} adding
     * {@code META_ALT_ON} to {@code getInternalMetaState()}; that is not a held Alt, so Sym, Sym,
     * Sym must keep paging/closing rather than open the chord's emoji picker.
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

    private void useAction(String action) {
        PrefsManager.INSTANCE.getPrefs(context).edit()
                .putString(AltSymShortcutHandler.PREF_KEY, action).commit();
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
    }

    private void symTapThroughProcessor() {
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, /* metaState */ 0);
        when(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down);
        processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down);
        assertTrue("the chord's Sym release is swallowed", handler.consumeSymKeyUp());
    }

    /**
     * Hold Alt, tap Sym twice: two taps of Ctrl, which is the double tap that locks it. The first
     * Alt+Sym spends the Alt it used (so a released Alt is not left on for the next key), and the
     * Alt still held down must count for the second all the same.
     */
    @Test
    public void heldAltWithTwoSymTapsIsTwoCtrlTaps() {
        useAction(AltSymShortcutHandler.ACTION_CTRL);
        tracker.handleKeyDown(KeyEvent.KEYCODE_ALT_LEFT, physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0));

        symTapThroughProcessor();
        assertTrue("premise: the first chord spent the Alt", tracker.isAltKeyConsumed());
        symTapThroughProcessor();

        assertEquals(2, actions.ctrlTaps);
        verifySymbolBoardNotToggled();
    }

    /** Only the Ctrl action reads a spent, still-held Alt as Alt; the board actions keep their rule. */
    @Test
    public void onlyTheCtrlActionIsTheCtrlAction() {
        assertFalse(handler.isCtrlAction());
        useAction(AltSymShortcutHandler.ACTION_CTRL);
        assertTrue(handler.isCtrlAction());
    }

    /** The retired "ctrl_mode" choice comes back as Ctrl rather than as nothing. */
    @Test
    public void theRetiredCtrlModeChoiceUpgradesToCtrl() {
        assertEquals(AltSymShortcutHandler.ACTION_CTRL,
                AltSymShortcutHandler.upgradeLegacyAction(AltSymShortcutHandler.LEGACY_ACTION_CTRL_MODE));
    }
}
