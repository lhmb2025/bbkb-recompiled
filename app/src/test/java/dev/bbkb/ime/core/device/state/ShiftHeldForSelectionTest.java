package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Locale;

/**
 * Pins when a held Shift makes keypad swipes select: from a swipe or a cursor-mode move made under
 * it, never once a key has been typed under it, and never left over for the next press. Neither
 * the swipe nor a long hold locks Shift.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShiftHeldForSelectionTest {

    private PhysicalKeyboardStateTracker tracker;
    private int longPressMs;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ true,
                /* isBlackBerryDevice */ false, "qwerty", "4row"));
        DeviceInputResolver.resetConfig();
        KeyEventDeviceClassifier.getInstance().clearCache();
        tracker = new PhysicalKeyboardStateTracker(null);
        tracker.setSymKeyCode(KeyEvent.KEYCODE_SYM);
        longPressMs = SettingsManager.getInstance().getSettingsValues().shiftKeyLongPressTimeout;
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

    /** A letter typed the way the key pipeline reports it: down, modifiers spent, up. */
    private void type(int keyCode) {
        down(keyCode);
        tracker.consumeModifiersAfterKey(keyCode, true);
        up(keyCode);
    }

    private void waitMs(int ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void aSwipeUnderAHeldShift_selects_withoutLockingShift() {
        assertFalse(tracker.canHoldShiftForSelection());
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertTrue(tracker.canHoldShiftForSelection());
        assertFalse("holding alone selects nothing", tracker.isShiftHeldForSelection());
        tracker.holdShiftForSelection();
        assertTrue(tracker.isShiftHeldForSelection());
        assertFalse("the swipe must not lock Shift", tracker.getModifierState().isShiftLocked());
    }

    @Test
    public void aLongHold_neitherSelectsNorLocks() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        waitMs(longPressMs + 200);
        assertFalse(tracker.isShiftHeldForSelection());
        assertFalse(tracker.getModifierState().isShiftLocked());
        assertTrue(tracker.canHoldShiftForSelection());
    }

    @Test
    public void lettingGo_endsIt_andTheNextPressStartsOver() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        tracker.holdShiftForSelection();
        up(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertFalse(tracker.isShiftHeldForSelection());
        assertFalse(tracker.canHoldShiftForSelection());
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertFalse(tracker.isShiftHeldForSelection());
    }

    @Test
    public void aLetterTypedUnderShift_keepsTheSwipeOff_untilShiftIsLetGo() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        type(KeyEvent.KEYCODE_A);
        assertFalse(tracker.canHoldShiftForSelection());
        up(KeyEvent.KEYCODE_SHIFT_LEFT);
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertTrue(tracker.canHoldShiftForSelection());
    }

    @Test
    public void aLetterHeldWithShift_keepsTheSwipeOff() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        down(KeyEvent.KEYCODE_A);
        assertFalse(tracker.canHoldShiftForSelection());
    }

    @Test
    public void aLetterTypedAfterSelecting_turnsItOff() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        tracker.holdShiftForSelection();
        type(KeyEvent.KEYCODE_A);
        assertFalse(tracker.isShiftHeldForSelection());
    }

    @Test
    public void aSelectionStep_keepsSelecting_thoughItSpendsShift() {
        tracker.holdShiftForSelection();
        assertFalse(tracker.isShiftHeldForSelection());
        down(KeyEvent.KEYCODE_SHIFT_RIGHT);
        tracker.holdShiftForSelection();
        // What the editor's selection update does while Shift is down.
        tracker.consumeModifiersAfterKey(0, true);
        assertTrue(tracker.isShiftHeldForSelection());
        up(KeyEvent.KEYCODE_SHIFT_RIGHT);
        assertFalse(tracker.isShiftHeldForSelection());
    }
}
