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
import org.robolectric.annotation.LooperMode;

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
// The updater hands the icon over at the end of the main-thread message; under the legacy looper
// that is at once, so each case below still reads "do this, the icon is that".
@LooperMode(LooperMode.Mode.LEGACY)
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

    // ==================== binder traffic: post only on a change ====================

    /** One keyboard show (two re-asserts) and one hide (three full resets), as the IME runs them. */
    private void showHideCycle() {
        tracker.refreshModifierStatus();   // onStartInputView
        tracker.refreshModifierStatus();   // onWindowShown
        tracker.resetModifiers(ModifierResetReason.FINISH_INPUT);
        tracker.resetModifiers(ModifierResetReason.WINDOW_HIDDEN);
        tracker.resetModifiers(ModifierResetReason.WINDOW_HIDDEN);
    }

    /**
     * Each post is an {@code updateStatusIconAsync} binder call; five show/hide cycles with no
     * modifier used to make 25 of them. A hidden icon is posted once, then never again.
     */
    @Test
    public void showHideCyclesWithNoModifierPostTheHiddenIconOnce() {
        for (int i = 0; i < 5; i++) {
            showHideCycle();
        }

        verify(ime, Mockito.times(1)).hideStatusIcon();
        verify(ime, never()).showStatusIcon(Mockito.anyInt());
    }

    /** The two re-asserts of one show post a visible icon once. */
    @Test
    public void theReassertsOfOneShowPostAVisibleIconOnce() {
        installCtrlSource();
        setCtrl(true, true);
        tracker.refreshModifierStatus();
        tracker.refreshModifierStatus();

        // The change itself, then one re-assert.
        verify(ime, Mockito.times(2)).showStatusIcon(R.drawable.ic_status_ctrl_locked);
    }

    /**
     * The system clears the slot between sessions, so after a session boundary the re-assert of
     * an icon that never changed (a locked Ctrl outlives the reset) still has to go out.
     */
    @Test
    public void aReassertAfterASessionBoundaryPostsAgain() {
        installCtrlSource();
        setCtrl(true, true);
        tracker.refreshModifierStatus();
        tracker.resetModifiers(ModifierResetReason.WINDOW_HIDDEN);
        Mockito.clearInvocations(ime);

        tracker.refreshModifierStatus();
        tracker.refreshModifierStatus();

        verify(ime, Mockito.times(1)).showStatusIcon(R.drawable.ic_status_ctrl_locked);
        verify(ime, never()).hideStatusIcon();
    }

    /** A reload inside a show is not a session boundary. */
    @Test
    public void aKeyboardReloadDoesNotReopenTheReassert() {
        installCtrlSource();
        setCtrl(true, true);
        tracker.refreshModifierStatus();
        tracker.resetModifiers(ModifierResetReason.KEYBOARD_RELOADED);
        Mockito.clearInvocations(ime);

        tracker.refreshModifierStatus();

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

    // ==================== Ctrl ====================

    /** Ctrl's one owner, as the tracker sees it: two switches the test flips. */
    private final boolean[] ctrlActive = {false};
    private final boolean[] ctrlLocked = {false};

    private void installCtrlSource() {
        tracker.setCtrlStateSource(new PhysicalKeyboardStateTracker.CtrlStateSource() {
            @Override public boolean isCtrlActive() { return ctrlActive[0]; }
            @Override public boolean isCtrlLocked() { return ctrlLocked[0]; }
        });
    }

    private void setCtrl(boolean active, boolean locked) {
        ctrlActive[0] = active;
        ctrlLocked[0] = locked;
        tracker.onCtrlStateChanged();
    }

    @Test
    public void ctrlOnForTheNextKeyShowsTheCtrlIcon() {
        installCtrlSource();
        setCtrl(true, false);

        verify(ime).showStatusIcon(R.drawable.ic_status_ctrl);
    }

    @Test
    public void lockedCtrlShowsTheCtrlLockedIconAndTurningItOffHidesIt() {
        installCtrlSource();
        setCtrl(true, false);
        setCtrl(true, true);
        setCtrl(false, false);

        InOrder order = Mockito.inOrder(ime);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_ctrl);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_ctrl_locked);
        order.verify(ime).hideStatusIcon();
    }

    @Test
    public void ctrlWithAShiftShowsTheCombinedIcon() {
        installCtrlSource();
        setCtrl(true, false);
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_ctrl_shift);
    }

    @Test
    public void lockedCtrlWithALockedShiftShowsTheDoubleLockedIcon() {
        installCtrlSource();
        setCtrl(true, true);
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_ctrl_locked_shift_locked);
    }

    /** Alt on top of a latched Ctrl wins the next key, so the icon shows Alt. */
    @Test
    public void altOutranksCtrlInTheIcon() {
        installCtrlSource();
        setCtrl(true, true);
        trackerDown(KeyEvent.KEYCODE_ALT_LEFT);

        InOrder order = Mockito.inOrder(ime);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_ctrl_locked);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_alt);
    }

    /** Every Ctrl state and Shift state lands on its own drawable. */
    @Test
    public void everyCtrlAndShiftCombinationHasItsIcon() {
        int shift = KeyEvent.META_SHIFT_ON;
        int shiftLocked = android.text.method.MetaKeyKeyListener.META_CAP_LOCKED;
        assertEquals(R.drawable.ic_status_ctrl,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(0, true, false)));
        assertEquals(R.drawable.ic_status_ctrl_locked,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(0, true, true)));
        assertEquals(R.drawable.ic_status_ctrl_shift,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(shift, true, false)));
        assertEquals(R.drawable.ic_status_ctrl_shift_locked,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(shiftLocked, true, false)));
        assertEquals(R.drawable.ic_status_ctrl_locked_shift,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(shift, true, true)));
        assertEquals(R.drawable.ic_status_ctrl_locked_shift_locked,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(shiftLocked, true, true)));
        assertEquals(R.drawable.ic_status_shift,
                ModifierStatusBarUpdater.getStatusIconDrawable(ModifierStatusBarUpdater.withCtrl(shift, false, false)));
    }

    // ==================== a glyph stays while its key is down ====================

    /**
     * The blink: the second press of a double tap turns the modifier off on its key-down and
     * locks it on the key-up, and the icon used to go off for the length of that press.
     */
    @Test
    public void aDoubleTapGoesFromTheIconToTheLockedIconWithoutHidingInBetween() {
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);

        InOrder order = Mockito.inOrder(ime);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_shift);
        order.verify(ime).showStatusIcon(R.drawable.ic_status_shift_locked);
        verify(ime, never()).hideStatusIcon();
    }

    @Test
    public void aDoubleTappedAltDoesNotHideInBetweenEither() {
        trackerTap(KeyEvent.KEYCODE_ALT_LEFT);
        trackerTap(KeyEvent.KEYCODE_ALT_LEFT);

        verify(ime).showStatusIcon(R.drawable.ic_status_alt_locked);
        verify(ime, never()).hideStatusIcon();
    }

    /** The press that unlocks: the locked icon stays while the key is down and goes on release. */
    @Test
    public void unlockingHidesTheIconWhenTheKeyComesUpNotWhenItGoesDown() {
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);
        trackerTap(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals("premise: Shift is locked", true, tracker.getModifierState().isShiftLocked());

        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        verify(ime, never()).hideStatusIcon();

        trackerUp(KeyEvent.KEYCODE_SHIFT_LEFT);
        verify(ime).hideStatusIcon();
    }

    /** A held Shift that a character has used is still a held Shift: the icon waits for the release. */
    @Test
    public void aHeldShiftKeepsItsIconAfterACharacterUntilItIsReleased() {
        trackerDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);
        verify(ime, never()).hideStatusIcon();

        trackerUp(KeyEvent.KEYCODE_SHIFT_LEFT);
        verify(ime).hideStatusIcon();
    }
}
