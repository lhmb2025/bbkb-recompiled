package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertEquals;

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
 * <b>Characterisation</b> of the physical Shift key's lifecycle inside
 * {@link PhysicalKeyboardStateTracker}, and (Phase 1h) the proof that the {@code ModifierState}
 * snapshot now answers the same in every state.
 *
 * <h3>Two pieces of state, not one</h3>
 * <ul>
 *   <li><b>Per-key state</b> ({@code mShiftState[]}, one slot for each Shift key):
 *       DOWN (−1), RELEASED (−2) or CONSUMED (−3). A key goes DOWN on press; it goes CONSUMED
 *       when something else happens while it is down (another key is already held when it is
 *       pressed, another modifier or the other Shift is pressed while it is held, or a character
 *       is typed with it); it goes RELEASED on key-up or a full reset.</li>
 *   <li><b>The Shift span</b> (the span tracker's {@code SHIFT_SPAN}): none, PRESSED, STICKY,
 *       USED or LOCKED. PRESSED and STICKY read as {@code META_SHIFT_ON} (0x1), LOCKED as 0x100,
 *       and USED as <em>nothing</em>.</li>
 * </ul>
 *
 * The tracker's three per-key predicates are exact per-key tests: {@code isShiftKeyDown()} is "a
 * key is DOWN", {@code isShiftKeyConsumed()} is "a key is CONSUMED", and
 * {@code isShiftReleased()} is "<em>both</em> keys are RELEASED". So a Shift that is physically
 * held but CONSUMED is neither down nor released. Before Phase 1h the snapshot's
 * {@code isShiftReleased()} was {@code !shiftKeyDown}, which is true there.
 *
 * <p>Every row below was first run green against the unmodified tracker (commit e6813111).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShiftLifecycleTest {

    private static final int SHIFT_L = KeyEvent.KEYCODE_SHIFT_LEFT;
    private static final int SHIFT_R = KeyEvent.KEYCODE_SHIFT_RIGHT;
    private static final int ALT_L = KeyEvent.KEYCODE_ALT_LEFT;
    private static final int KEY_A = KeyEvent.KEYCODE_A;
    private static final int KEY_B = KeyEvent.KEYCODE_B;

    /** 256, what {@code BlackBerryIME.isMetaKeyActive()} originally passed to {@code hasMetaFlag}. */
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
    }

    @After
    public void tearDown() {
        ScancodeMappingResolver.getInstance().reset();
        KeyEventDeviceClassifier.getInstance().clearCache();
        DeviceProfile.initialize(null);
    }

    // ─────────────────────────────────────────────────────────────── key driving

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

    /** A character key typed on the physical keyboard, as {@code KeyEventProcessor} reports it. */
    private void typeLetter(int keyCode) {
        down(keyCode);
        tracker.consumeModifiersAfterKey(keyCode, /* isPhysicalKeyboard */ true);
    }

    // ─────────────────────────────────────────────────────────────── the table

    /** Expected tracker answers for one row of the lifecycle table. */
    private static final class Row {
        final String name;
        final boolean keyDown;
        final boolean released;
        final boolean consumed;
        final int shiftSpan;       // getModifierKeyMetaState() & (0x1 | 0x100)
        final boolean manualShift; // isManualShiftAndShiftPressing()
        final boolean altUsedWithKey;

        Row(String name, boolean keyDown, boolean released, boolean consumed, int shiftSpan,
                boolean manualShift, boolean altUsedWithKey) {
            this.name = name;
            this.keyDown = keyDown;
            this.released = released;
            this.consumed = consumed;
            this.shiftSpan = shiftSpan;
            this.manualShift = manualShift;
            this.altUsedWithKey = altUsedWithKey;
        }
    }

    private static final boolean T = true;
    private static final boolean F = false;

    private void assertTracker(Row row) {
        assertEquals(row.name + ": isShiftKeyDown", row.keyDown, tracker.isShiftKeyDown());
        assertEquals(row.name + ": isShiftReleased", row.released, tracker.isShiftReleased());
        assertEquals(row.name + ": isShiftKeyConsumed", row.consumed, tracker.isShiftKeyConsumed());
        assertEquals(row.name + ": shift span", row.shiftSpan,
                tracker.getModifierKeyMetaState() & (KeyEvent.META_SHIFT_ON | 0x100));
        assertEquals(row.name + ": isManualShiftAndShiftPressing", row.manualShift,
                tracker.isManualShiftAndShiftPressing());
        assertEquals(row.name + ": isAltUsedWithKey", row.altUsedWithKey,
                tracker.isAltUsedWithKey());
    }

    /**
     * Phase 1h: the snapshot answers every Shift question the way the tracker does, in this row.
     * Also the three reads 1f had to leave on the tracker, spelled both ways.
     */
    private void assertSnapshotAgrees(Row row) {
        ModifierState m = tracker.getModifierState();
        assertEquals(row.name + ": held", tracker.isShiftKeyDown(), m.isShiftHeld());
        assertEquals(row.name + ": released", tracker.isShiftReleased(), m.isShiftReleased());
        assertEquals(row.name + ": consumed", tracker.isShiftKeyConsumed(), m.isShiftConsumed());
        assertEquals(row.name + ": manual shift",
                tracker.isManualShiftAndShiftPressing(), m.isManualShiftPending());
        assertEquals(row.name + ": alt used with key",
                tracker.isAltUsedWithKey(), m.isAltUsedWithKey());

        // BlackBerryIME.isMetaKeyActive(), old spelling vs new.
        boolean oldMetaKeyActive = !tracker.isShiftReleased()
                && tracker.hasMetaFlag(META_CAP_LOCKED) && !tracker.isAltUsedWithKey();
        boolean newMetaKeyActive = !m.isShiftReleased()
                && m.isShiftLockedForLayout() && !m.isAltUsedWithKey();
        assertEquals(row.name + ": isMetaKeyActive", oldMetaKeyActive, newMetaKeyActive);
        // BlackBerryIME.isShiftChording()'s tracker half (the other half is the on-screen Shift).
        assertEquals(row.name + ": isShiftChording's tracker half",
                tracker.isShiftReleased(), m.isShiftReleased());

        // The derived Shift answers are consistent with the per-key state: a Shift is sticky only
        // once every key is released, and a consumed Shift is not sticky even though the span
        // still says Shift (Shift+Alt).
        boolean spanShift = (row.shiftSpan & KeyEvent.META_SHIFT_ON) != 0;
        assertEquals(row.name + ": sticky", row.released && spanShift, m.isShiftSticky());
        assertEquals(row.name + ": locked", row.shiftSpan == 0x100, m.isShiftLocked());
        assertEquals(row.name + ": active", row.keyDown || row.shiftSpan != 0, m.isShiftActive());
    }

    /** Pins the tracker's row, then checks the snapshot against it. */
    private void check(Row row) {
        assertTracker(row);
        assertSnapshotAgrees(row);
    }

    // ── the brief's seven states ─────────────────────────────────────────────────

    @Test
    public void r01_idle() {
        check(new Row("idle", F, T, F, 0, F, F));
    }

    @Test
    public void r02_held() {
        down(SHIFT_L);
        check(new Row("held", T, F, F, 0x1, F, F));
    }

    @Test
    public void r03_sticky() {
        tap(SHIFT_L);
        check(new Row("sticky", F, T, F, 0x1, T, F));
    }

    @Test
    public void r04_locked() {
        tap(SHIFT_L);
        tap(SHIFT_L);
        check(new Row("locked", F, T, F, 0x100, F, F));
    }

    @Test
    public void r05_consumedByAlt() {
        down(SHIFT_L);
        down(ALT_L);
        check(new Row("consumed by Alt", F, F, T, 0x1, F, F));
    }

    @Test
    public void r06_consumedByLetter() {
        down(SHIFT_L);
        typeLetter(KEY_A);
        // The span goes PRESSED -> USED, which reads as nothing; the tracker also records the
        // chord in its (misnamed) "Alt used with key" flag, which is set whenever a key is typed
        // while any Shift key is not RELEASED.
        check(new Row("consumed by letter", F, F, T, 0, F, T));
    }

    @Test
    public void r07_releasedAfterConsumedByLetter() {
        down(SHIFT_L);
        typeLetter(KEY_A);
        up(KEY_A);
        up(SHIFT_L);
        check(new Row("released after letter", F, T, F, 0, F, F));
    }

    // ── the rest of the lifecycle ────────────────────────────────────────────────

    @Test
    public void r08_lockedThenPressedAgainUnlocksWhileHeld() {
        tap(SHIFT_L);
        tap(SHIFT_L);
        down(SHIFT_L);
        check(new Row("locked, pressed again", T, F, F, 0, F, F));
    }

    @Test
    public void r09_lockedThenTappedAgainIsIdle() {
        tap(SHIFT_L);
        tap(SHIFT_L);
        tap(SHIFT_L);
        check(new Row("locked, tapped again", F, T, F, 0, F, F));
    }

    @Test
    public void r10_consumedByAltStaysConsumedAfterAltIsReleased() {
        down(SHIFT_L);
        down(ALT_L);
        up(ALT_L);
        check(new Row("consumed by Alt, Alt up", F, F, T, 0x1, F, F));
    }

    @Test
    public void r11_releasingAShiftConsumedByAltLeavesItSticky() {
        down(SHIFT_L);
        down(ALT_L);
        up(SHIFT_L);
        check(new Row("consumed by Alt, Shift up", F, T, F, 0x1, T, F));
    }

    @Test
    public void r12_aSecondLetterKeepsItConsumed() {
        down(SHIFT_L);
        typeLetter(KEY_A);
        up(KEY_A);
        typeLetter(KEY_B);
        check(new Row("consumed, second letter", F, F, T, 0, F, T));
    }

    @Test
    public void r13_consumedByAltThenALetter() {
        down(SHIFT_L);
        down(ALT_L);
        typeLetter(KEY_A);
        check(new Row("consumed by Alt, then letter", F, F, T, 0, F, T));
    }

    @Test
    public void r14_shiftPressedWhileALetterIsHeldIsConsumedAtOnce() {
        down(KEY_A);
        down(SHIFT_L);
        check(new Row("pressed over a held letter", F, F, T, 0x1, F, F));
    }

    @Test
    public void r15_bothShiftKeysConsumeEachOther() {
        down(SHIFT_L);
        down(SHIFT_R);
        check(new Row("both Shift keys", F, F, T, 0x1, F, F));
    }

    @Test
    public void r16_stickyShiftThenAltHeld() {
        tap(SHIFT_L);
        down(ALT_L);
        check(new Row("sticky, then Alt held", F, T, F, 0x1, T, F));
    }

    @Test
    public void r17_stickyShiftSpentOnALetter() {
        tap(SHIFT_L);
        typeLetter(KEY_A);
        check(new Row("sticky, spent", F, T, F, 0, F, F));
    }

    @Test
    public void r18_lockedShiftSurvivesALetter() {
        tap(SHIFT_L);
        tap(SHIFT_L);
        typeLetter(KEY_A);
        check(new Row("locked, letter", F, T, F, 0x100, F, F));
    }

    @Test
    public void r19_altOnlyResetLeavesAConsumedShiftConsumed() {
        down(SHIFT_L);
        down(ALT_L);
        tracker.resetAltStateAndNotify();
        check(new Row("consumed, Alt-only reset", F, F, T, 0x1, F, F));
    }

    @Test
    public void r20_manualShiftResetIsANoOpOnAConsumedShift() {
        down(SHIFT_L);
        down(ALT_L);
        tracker.clearManualShift();
        check(new Row("consumed, manual-shift reset", F, F, T, 0x1, F, F));
    }

    @Test
    public void r21_fullResetReturnsAConsumedShiftToIdle() {
        down(SHIFT_L);
        down(ALT_L);
        tracker.resetAllMetaState();
        check(new Row("consumed, full reset", F, T, F, 0, F, F));
    }
}
