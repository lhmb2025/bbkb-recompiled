package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
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
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_PICKER);

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
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_PICKER);
        when(ime.requestShowOnKeyPress()).thenReturn(false);

        bridge.getAltSymShortcutHandler().detectAndExecute(KeyEvent.META_ALT_ON);

        verify(keyboardSwitcher, never()).onEmojiKeyPressed();
    }

    /** Alt-locked (the app's own 0x200 span bit) counts as Alt, the same as a held Alt. */
    @Test
    public void altLockCountsAsTheChord() {
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_PICKER);

        bridge.getAltSymShortcutHandler().detectAndExecute(/* META_ALT_LOCKED */ 0x200);

        verify(keyboardSwitcher).onEmojiKeyPressed();
    }

    /** No Alt: the handler declines and the caller falls back to the plain Sym behaviour. */
    @Test
    public void noAltIsNotTheChord() {
        storeAction(AltSymShortcutHandler.ACTION_EMOJI_PICKER);

        org.junit.Assert.assertFalse(
                bridge.getAltSymShortcutHandler().detectAndExecute(/* metaState */ 0));
        verify(keyboardSwitcher, never()).onEmojiKeyPressed();
    }
}
