package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.method.MetaKeyKeyListener;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
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
 * Pins that each {@link ModifierState} query used at a read site answers the same as the raw tracker
 * expression it stands for, over every modifier state the sites can be in. Lives in the tracker's
 * package because the per-key Shift predicates it compares against are package-private. One
 * deliberate exception: {@link #lockedAltIsTheOneCaseWhereTheMultifunctionAltCheckChanges()}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ModifierReadSiteEquivalenceTest {
    /** 256, the flag {@code isMetaKeyActive()} passes to {@code hasMetaFlag}. */
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
        DeviceInputResolver.resetConfig();
        KeyEventDeviceClassifier.getInstance().clearCache();
        tracker = new PhysicalKeyboardStateTracker(null);
        tracker.setSymKeyCode(KeyEvent.KEYCODE_SYM);
    }

    @After
    public void tearDown() {
        DeviceInputResolver.resetConfig();
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

    /** Asserts every read-site query matches its raw tracker expression in the current state. */
    private void assertEveryReadSiteAgrees(String state) {
        ModifierState modifiers = tracker.getModifierState();

        // BlackBerryIME.onUpdateSelection: isShiftKeyDown() -> isShiftHeld()
        assertEquals(state + ": isShiftKeyDown/isShiftHeld",
                tracker.isShiftKeyDown(), modifiers.isShiftHeld());

        // BlackBerryIME.isMetaKeyActive() and isShiftChording()
        assertEquals(state + ": isShiftReleased",
                tracker.isShiftReleased(), modifiers.isShiftReleased());
        assertEquals(state + ": hasMetaFlag(META_CAP_LOCKED)/isShiftLockedForLayout",
                tracker.hasMetaFlag(META_CAP_LOCKED), modifiers.isShiftLockedForLayout());
        assertEquals(state + ": isAltUsedWithKey",
                tracker.isAltUsedWithKey(), modifiers.isAltUsedWithKey());
        // The same three terms together, as isMetaKeyActive() asks them on every touch sample:
        // the tracker's answer without a snapshot against the snapshot's.
        assertEquals(state + ": isShiftHeldAndLockedForLayout",
                !modifiers.isShiftReleased() && modifiers.isShiftLockedForLayout()
                        && !modifiers.isAltUsedWithKey(),
                tracker.isShiftHeldAndLockedForLayout());

        // InputLogic: isManualShiftAndShiftPressing() -> isManualShiftPending()
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

    /**
     * A Shift key physically down but CONSUMED (Alt pressed while Shift held) is neither released
     * nor held; the snapshot must agree with the tracker there, not treat it as {@code !shiftKeyDown}.
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

        assertFalse("the tracker: no manual shift is pending",
                tracker.isManualShiftAndShiftPressing());
        assertEquals("the snapshot agrees",
                tracker.isManualShiftAndShiftPressing(), modifiers.isManualShiftPending());
        assertEveryReadSiteAgrees("shift consumed by alt");
    }

    /**
     * The multifunction-Ctrl remap skips itself while Alt is active (so Alt+mic types '0'). The raw
     * {@code getInternalMetaState() & META_ALT_MASK} misses a LOCKED Alt: the span state holds the
     * locked bit (0x200) or the pressed bit (0x2), never both, and 0x200 is outside the mask.
     * {@link ModifierState#isAltActiveForCharacter()} counts the locked bit, which is intended.
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
