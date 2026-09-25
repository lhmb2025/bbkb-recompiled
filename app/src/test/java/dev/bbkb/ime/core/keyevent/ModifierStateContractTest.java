package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.core.app.ApplicationProvider;

import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceCapabilities;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The three things Phase 1b promised on top of the query API itself, driven through the real
 * {@code PhysicalKeyboardStateTracker} rather than a hand-built snapshot:
 *
 * <ol>
 *   <li><b>One query API.</b> Real Shift/Alt presses produce the right
 *       held / sticky / locked answers from {@link ModifierState}.</li>
 *   <li><b>One feed.</b> Every modifier transition arrives at a single
 *       {@link ModifierStateListener}, and only there. The status bar is one consumer of that
 *       feed, not a second owner of the state.</li>
 *   <li><b>One reset contract.</b> {@link ModifierResetReason} says what each reset clears, and
 *       every reset entry goes through it.</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ModifierStateContractTest {

    /** Records the single feed. */
    private static final class Recorder implements ModifierStateListener {
        final List<ModifierState> states = new ArrayList<>();
        final List<Boolean> forced = new ArrayList<>();

        @Override
        public void onModifierStateChanged(ModifierState state, boolean force) {
            states.add(state);
            forced.add(force);
        }

        ModifierState last() {
            assertFalse("expected at least one notification", states.isEmpty());
            return states.get(states.size() - 1);
        }

        boolean lastWasForced() {
            return forced.get(forced.size() - 1);
        }

        void clear() {
            states.clear();
            forced.clear();
        }
    }

    private PhysicalKeyboardStateTracker tracker;
    private Recorder recorder;

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
        recorder = new Recorder();
        tracker.setModifierStateListener(recorder);
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

    // ==================== one query API, over real key presses ====================

    @Test
    public void aHeldShiftReadsAsHeld() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);

        ModifierState state = tracker.getModifierState();
        assertTrue(state.isShiftHeld());
        assertTrue(state.isShiftActive());
        assertFalse(state.isShiftSticky());
        assertFalse(state.isShiftReleased());
        assertTrue("and the key is in the held-key set", state.isAnyKeyHeld());
    }

    @Test
    public void aTappedShiftReadsAsSticky() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);

        ModifierState state = tracker.getModifierState();
        assertTrue(state.isShiftSticky());
        assertTrue(state.isShiftActive());
        assertFalse(state.isShiftHeld());
        assertTrue("a sticky shift is a manual shift", state.isManualShiftPending());
    }

    @Test
    public void aDoubleTappedAltReadsAsLocked() {
        tap(KeyEvent.KEYCODE_ALT_LEFT);
        tap(KeyEvent.KEYCODE_ALT_LEFT);

        ModifierState state = tracker.getModifierState();
        assertTrue(state.isAltLocked());
        assertTrue(state.isAltActive());
        assertFalse(state.isAltSticky());
    }

    @Test
    public void aHeldAltReadsAsHeldByBothWitnesses() {
        down(KeyEvent.KEYCODE_ALT_LEFT);

        ModifierState state = tracker.getModifierState();
        assertTrue(state.isAltHeld());
        assertTrue(state.isAltHeldBySpan());
        assertTrue(state.isAltKeyDown());
    }

    /** The character that spends a sticky modifier takes it out of the answer too. */
    @Test
    public void aCharacterSpendsAStickyShift() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertTrue(tracker.getModifierState().isShiftActive());

        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);

        assertFalse(tracker.getModifierState().isShiftActive());
    }

    @Test
    public void theSymKeyIsHeldWhileItIsDown() {
        tracker.setSymKeyCode(KeyEvent.KEYCODE_SYM);

        down(KeyEvent.KEYCODE_SYM);
        assertTrue(tracker.getModifierState().isSymHeld());

        up(KeyEvent.KEYCODE_SYM);
        assertFalse(tracker.getModifierState().isSymHeld());
    }

    /** Ctrl is answered by its one owner, through the supplier — the tracker keeps no copy. */
    @Test
    public void ctrlIsAnsweredByTheSuppliedOwner() {
        assertFalse("no source wired: Ctrl is simply not active",
                tracker.getModifierState().isCtrlActive());

        final boolean[] ctrl = {false};
        tracker.setCtrlStateSource(() -> ctrl[0]);
        assertFalse(tracker.getModifierState().isCtrlActive());

        ctrl[0] = true;
        assertTrue(tracker.getModifierState().isCtrlActive());
    }

    /** An event's own meta state is merged in for the overloads that take one. */
    @Test
    public void anEventsMetaStateIsMergedIntoTheAnswer() {
        KeyEvent altHeld = new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0,
                KeyEvent.META_ALT_ON, 0, 0, 0, InputDevice.SOURCE_KEYBOARD);

        assertFalse(tracker.getModifierState().isAltActiveForChord());
        assertTrue(tracker.getModifierState(altHeld).isAltActiveForChord());
        assertTrue(tracker.getModifierState(KeyEvent.META_ALT_ON).isAltActiveForChord());
    }

    /**
     * The chord/character split, through the real tracker: installing the symbol board's Alt page
     * as the keyboard's meta mask puts Alt in the interpreted state and must leave the chord
     * state alone. This is the Sym&rarr;emoji regression, asked through the new API.
     */
    @Test
    public void theSymbolBoardAltPageDoesNotFakeAChord() {
        tracker.updateFilterAndAltGr(0, new KeyCharacterInterpreter.MetaMask(KeyEvent.META_ALT_ON, 0));

        ModifierState state = tracker.getModifierState();
        assertTrue("the page's mask is in the interpreted state",
                (state.getInterpretedMetaState() & KeyEvent.META_ALT_ON) != 0);
        assertTrue(state.isAltActiveForCharacter());
        assertFalse("but no modifier KEY is down", state.isAltActiveForChord());
        assertEquals(0, state.getModifierKeyMetaState() & ModifierState.ALT_ANY_MASK);
    }

    /**
     * The symbol-page question, which {@code getSymbolPageOrder()} still answers from the
     * tracker's own fields because it runs several times per keystroke and a snapshot per call
     * would be an allocation per call. The API answers the same question, and these pin that the
     * two agree — so the remaining call sites can be moved when their files are opened.
     */
    @Test
    public void theApiAgreesWithGetSymbolPageOrder() {
        assertEquals(0, tracker.getSymbolPageOrder());
        assertFalse(tracker.getModifierState().isShiftActiveForLayout());
        assertFalse(tracker.getModifierState().isShiftLockedForLayout());

        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals("a sticky shift is the shifted page", 1, tracker.getSymbolPageOrder());
        assertTrue(tracker.getModifierState().isShiftActiveForLayout());

        tap(KeyEvent.KEYCODE_SHIFT_LEFT); // double tap locks
        assertEquals("a locked shift is the caps page", 3, tracker.getSymbolPageOrder());
        assertTrue(tracker.getModifierState().isShiftLockedForLayout());
        assertFalse(tracker.getModifierState().isShiftActiveForLayout());
    }

    /** And the manual-shift question, which the commit path asks. */
    @Test
    public void theApiAgreesWithIsManualShiftAndShiftPressing() {
        assertEquals(tracker.isManualShiftAndShiftPressing(),
                tracker.getModifierState().isManualShiftPending());

        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertTrue(tracker.isManualShiftAndShiftPressing());
        assertEquals(tracker.isManualShiftAndShiftPressing(),
                tracker.getModifierState().isManualShiftPending());

        down(KeyEvent.KEYCODE_SHIFT_LEFT); // held, not manual
        assertEquals(tracker.isManualShiftAndShiftPressing(),
                tracker.getModifierState().isManualShiftPending());
    }

    // ==================== one feed ====================

    @Test
    public void everyModifierTransitionReachesTheOneListener() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals(1, recorder.states.size());
        assertTrue(recorder.last().isShiftHeld());

        up(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertEquals(2, recorder.states.size());
        assertTrue(recorder.last().isShiftSticky());

        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true);
        assertEquals(3, recorder.states.size());
        assertFalse(recorder.last().isShiftActive());
    }

    /** An ordinary character key is not a modifier transition and posts nothing. */
    @Test
    public void aPlainCharacterKeyDownPostsNothing() {
        down(KeyEvent.KEYCODE_A);

        assertTrue(recorder.states.isEmpty());
    }

    /** The re-assert path: same state, posted anyway, because the system owns the slot. */
    @Test
    public void refreshRepublishesTheSameStateWithForce() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        recorder.clear();

        tracker.refreshModifierStatus();

        assertEquals(1, recorder.states.size());
        assertTrue("re-assert must be forced", recorder.lastWasForced());
        assertTrue(recorder.last().isShiftHeld());
    }

    @Test
    public void anOrdinaryTransitionIsNotForced() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);

        assertFalse(recorder.lastWasForced());
    }

    /** The status bar is a listener on that feed, and nothing else makes it post. */
    @Test
    public void theStatusBarUpdaterIsJustAListenerOnTheFeed() {
        assertTrue(ModifierStateListener.class.isAssignableFrom(ModifierStatusBarUpdater.class));
    }

    // ==================== one reset contract ====================

    @Test
    public void aFullResetClearsEverythingAndForcesAPost() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT);
        down(KeyEvent.KEYCODE_ALT_LEFT);
        recorder.clear();

        tracker.resetModifiers(ModifierResetReason.WINDOW_HIDDEN);

        ModifierState state = tracker.getModifierState();
        assertFalse(state.isShiftActive());
        assertFalse(state.isAltActive());
        assertFalse("the held-key set goes too", state.isAnyKeyHeld());
        assertEquals(0, state.getInterpretedMetaState());
        assertEquals(1, recorder.states.size());
        assertTrue(recorder.lastWasForced());
    }

    /** Every ALL-scoped reason does the same thing; they differ only in what they document. */
    @Test
    public void allTheFullResetReasonsClearTheSameState() {
        for (ModifierResetReason reason : ModifierResetReason.values()) {
            if (reason.scope() != ModifierResetReason.Scope.ALL) {
                continue;
            }
            down(KeyEvent.KEYCODE_ALT_LEFT);
            assertTrue(reason.name(), tracker.getModifierState().isAltActive());

            tracker.resetModifiers(reason);

            assertFalse(reason.name(), tracker.getModifierState().hasAnyModifier());
        }
    }

    /** The Alt-only reset leaves Shift and the held-key set alone. */
    @Test
    public void theAltPageResetClearsOnlyAlt() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        tap(KeyEvent.KEYCODE_ALT_LEFT);
        down(KeyEvent.KEYCODE_SYM);
        recorder.clear();

        tracker.resetModifiers(ModifierResetReason.ALT_PAGE_LEFT);

        ModifierState state = tracker.getModifierState();
        assertFalse("alt is gone", state.isAltActive());
        assertTrue("shift survives", state.isShiftActive());
        assertTrue("so does the held-key set", state.isAnyKeyHeld());
        assertEquals(1, recorder.states.size());
        assertFalse("a partial reset is an ordinary transition", recorder.lastWasForced());
    }

    /** The legacy entry point is the same reset. */
    @Test
    public void resetAltStateAndNotifyIsTheAltPageReset() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        tap(KeyEvent.KEYCODE_ALT_LEFT);

        tracker.resetAltStateAndNotify();

        assertFalse(tracker.getModifierState().isAltActive());
        assertTrue(tracker.getModifierState().isShiftActive());
    }

    /** The manual-shift reset only fires while a manual shift is actually pending. */
    @Test
    public void theManualShiftResetClearsOnlyAPendingManualShift() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);
        assertTrue(tracker.getModifierState().isManualShiftPending());
        recorder.clear();

        tracker.resetModifiers(ModifierResetReason.MANUAL_SHIFT_SPENT);

        assertFalse(tracker.getModifierState().isShiftActive());
        assertEquals(1, recorder.states.size());
    }

    @Test
    public void theManualShiftResetPostsNothingWhenNoManualShiftIsPending() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT); // held, not manual
        recorder.clear();

        tracker.resetModifiers(ModifierResetReason.MANUAL_SHIFT_SPENT);

        assertTrue("a held shift is untouched", tracker.getModifierState().isShiftHeld());
        assertTrue("and nothing is posted", recorder.states.isEmpty());
    }

    @Test
    public void clearManualShiftIsTheManualShiftReset() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT);

        tracker.clearManualShift();

        assertFalse(tracker.getModifierState().isShiftActive());
    }

    @Test
    public void resetAllMetaStateIsStillTheFullReset() {
        down(KeyEvent.KEYCODE_ALT_LEFT);

        tracker.resetAllMetaState();

        assertFalse(tracker.getModifierState().hasAnyModifier());
    }

    @Test
    public void everyResetReasonDeclaresAScope() {
        for (ModifierResetReason reason : ModifierResetReason.values()) {
            assertNotNull(reason.name(), reason.scope());
        }
    }
}
