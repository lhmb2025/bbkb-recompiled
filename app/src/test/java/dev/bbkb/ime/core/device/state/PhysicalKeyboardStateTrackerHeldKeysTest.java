package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
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
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * The held-key accounting behind {@link PhysicalKeyboardStateTracker#getNumberOfKeysDown()}.
 *
 * <p>That number is not bookkeeping for its own sake: it is the one extra precondition
 * {@code FccController.showFcc()} carries. While it reads non-zero the fine-cursor-control board
 * refuses to open, silently, from every entry point — the UIM bar icon included. So any way the
 * number can be wrong in the positive direction is a way for FCC to be dead on a real device until
 * the input connection restarts, which is the owner-reported "FCC sometimes does not open when
 * tapping on its icon".
 *
 * <p>It used to be a bare counter incremented on key-down and decremented on key-up, and the two
 * halves are not reliably paired in production:
 *
 * <ul>
 *   <li>{@code KeyEventProcessor.onKeyDownInternal} increments for every physical key-down it
 *       reaches, but the matching decrement in {@code onKeyUpInternal} runs only inside
 *       {@code if (isKeyTracked(event))} — and a key-down whose converted {@code InputEvent}
 *       carries no data is deliberately left untracked.</li>
 *   <li>Several key-up branches return before the tracker is told anything at all: a control-mode
 *       chord ({@code ControlModeController.handleHardKeyUp}, which is consulted on the up path
 *       without the {@code isInputActive && isInputViewShown} gate its down twin has), the Cangjie
 *       shift branch, the Alt+Enter branch.</li>
 *   <li>The accessibility all-keys callback pre-processes keys through
 *       {@code processKeyEventForState}, which reaches the same {@code handleKeyDown}.</li>
 * </ul>
 *
 * <p>Each of those leaks left the counter permanently positive. A set of held key codes cannot be
 * permanently positive in the same way: the next press-and-release of the leaked key discharges
 * it. These tests pin that, and the negative controls pin that ordinary counting is unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PhysicalKeyboardStateTrackerHeldKeysTest {

    private PhysicalKeyboardStateTracker tracker;

    @Before
    public void setUp() {
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

    /** deviceId 0 on a profile with a physical keyboard is a physical event to the classifier. */
    private static KeyEvent event(int action, int keyCode, int repeat) {
        return new KeyEvent(0L, 0L, action, keyCode, repeat, /* metaState */ 0,
                /* deviceId */ 0, /* scanCode */ keyCode, /* flags */ 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    private void down(int keyCode) {
        tracker.handleKeyDown(keyCode, event(KeyEvent.ACTION_DOWN, keyCode, 0));
    }

    private void repeatDown(int keyCode, int repeat) {
        tracker.handleKeyDown(keyCode, event(KeyEvent.ACTION_DOWN, keyCode, repeat));
    }

    private void up(int keyCode) {
        tracker.handleKeyUp(keyCode, event(KeyEvent.ACTION_UP, keyCode, 0));
    }

    // ── negative controls: ordinary counting is unchanged ────────────────────

    @Test
    public void nothingIsHeldToBeginWith() {
        assertEquals(0, tracker.getNumberOfKeysDown());
        assertFalse(tracker.isKeyHeld(KeyEvent.KEYCODE_A));
    }

    @Test
    public void aPlainPressAndReleaseCountsOneThenZero() {
        down(KeyEvent.KEYCODE_A);
        assertEquals(1, tracker.getNumberOfKeysDown());
        assertTrue(tracker.isKeyHeld(KeyEvent.KEYCODE_A));

        up(KeyEvent.KEYCODE_A);
        assertEquals(0, tracker.getNumberOfKeysDown());
        assertFalse(tracker.isKeyHeld(KeyEvent.KEYCODE_A));
    }

    @Test
    public void twoDifferentKeysHeldAtOnceCountTwo() {
        down(KeyEvent.KEYCODE_A);
        down(KeyEvent.KEYCODE_B);

        assertEquals(2, tracker.getNumberOfKeysDown());

        up(KeyEvent.KEYCODE_A);
        assertEquals("releasing one of two leaves the other held",
                1, tracker.getNumberOfKeysDown());
        assertTrue(tracker.isKeyHeld(KeyEvent.KEYCODE_B));
    }

    @Test
    public void anAutoRepeatDownDoesNotCountAgain() {
        down(KeyEvent.KEYCODE_A);
        repeatDown(KeyEvent.KEYCODE_A, 1);
        repeatDown(KeyEvent.KEYCODE_A, 2);

        assertEquals(1, tracker.getNumberOfKeysDown());

        up(KeyEvent.KEYCODE_A);
        assertEquals("one release ends a held-and-repeating key",
                0, tracker.getNumberOfKeysDown());
    }

    @Test
    public void aNonPhysicalEventIsNotCountedAtAll() {
        KeyEvent soft = new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 0,
                /* deviceId */ -1, 0, 0, InputDevice.SOURCE_UNKNOWN);
        tracker.handleKeyDown(KeyEvent.KEYCODE_A, soft);

        assertEquals(0, tracker.getNumberOfKeysDown());
    }

    @Test
    public void resettingTheMetaStateReleasesEverything() {
        down(KeyEvent.KEYCODE_A);
        down(KeyEvent.KEYCODE_B);

        tracker.resetAllMetaState();

        assertEquals(0, tracker.getNumberOfKeysDown());
    }

    // ── the leaks the bare counter could not survive ─────────────────────────

    /**
     * The double-dispatch leak. The accessibility all-keys callback pre-processes a key through
     * {@code processKeyEventForState} and the IME's own {@code onKeyDownInternal} processes it
     * again; both reach {@code handleKeyDown} with the same event. The counter went to 2 and the
     * single key-up brought it back to 1 — FCC dead from then on. The same key held once is one
     * key held, however many times the press was reported.
     */
    @Test
    public void theSameKeyReportedDownTwiceIsStillOneHeldKey() {
        down(KeyEvent.KEYCODE_A);
        down(KeyEvent.KEYCODE_A);

        assertEquals("one key is held, not two", 1, tracker.getNumberOfKeysDown());

        up(KeyEvent.KEYCODE_A);

        assertEquals("and its single release ends it", 0, tracker.getNumberOfKeysDown());
    }

    /**
     * The swallowed-key-up leak, and the one that matters most: it is permanent under a counter.
     * A key-up consumed before the tracker is told (a control-mode chord release, the Cangjie
     * shift, Alt+Enter, a key released after {@code isKeyTracked} stopped answering) leaves the
     * key counted as held forever. Pressing and releasing that same key again must discharge it.
     */
    @Test
    public void aSwallowedKeyUpIsDischargedByTheNextPressOfTheSameKey() {
        down(KeyEvent.KEYCODE_A);
        // ...the key-up is consumed by a branch that returns before telling the tracker.

        down(KeyEvent.KEYCODE_A);
        up(KeyEvent.KEYCODE_A);

        assertEquals("a leaked key-down must not outlive the next release of that key",
                0, tracker.getNumberOfKeysDown());
    }

    /**
     * And the converse must not be used to paper over a real held key: an unmatched key-up is a
     * no-op, it does not cancel some <em>other</em> key's press. The old counter decremented
     * blindly, so a stray release reported FCC as safe to open while a key really was held.
     */
    @Test
    public void anUnmatchedKeyUpDoesNotReleaseSomeOtherKey() {
        down(KeyEvent.KEYCODE_A);

        up(KeyEvent.KEYCODE_B);

        assertEquals("A is still held", 1, tracker.getNumberOfKeysDown());
        assertTrue(tracker.isKeyHeld(KeyEvent.KEYCODE_A));
    }

    @Test
    public void anUnmatchedKeyUpOnItsOwnNeverGoesNegative() {
        up(KeyEvent.KEYCODE_A);

        assertEquals(0, tracker.getNumberOfKeysDown());
    }

    // ── the held Sym key ─────────────────────────────────────────────────────

    /**
     * The PKB symbol board closes itself after the symbol typed on it; holding Sym is what keeps
     * it open instead (owner decision, 2026-09-22, replacing the "Close symbol keyboard after
     * symbol" setting). {@code KeyboardState} asks this question through
     * {@code SwitcherCallbacks.isSymKeyHeld()}.
     */
    @Test
    public void theSymKeyIsHeldBetweenItsPressAndItsRelease() {
        assertFalse("nothing is held to begin with", tracker.isSymKeyHeld());

        down(KeyEvent.KEYCODE_SYM);
        assertTrue(tracker.isSymKeyHeld());

        up(KeyEvent.KEYCODE_SYM);
        assertFalse(tracker.isSymKeyHeld());
    }

    @Test
    public void anOrdinaryHeldKeyIsNotTheSymKey() {
        down(KeyEvent.KEYCODE_A);

        assertFalse(tracker.isSymKeyHeld());
    }

    /**
     * The MP01's ROM attaches {@code KEYCODE_ALT_RIGHT} to its Sym scancode, so the key-event
     * path names the key code it resolved rather than this class assuming {@code KEYCODE_SYM}.
     */
    @Test
    public void aDeviceWhoseSymKeyReportsAnotherKeyCodeIsHeldByThatKeyCode() {
        // KEYCODE_ALT_RIGHT is a modifier key code, so this is the one test here that takes the
        // tracker's modifier arm — which reads the long-press timeout out of the settings.
        Context context = ApplicationProvider.getApplicationContext();
        SettingsManager.initialize(context);
        SettingsManager.getInstance().loadSettings(context, Locale.US,
                new EditorCapabilities(null, false, context.getPackageName(), Locale.US, false));

        tracker.setSymKeyCode(KeyEvent.KEYCODE_ALT_RIGHT);

        down(KeyEvent.KEYCODE_ALT_RIGHT);
        assertTrue(tracker.isSymKeyHeld());

        up(KeyEvent.KEYCODE_ALT_RIGHT);
        assertFalse(tracker.isSymKeyHeld());
    }

    /** A leaked Sym press is discharged like any other, by the next release of that key. */
    @Test
    public void aSwallowedSymKeyUpDoesNotLeaveSymHeldForever() {
        down(KeyEvent.KEYCODE_SYM);
        // ...the release is consumed by a branch that returns before telling the tracker.

        down(KeyEvent.KEYCODE_SYM);
        up(KeyEvent.KEYCODE_SYM);

        assertFalse(tracker.isSymKeyHeld());
    }
}
