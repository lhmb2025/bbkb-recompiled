package dev.bbkb.ime.core.device.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
import org.robolectric.annotation.Config;

import java.util.Locale;

/**
 * Pins the held-key set behind {@link PhysicalKeyboardStateTracker#getNumberOfKeysDown()}, which
 * {@code FccController.showFcc()} requires to be zero. Key-downs and key-ups are not reliably paired
 * (untracked keys, key-up branches that return early, accessibility double dispatch), so a leaked
 * key must be discharged by its next press and release, and a stray key-up must not release another.
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
        DeviceInputResolver.resetConfig();
        KeyEventDeviceClassifier.getInstance().clearCache();
        tracker = new PhysicalKeyboardStateTracker(null);
    }

    @After
    public void tearDown() {
        DeviceInputResolver.resetConfig();
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

    /**
     * The accessibility all-keys callback and {@code onKeyDownInternal} both reach
     * {@code handleKeyDown} with the same event; that is still one held key.
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
     * A key-up consumed before the tracker is told (control-mode chord, Cangjie shift, Alt+Enter,
     * an untracked key) must be discharged by the next press and release of that key.
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

    /** An unmatched key-up is a no-op; it must not cancel another key's press. */
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

    /**
     * The PKB symbol board closes after one symbol unless Sym is held; {@code KeyboardState} asks
     * through {@code SwitcherCallbacks.isSymKeyHeld()}.
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

    /** Some ROMs map the Sym scancode to {@code KEYCODE_ALT_RIGHT}; the tracker uses the set code. */
    @Test
    public void aDeviceWhoseSymKeyReportsAnotherKeyCodeIsHeldByThatKeyCode() {
        // A modifier key code takes the tracker's modifier arm, which reads the long-press timeout
        // from settings.
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
