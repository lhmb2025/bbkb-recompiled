package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.method.MetaKeyKeyListener;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.keyevent.ModifierState;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * <b>Equivalence</b> for the modifier read sites Phase 1b could not reach, driven through the real
 * {@code PhysicalKeyboardStateTracker}.
 *
 * <p>Phase 1b routed the physical-keyboard path through one query API, {@link ModifierState}, and
 * listed the sites it had to leave behind because they live in files it did not own:
 * {@code BlackBerryIME}, {@code KeyboardSwitcher}, {@code KeyboardState}, {@code AuxBarManager},
 * {@code MoreKeysProvider}, {@code CommitController}, {@code InputLogic}, {@code CkbGestureBridge}
 * and the screen-off receiver. Phase 1f migrates them.
 *
 * <p>Each migration replaces a hand-rolled tracker expression with a named query, and the claim in
 * every case is that the two are the <em>same</em> answer. That claim is what this file tests: it
 * evaluates the old expression and the new one against the same live tracker state and asserts they
 * agree, over every modifier state the sites can actually be in. {@code ModifierStateContractTest}
 * already pins what each query <em>means</em>; this file pins that the call sites did not change
 * meaning when they started using them.
 *
 * <p>One pair does <b>not</b> agree everywhere, and the divergence is deliberate and narrow — see
 * {@link #lockedAltIsTheOneCaseWhereTheMultifunctionAltCheckChanges()}.
 *
 * <p>Phase 1h moved this file from {@code dev.bbkb.ime.core} into the tracker's package: the
 * tracker's per-key Shift predicates it compares against are package-private now, so that
 * production code has one way to ask. Nothing else about it moved.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ModifierReadSiteEquivalenceTest {

    /** 256. What {@code BlackBerryIME.isMetaKeyActive()} passed to {@code hasMetaFlag}. */
    private static final int META_CAP_LOCKED = MetaKeyKeyListener.META_CAP_LOCKED;

    private PhysicalKeyboardStateTracker tracker;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ false,
                /* isBlackBerryDevice */ false, "qwerty", "4row"));
        ScancodeMappingResolver.getInstance().reset();
        KeyEventDeviceClassifier.getInstance().clearCache();
        tracker = new PhysicalKeyboardStateTracker(null);
        tracker.setSymKeyCode(KeyEvent.KEYCODE_SYM);
    }

    @After
    public void tearDown() {
        ScancodeMappingResolver.getInstance().reset();
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
    }

    private static KeyEvent event(int action, int keyCode) {
        return new KeyEvent(0L, 0L, action, keyCode, /* repeat */ 0, /* metaState */ 0,
                /* deviceId */ 0, /* scanCode */ keyCode, /* flags */ 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    private void down(int keyCode) {
        tracker.handleKeyDown(keyCode, event(KeyEvent.ACTION_DOWN, keyCode));
    }

    private void up(int keyCode) {
        tracker.handleKeyUp(keyCode, event(KeyEvent.ACTION_UP, keyCode));
    }

    private void tap(int keyCode) {
        down(keyCode);
        up(keyCode);
    }

    // ═══════════════════════════════════════════════════ the pairs, site by site

    /**
     * Assert that every migrated read answers exactly what the expression it replaced answered.
     * Run after each state setup below, so the net covers the whole matrix rather than one point.
     */
    private void assertEveryReadSiteAgrees(String state) {
        ModifierState modifiers = tracker.getModifierState();

        // BlackBerryIME.onUpdateSelection: isShiftKeyDown() -> isShiftHeld()
        assertEquals(state + ": isShiftKeyDown/isShiftHeld",
                tracker.isShiftKeyDown(), modifiers.isShiftHeld());

        // BlackBerryIME.isMetaKeyActive() and isShiftChording(): all migrated terms. The Shift-released
        // term was left on the tracker in 1f and moved in 1h, once the snapshot carried CONSUMED.
        assertEquals(state + ": isShiftReleased",
                tracker.isShiftReleased(), modifiers.isShiftReleased());
        assertEquals(state + ": hasMetaFlag(META_CAP_LOCKED)/isShiftLockedForLayout",
                tracker.hasMetaFlag(META_CAP_LOCKED), modifiers.isShiftLockedForLayout());
        assertEquals(state + ": isAltUsedWithKey",
                tracker.isAltUsedWithKey(), modifiers.isAltUsedWithKey());

        // InputLogic: isManualShiftAndShiftPressing() -> isManualShiftPending(). Unguarded since 1h:
        // the CONSUMED state agrees too.
        assertEquals(state + ": isManualShiftAndShiftPressing/isManualShiftPending",
                tracker.isManualShiftAndShiftPressing(), modifiers.isManualShiftPending());

        // KeyboardSwitcher / KeyboardState: isSymKeyHeld() -> isSymHeld()
        assertEquals(state + ": isSymKeyHeld/isSymHeld",
                tracker.isSymKeyHeld(), modifiers.isSymHeld());

        // AuxBarManager's hold-action check and MoreKeysProvider's Alt-style lookup both read an
        // event's own meta, which ModifierState.ofEvent answers identically.
        KeyEvent shifted = new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N, 0,
                KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON, 0, KeyEvent.KEYCODE_N, 0,
                InputDevice.SOURCE_KEYBOARD);
        assertEquals(state + ": event shift", shifted.isShiftPressed(),
                ModifierState.ofEvent(shifted).isShiftHeld());
        assertEquals(state + ": event alt", shifted.isAltPressed(),
                ModifierState.ofEvent(shifted).isAltHeld());
    }

    /**
     * The multifunction-Ctrl remap's "is Alt active at press time" check, in both spellings. Alt
     * LOCKED is excluded: it is the one state they disagree in, and the test below is about that.
     */
    private void assertMultifunctionAltCheckAgrees(String state) {
        KeyEvent plain = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1);
        boolean oldAltActive = plain.isAltPressed()
                || (tracker.getInternalMetaState() & KeyEvent.META_ALT_MASK) != 0;
        boolean newAltActive = tracker.getModifierState(plain).isAltActiveForCharacter();
        assertEquals(state + ": multifunction alt check", oldAltActive, newAltActive);
    }

    // ═══════════════════════════════════════════════════════════ the state matrix

    @Test
    public void nothingActive() {
        assertEveryReadSiteAgrees("nothing");
        assertMultifunctionAltCheckAgrees("nothing");
    }

    @Test
    public void shiftHeld() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);

        assertEveryReadSiteAgrees("shift held");
        assertMultifunctionAltCheckAgrees("shift held");
    }

    @Test
    public void shiftSticky() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);

        assertTrue("a tapped shift is a manual shift",
                tracker.getModifierState().isManualShiftPending());
        assertEveryReadSiteAgrees("shift sticky");
        assertMultifunctionAltCheckAgrees("shift sticky");
    }

    @Test
    public void shiftLocked() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);

        assertTrue("double-tapped shift is locked", tracker.getModifierState().isShiftLocked());
        assertEveryReadSiteAgrees("shift locked");
        assertMultifunctionAltCheckAgrees("shift locked");
    }

    @Test
    public void shiftLockedThenHeldAgain() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        down(KeyEvent.KEYCODE_SHIFT_LEFT);

        assertEveryReadSiteAgrees("shift locked then held");
        assertMultifunctionAltCheckAgrees("shift locked then held");
    }

    @Test
    public void altHeld() {
        down(KeyEvent.KEYCODE_ALT_LEFT);

        assertEveryReadSiteAgrees("alt held");
        assertMultifunctionAltCheckAgrees("alt held");
    }

    @Test
    public void altSticky() {
        tap(KeyEvent.KEYCODE_ALT_LEFT);

        assertEveryReadSiteAgrees("alt sticky");
        assertMultifunctionAltCheckAgrees("alt sticky");
    }

    @Test
    public void altSpentOnAKey() {
        down(KeyEvent.KEYCODE_ALT_LEFT);
        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);

        assertEveryReadSiteAgrees("alt used with key");
        assertMultifunctionAltCheckAgrees("alt used with key");
    }

    @Test
    public void symHeld() {
        down(KeyEvent.KEYCODE_SYM);

        assertTrue("the Sym key is held", tracker.isSymKeyHeld());
        assertEveryReadSiteAgrees("sym held");
        assertMultifunctionAltCheckAgrees("sym held");
    }

    @Test
    public void symReleased() {
        down(KeyEvent.KEYCODE_SYM);
        up(KeyEvent.KEYCODE_SYM);

        assertFalse("the Sym key is no longer held", tracker.isSymKeyHeld());
        assertEveryReadSiteAgrees("sym released");
    }

    @Test
    public void shiftAndAltTogether() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        down(KeyEvent.KEYCODE_ALT_LEFT);

        assertEveryReadSiteAgrees("shift and alt held");
        assertMultifunctionAltCheckAgrees("shift and alt held");
    }

    // ═══════════════════════════════════════════════ what 1f could not migrate, 1h did

    /**
     * <b>Was the pin on the one gap; now asserts agreement.</b> Phase 1f left three reads on the
     * tracker ({@code BlackBerryIME.isShiftChording()}, the first term of
     * {@code BlackBerryIME.isMetaKeyActive()}, and {@code InputLogic}'s Shift+Enter check) because
     * {@link ModifierState#isShiftReleased()} was {@code !shiftKeyDown} and so answered
     * <em>true</em> for a Shift key that is physically down but CONSUMED, where the tracker's
     * {@code isShiftReleased()} answers false.
     *
     * <p>Pressing Alt while Shift is held reaches that state. Phase 1h taught the snapshot about the
     * CONSUMED per-key state, and this test (which used to assert the disagreement) now asserts that
     * the tracker and the snapshot agree in it. {@code ShiftLifecycleTest} checks the same over the
     * whole Shift lifecycle.
     */
    @Test
    public void theTrackersShiftReleasedIsNowExpressedThroughModifierState() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        down(KeyEvent.KEYCODE_ALT_LEFT);

        ModifierState modifiers = tracker.getModifierState();
        assertFalse("the tracker says the Shift key is not released", tracker.isShiftReleased());
        assertFalse("...and not down either: it is CONSUMED", tracker.isShiftKeyDown());
        assertTrue("the tracker's consumed state", tracker.isShiftKeyConsumed());

        assertEquals("the snapshot agrees: not released",
                tracker.isShiftReleased(), modifiers.isShiftReleased());
        assertEquals("the snapshot agrees: not held",
                tracker.isShiftKeyDown(), modifiers.isShiftHeld());
        assertTrue("the snapshot names the consumed state", modifiers.isShiftConsumed());

        // ...and so the manual-shift read no longer flips.
        assertFalse("the tracker: no manual shift is pending",
                tracker.isManualShiftAndShiftPressing());
        assertEquals("the snapshot agrees",
                tracker.isManualShiftAndShiftPressing(), modifiers.isManualShiftPending());
        assertEveryReadSiteAgrees("shift consumed by alt");
    }

    // ═══════════════════════════════════════════════ the one deliberate divergence

    /**
     * <b>A narrow, deliberate behaviour change.</b> The multifunction-Ctrl remap skips itself while
     * Alt is active at press time, so Alt+multifunction still types the mapping's Alt character
     * (the '0' on the Key2 mic key). The old check spelled "Alt is active" as
     * {@code getInternalMetaState() & META_ALT_MASK}, and the span encoding makes that read
     * <em>false</em> for a LOCKED Alt: {@code getSpanMetaState} returns the locked bit (0x200) OR
     * the pressed bit (0x2), never both, and 0x200 is not in {@code META_ALT_MASK}. So with Alt
     * double-tap-locked — the state in which the user is most plainly asking for Alt characters —
     * the old check said Alt was off and the multifunction key became Ctrl anyway.
     *
     * <p>{@link ModifierState#isAltActiveForCharacter()} counts the locked bit, so it says Alt is
     * on and the key keeps its mapped character. That is what the comment on the remap always
     * described; the bit test did not implement it. The change is recorded here rather than worked
     * around, because reproducing the old answer would mean keeping the bit arithmetic at the call
     * site, which is the thing being removed.
     */
    @Test
    public void lockedAltIsTheOneCaseWhereTheMultifunctionAltCheckChanges() {
        tap(KeyEvent.KEYCODE_ALT_LEFT);
        tap(KeyEvent.KEYCODE_ALT_LEFT);
        assertTrue("alt is locked", tracker.getModifierState().isAltLocked());

        KeyEvent plain = event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1);
        boolean oldAltActive = plain.isAltPressed()
                || (tracker.getInternalMetaState() & KeyEvent.META_ALT_MASK) != 0;
        boolean newAltActive = tracker.getModifierState(plain).isAltActiveForCharacter();

        assertFalse("the old bit test did not see a locked Alt", oldAltActive);
        assertTrue("the named query does", newAltActive);
    }
}
