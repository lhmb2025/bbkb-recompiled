package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * The PKB modifier status-bar icon: {@link ModifierStatusBarUpdater} driven from the hardware key
 * path, i.e. the "Shift / Alt indicator in the status bar" the owner reported missing on a KEY2
 * (2026-09-22). Nothing covered this class before.
 *
 * <p>Two things here are the bug, and both come from the same assumption — that the status-bar
 * slot stays exactly as the IME last left it:
 *
 * <ul>
 *   <li>The updater posts only when the meta state <em>changes</em>, and {@code forceUpdate} used
 *       to mean nothing more than "skip the physical-keyboard check": it still fell through the
 *       {@code mLastMetaState != metaState} short circuit. So the IME had no way at all to put
 *       the icon back. It is not the IME's slot: {@code InputMethodManagerService} clears the IME
 *       icon on every unbind, and {@code InputMethodService.showStatusIcon} silently returns when
 *       the privileged operations are not attached yet — which on a PKB is exactly the first
 *       modifier press of a session, made while {@code requestShowOnKeyPress()} is still bringing
 *       the window up. After one dropped post the cache said "already showing" forever.</li>
 *   <li>{@code consumeModifiersAfterKey} spends a sticky Shift/Alt without telling the updater,
 *       so the icon outlived the modifier — and then the <em>next</em> press of the same modifier
 *       computed the same meta state as the cache and posted nothing.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ModifierStatusIconTest {

    private Context context;
    private BlackBerryIME ime;
    private PhysicalKeyboardStateTracker tracker;
    private KeyEventProcessor processor;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PrefsManager.INSTANCE.getPrefs(context).edit().clear().commit();
        // initialize() first so DeviceProfile.appContext() is non-null: KeyEventProcessor
        // re-detects the profile from the live hardware when it is null, and a JVM has no
        // physical keyboard, which would silently disarm the icon's own PKB gate.
        DeviceProfile.initialize(context);
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ true,
                /* isBlackBerryDevice */ true, "qwerty", "4row"));
        KeyEventDeviceClassifier.getInstance().clearCache();
        loadSettings();

        ime = mock(BlackBerryIME.class, Mockito.RETURNS_DEEP_STUBS);
        when(ime.getResources()).thenReturn(context.getResources());
        when(ime.getPackageName()).thenReturn(context.getPackageName());
        when(ime.getApplicationContext()).thenReturn(context);
        when(ime.isInputActive()).thenReturn(true);
        when(ime.isInputViewShown()).thenReturn(true);
        when(ime.getCurrentInputEditorInfo()).thenReturn(new EditorInfo());
        when(ime.getSettingsManager()).thenReturn(SettingsManager.getInstance());
        tracker = new PhysicalKeyboardStateTracker(new ModifierStatusBarUpdater(ime));
        when(ime.getPhysicalKeyboardStateTracker()).thenReturn(tracker);
        processor = new KeyEventProcessor(ime);
    }

    @After
    public void tearDown() {
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
        PrefsManager.INSTANCE.getPrefs(context).edit().clear().commit();
    }

    private void loadSettings() {
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
    }

    /** deviceId 0 on a PKB profile is what {@code KeyEventDeviceClassifier} calls physical. */
    private static KeyEvent physical(int action, int keyCode) {
        return new KeyEvent(0L, 0L, action, keyCode, /* repeat */ 0, /* metaState */ 0,
                /* deviceId */ 0, /* scanCode */ 0, /* flags */ 0, InputDevice.SOURCE_KEYBOARD);
    }

    /** Runs a hardware key-down through the whole {@link KeyEventProcessor} path. */
    private void keyDownThroughProcessor(int keyCode) {
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, keyCode);
        when(ime.remapKeyEvent(keyCode, down)).thenReturn(down);
        processor.onKeyDownInternal(keyCode, down);
    }

    private void trackerDown(int keyCode) {
        tracker.handleKeyDown(keyCode, physical(KeyEvent.ACTION_DOWN, keyCode));
    }

    private void trackerUp(int keyCode) {
        tracker.handleKeyUp(keyCode, physical(KeyEvent.ACTION_UP, keyCode));
    }

    private void trackerTap(int keyCode) {
        trackerDown(keyCode);
        trackerUp(keyCode);
    }

    // ==================== the hardware key path posts the icon ====================

    /** The owner's report, at its narrowest: press Shift on the KEY2, get the Shift icon. */
    @Test
    public void aShiftPressOnTheHardwarePathShowsTheShiftIcon() {
        keyDownThroughProcessor(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_shift);
    }

    @Test
    public void anAltPressOnTheHardwarePathShowsTheAltIcon() {
        keyDownThroughProcessor(KeyEvent.KEYCODE_ALT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_alt);
    }

    /** A tapped modifier is sticky: the icon stays up across the release. */
    @Test
    public void aStickyShiftKeepsTheIconUpAfterTheRelease() {
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_shift);
        verify(ime, never()).hideStatusIcon();
    }

    /** Double tap locks, and the locked state has its own icon. */
    @Test
    public void aDoubleTappedAltShowsTheAltLockedIcon() {
        trackerTap(KeyEvent.KEYCODE_ALT_LEFT);
        trackerTap(KeyEvent.KEYCODE_ALT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_alt_locked);
    }

    /** Both modifiers at once pick the combined icon. */
    @Test
    public void altAndShiftTogetherShowTheCombinedIcon() {
        trackerDown(KeyEvent.KEYCODE_ALT_LEFT);
        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_alt_shift);
    }

    // ==================== clearing the state takes the icon down ====================

    @Test
    public void clearingTheModifierStateHidesTheIcon() {
        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        verify(ime).showStatusIcon(R.drawable.ic_status_shift);

        tracker.resetAllMetaState();

        InOrder order = Mockito.inOrder(ime);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_shift);
        order.verify(ime).hideStatusIcon();
    }

    @Test
    public void releasingAHeldShiftThatWasUsedHidesTheIcon() {
        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        // A character typed while Shift is held spends it.
        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);
        trackerUp(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).hideStatusIcon();
        assertEquals("premise: no modifier is left set", 0, tracker.getInternalMetaState());
    }

    /**
     * The sticky-modifier half of the bug. A tapped Shift is spent by the next character, and
     * that transition is reported by nobody else — the Shift key's own key-up already ran.
     */
    @Test
    public void spendingAStickyShiftOnACharacterHidesTheIcon() {
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);
        verify(ime).showStatusIcon(R.drawable.ic_status_shift);
        assertEquals("premise: the tap left Shift sticky",
                KeyEvent.META_SHIFT_ON, tracker.getInternalMetaState() & KeyEvent.META_SHIFT_ON);

        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);

        assertEquals("premise: the character spent it", 0, tracker.getInternalMetaState());
        verify(ime).hideStatusIcon();
    }

    /**
     * And the consequence of leaving that transition unreported: because the updater posts only
     * on a change, the NEXT tap of the same modifier computed the same meta state the stale cache
     * held and posted nothing at all.
     */
    @Test
    public void aSecondStickyShiftAfterAConsumedOneIsPostedAgain() {
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);
        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);
        Mockito.clearInvocations(ime);

        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_shift);
    }

    // ==================== the IME can re-assert an icon the system dropped ====================

    /**
     * The system owns the slot: it clears the IME icon on unbind, and drops a post made before
     * the IME's privileged operations exist. The IME must be able to put the icon back for a
     * state that has not changed — which is what {@code onWindowShown} / {@code onStartInputView}
     * now do.
     */
    @Test
    public void refreshRePostsTheIconForAnUnchangedState() {
        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        Mockito.clearInvocations(ime);

        tracker.refreshModifierStatus();

        verify(ime).showStatusIcon(R.drawable.ic_status_shift);
    }

    /** With no modifier set, the same re-assert clears the slot rather than leaving it stale. */
    @Test
    public void refreshWithNoModifierClearsTheSlot() {
        tracker.refreshModifierStatus();

        verify(ime).hideStatusIcon();
        verify(ime, never()).showStatusIcon(Mockito.anyInt());
    }

    // ==================== the preference still turns it off ====================

    @Test
    public void theIconIsNotPostedWhenThePreferenceIsOff() {
        PrefsManager.INSTANCE.getPrefs(context).edit()
                .putBoolean(context.getString(R.string.pref_show_pkb_modifier_status_icon_key), false)
                .commit();
        loadSettings();

        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime, never()).showStatusIcon(Mockito.anyInt());
    }
}
