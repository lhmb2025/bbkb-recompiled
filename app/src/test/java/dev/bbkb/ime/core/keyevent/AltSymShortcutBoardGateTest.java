package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.ime.HardwareKeyBridge;
import dev.bbkb.ime.core.ime.InputViewCoordinator;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.InputSessionCoordinator;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager;
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * The third reason "Alt+Sym does nothing": the accessibility entry point detected the chord
 * correctly and then dispatched it into a window that was not up yet.
 *
 * <p>{@code HardwareKeyBridge.install()} wired the board actions behind a bare
 * {@code if (ime.isInputViewShown())}. On a physical-keyboard device the IME window is down for
 * most of the time the user is typing, and the SYM entry point deals with that by calling
 * {@code requestShowOnKeyPress()} — which only <em>asks</em> for the window
 * ({@code showSoftInputFromInputMethod} is asynchronous), so {@code isInputViewShown()} is still
 * false a microsecond later when the action runs. The action was therefore thrown away in
 * silence, while the plain-Sym fallback ({@code onSymbolShiftToggle}, which never had that gate)
 * went on working: precisely "Sym works, Alt+Sym does nothing".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class AltSymShortcutBoardGateTest {

    private Context context;
    private BlackBerryIME ime;
    private KeyboardSwitcher keyboardSwitcher;
    private HardwareKeyBridge bridge;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ true,
                /* isBlackBerryDevice */ true, "qwerty", "4row"));

        keyboardSwitcher = mock(KeyboardSwitcher.class);
        ime = mock(BlackBerryIME.class);
        when(ime.getKeyboardSwitcher()).thenReturn(keyboardSwitcher);
        when(ime.getCurrentInputType()).thenReturn(0);
        when(ime.getCurrentImeOptions()).thenReturn(0);
        when(ime.isUimEnabled()).thenReturn(false);
        // The state the owner's KEY2 is in while typing on the hardware keyboard: window down,
        // show-on-keypress willing to raise it.
        when(ime.isInputViewShown()).thenReturn(false);
        when(ime.requestShowOnKeyPress()).thenReturn(true);

        bridge = new HardwareKeyBridge(ime);
        bridge.install();
    }

    @After
    public void tearDown() {
        // Session state outside the settings snapshot: it must not leak into the next test.
        SettingsValues.setInputMenuRequestedByKey(false);
        DeviceProfile.setOnScreenKeyboardShowing(false);
        DeviceProfile.initialize(null);
        PrefsManager.INSTANCE.getPrefs(context).edit().clear().commit();
    }

    private void storeAction(String action) {
        PrefsManager.INSTANCE.getPrefs(context).edit().clear()
                .putString(AltSymShortcutHandler.PREF_KEY, action).commit();
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
        assertEquals(action, SettingsManager.getInstance().getSettingsValues().altSymShortcutAction);
    }

    @Test
    public void theEmojiActionOpensTheBoardWhenTheImeWindowIsStillComingUp() {
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_BOARD);

        boolean handled = bridge.getAltSymShortcutHandler()
                .detectAndExecute(KeyEvent.META_ALT_ON);

        org.junit.Assert.assertTrue("the chord itself was detected", handled);
        verify(keyboardSwitcher).onEmojiKeyPressed();
    }

    @Test
    public void theSymbolBoardActionOpensTheBoardWhenTheImeWindowIsStillComingUp() {
        storeAction(AltSymShortcutHandler.ACTION_SYMBOL_KEYBOARD);

        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);

        verify(keyboardSwitcher).onSymbolShiftToggle(0, 0, false, true);
    }

    /**
     * When show-on-keypress declines — no editor bound, or the user set the mode to never —
     * there is nothing to open a board into, and the action stays a no-op rather than throwing a
     * board at a window that will never appear.
     */
    @Test
    public void theEmojiActionStaysQuietWhenNoWindowCanBeShown() {
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_BOARD);
        when(ime.requestShowOnKeyPress()).thenReturn(false);

        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);

        verify(keyboardSwitcher, never()).onEmojiKeyPressed();
    }

    /**
     * Cursor control and the number pad, with the input menu off (or hidden with "Show the
     * suggestion bar"): they used to do nothing there, as UIM-only boards. They now open on their
     * own, like the clipboard and emoji boards, and the second chord closes them through the
     * board's own toggle.
     */
    @Test
    public void cursorControlAndTheNumberPadOpenWithoutTheInputMenu() {
        FccController fcc = mock(FccController.class);
        NumberPadController numberPad = mock(NumberPadController.class);
        UnifiedInputBoardManager uim = mock(UnifiedInputBoardManager.class);
        when(ime.getFccController()).thenReturn(fcc);
        when(ime.getNumberPadController()).thenReturn(numberPad);
        when(keyboardSwitcher.getUnifiedInputBoardManager()).thenReturn(uim);

        storeAction(AltSymShortcutHandler.ACTION_FCC);
        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);
        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);
        verify(fcc, times(2)).toggle();

        storeAction(AltSymShortcutHandler.ACTION_NUMBER_PAD);
        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);
        verify(numberPad).toggle();

        verify(uim, never()).requestBoard(anyInt());
    }

    /** With the menu on, the same chords still go through the board coordinator. */
    @Test
    public void cursorControlAndTheNumberPadUseTheCoordinatorWithTheInputMenuOn() {
        FccController fcc = mock(FccController.class);
        NumberPadController numberPad = mock(NumberPadController.class);
        UnifiedInputBoardManager uim = mock(UnifiedInputBoardManager.class);
        when(ime.getFccController()).thenReturn(fcc);
        when(ime.getNumberPadController()).thenReturn(numberPad);
        when(keyboardSwitcher.getUnifiedInputBoardManager()).thenReturn(uim);
        when(ime.isUimEnabled()).thenReturn(true);

        storeAction(AltSymShortcutHandler.ACTION_FCC);
        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);
        storeAction(AltSymShortcutHandler.ACTION_NUMBER_PAD);
        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);

        verify(uim).requestBoard(FccController.KEY_CODE);
        verify(uim).requestBoard(NumberPadController.KEY_CODE);
        verify(fcc, never()).toggle();
        verify(numberPad, never()).toggle();
    }

    // ── "Show or hide the input menu" ────────────────────────────────────────

    /** The menu bar as far as the toggle can see it: up or not. */
    private static final class FakeMenu {
        boolean up;
    }

    private UnifiedInputBoardManager uim;
    private FccController fcc;

    /**
     * The chord configured to {@code action} on a KEY2 with "Show the suggestion bar" off; the
     * IME's menu setting answered by the real SettingsValues (all the production
     * {@code isUimEnabled()} reads), the real InputViewCoordinator behind the action, and a menu
     * bar the board manager mock raises and lowers.
     */
    private FakeMenu chordWithTheBarHidden(String action) {
        PrefsManager.INSTANCE.getPrefs(context).edit().clear()
                .putString(AltSymShortcutHandler.PREF_KEY, action)
                .putBoolean(SettingsManager.PREF_PKB_SHOW_SUGGESTION_BAR, false).commit();
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
        DeviceProfile.setOnScreenKeyboardShowing(false);
        org.junit.Assert.assertTrue(SettingsManager.getInstance().getSettingsValues().isPkbSuggestionBarHidden());
        when(ime.isUimEnabled()).thenAnswer(
                invocation -> SettingsManager.getInstance().getSettingsValues().isUimEnabled());
        when(ime.getSettingsManager()).thenReturn(SettingsManager.getInstance());

        InputViewCoordinator coordinator = new InputViewCoordinator(ime);
        coordinator.initialize();
        when(ime.getUiCoordinator()).thenReturn(coordinator);

        FakeMenu menu = new FakeMenu();
        if (uim == null) {
            uim = mock(UnifiedInputBoardManager.class);
            fcc = mock(FccController.class);
            when(keyboardSwitcher.getUnifiedInputBoardManager()).thenReturn(uim);
            when(ime.getFccController()).thenReturn(fcc);
        }
        when(uim.isShowing()).thenAnswer(invocation -> menu.up);
        doAnswer(invocation -> { menu.up = true; return true; }).when(uim).showMenuForKey();
        doAnswer(invocation -> { menu.up = false; return null; }).when(uim).hide();
        return menu;
    }

    private void chord() {
        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);
    }

    /**
     * With "Show the suggestion bar" off there is no hamburger button: the chord raises the menu
     * anyway, and while it is up the board chords take its route; the next chord hides it and the
     * board chords go back to their own toggles. The preferences are never written.
     */
    @Test
    public void theInputMenuChordRaisesTheHiddenMenuAndTheNextOneHidesIt() {
        FakeMenu menu = chordWithTheBarHidden(AltSymShortcutHandler.ACTION_TOGGLE_UIM);
        org.junit.Assert.assertFalse("the fixture: the setting keeps the menu off", ime.isUimEnabled());

        chord();

        verify(uim).showMenuForKey();
        org.junit.Assert.assertTrue(menu.up);
        org.junit.Assert.assertTrue(SettingsValues.isInputMenuRequestedByKey());
        org.junit.Assert.assertTrue(ime.isUimEnabled());

        chord();

        verify(uim).hide();
        org.junit.Assert.assertFalse(menu.up);
        org.junit.Assert.assertFalse(SettingsValues.isInputMenuRequestedByKey());
        org.junit.Assert.assertFalse(ime.isUimEnabled());
        org.junit.Assert.assertFalse(PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(SettingsManager.PREF_PKB_SHOW_SUGGESTION_BAR, true));
        org.junit.Assert.assertFalse(PrefsManager.INSTANCE.getPrefs(context).contains("pref_uim_enabled"));
    }

    @Test
    public void whileTheChordsMenuIsUpTheBoardChordsGoThroughTheCoordinator() {
        chordWithTheBarHidden(AltSymShortcutHandler.ACTION_TOGGLE_UIM);
        chord();
        org.junit.Assert.assertTrue(SettingsValues.isInputMenuRequestedByKey());

        // Reconfigured mid-session: the stored settings reload, the session override does not.
        chordWithTheBarHidden(AltSymShortcutHandler.ACTION_FCC).up = true;
        chord();

        verify(uim).requestBoard(FccController.KEY_CODE);
        verify(fcc, never()).toggle();
    }

    /** The next field gets the user's settings back: the menu the chord raised goes at input start. */
    @Test
    public void theMenuTheChordRaisedEndsAtTheNextInputStart() {
        FakeMenu menu = chordWithTheBarHidden(AltSymShortcutHandler.ACTION_TOGGLE_UIM);
        chord();
        org.junit.Assert.assertTrue(SettingsValues.isInputMenuRequestedByKey());

        new InputSessionCoordinator(ime).onStartInputInternal(new EditorInfo(), false);

        org.junit.Assert.assertFalse(SettingsValues.isInputMenuRequestedByKey());
        verify(uim).hide();
        org.junit.Assert.assertFalse(menu.up);
        org.junit.Assert.assertFalse(ime.isUimEnabled());
    }

    /** As for the boards: no window to put the menu in, no menu. */
    @Test
    public void theInputMenuChordStaysQuietWhenNoWindowCanBeShown() {
        chordWithTheBarHidden(AltSymShortcutHandler.ACTION_TOGGLE_UIM);
        when(ime.requestShowOnKeyPress()).thenReturn(false);

        chord();

        verify(uim, never()).showMenuForKey();
        org.junit.Assert.assertFalse(SettingsValues.isInputMenuRequestedByKey());
    }

    /** Alt-locked (the app's own 0x200 span bit) counts as Alt, the same as a held Alt. */
    @Test
    public void altLockCountsAsTheChord() {
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_BOARD);

        bridge.getAltSymShortcutHandler().detectAndExecute(/* META_ALT_LOCKED */ 0x200);

        verify(keyboardSwitcher).onEmojiKeyPressed();
    }

    /** No Alt: the handler declines and the caller falls back to the plain Sym behaviour. */
    @Test
    public void noAltIsNotTheChord() {
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_BOARD);

        org.junit.Assert.assertFalse(
                bridge.getAltSymShortcutHandler().detectAndExecute(/* metaState */ 0));
        verify(keyboardSwitcher, never()).onEmojiKeyPressed();
    }
}
