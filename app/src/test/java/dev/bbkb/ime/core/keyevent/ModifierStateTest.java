package dev.bbkb.ime.core.keyevent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.InputDevice;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link ModifierState}, the one modifier query API (Phase 1b).
 *
 * <p>These are the answers the six former state holders used to give in four different spellings.
 * The cases that matter most are the ones where the spellings disagreed:
 *
 * <ul>
 *   <li>the symbol board's Alt page, which is a {@link KeyCharacterInterpreter.MetaMask} adding
 *       {@code META_ALT_ON} to the interpreted state — right for typing an Alt-layer symbol, and
 *       the reason Sym paging opened the emoji board when a chord read it (KEY2, 2026-09-21);</li>
 *   <li>the two caps-lock bits, {@code 0x100}/{@code 0x800} in the span tracker's encoding and
 *       {@link KeyEvent#META_CAPS_LOCK_ON} (0x100000) in the KeyEvent encoding, which a deleted
 *       {@code MetaState.fromSpans} once silently mixed;</li>
 *   <li>"Alt is held", which has three witnesses that do not always agree.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ModifierStateTest {

    private static KeyEvent eventWithMeta(int metaState) {
        return new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, metaState,
                /* deviceId */ 0, /* scanCode */ 0, /* flags */ 0, InputDevice.SOURCE_KEYBOARD);
    }

    // ==================== nothing set ====================

    @Test
    public void anEmptyStateHasNoModifiers() {
        ModifierState state = ModifierState.none();

        assertFalse(state.isShiftActive());
        assertFalse(state.isAltActive());
        assertFalse(state.isCtrlActive());
        assertFalse(state.isSymActive());
        assertFalse(state.hasAnyModifier());
        assertFalse(state.isAnyKeyHeld());
        assertEquals(0, state.getChordMetaState());
    }

    // ==================== Shift: held / sticky / locked ====================

    @Test
    public void aHeldShiftIsHeldAndActiveButNeitherStickyNorLocked() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_SHIFT_ON).shiftKeyDown(true).build();

        assertTrue(state.isShiftHeld());
        assertTrue(state.isShiftActive());
        assertFalse(state.isShiftSticky());
        assertFalse(state.isShiftLocked());
        assertFalse(state.isShiftReleased());
    }

    @Test
    public void aTappedShiftIsStickyAndActiveButNotHeld() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_SHIFT_ON).shiftKeyDown(false).build();

        assertTrue(state.isShiftSticky());
        assertTrue(state.isShiftActive());
        assertFalse(state.isShiftHeld());
        assertTrue(state.isShiftReleased());
    }

    /**
     * Phase 1h: a Shift key still physically down after being chorded with Alt is CONSUMED. The
     * span still says Shift, so Shift is active, but the key is neither held (not DOWN) nor released,
     * it is not sticky until it is let go, and it is not a pending manual shift.
     */
    @Test
    public void aConsumedShiftIsActiveButNeitherHeldNorReleasedNorSticky() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON)
                .shiftKeyConsumed(true).build();

        assertTrue(state.isShiftConsumed());
        assertTrue(state.isShiftActive());
        assertFalse(state.isShiftHeld());
        assertFalse(state.isShiftReleased());
        assertFalse(state.isShiftSticky());
        assertFalse(state.isManualShiftPending());
    }

    /** Consumed by a typed letter: the span reads nothing, the key is still not released. */
    @Test
    public void aShiftSpentOnALetterWhileHeldIsStillNotReleased() {
        ModifierState state = ModifierState.builder().shiftKeyConsumed(true).build();

        assertTrue(state.isShiftConsumed());
        assertFalse(state.isShiftActive());
        assertFalse(state.isShiftReleased());
    }

    /** An event-only snapshot has no per-key state: never consumed, released unless told. */
    @Test
    public void anEventOnlySnapshotIsNeverConsumed() {
        ModifierState state = ModifierState.ofEventMeta(KeyEvent.META_SHIFT_ON);

        assertFalse(state.isShiftConsumed());
        assertTrue(state.isShiftHeld());
        assertTrue("released is per-key state only; the event is not consulted",
                state.isShiftReleased());
    }

    @Test
    public void aDoubleTappedShiftIsLockedAndActiveButNotSticky() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(ModifierState.SPAN_SHIFT_LOCKED).build();

        assertTrue(state.isShiftLocked());
        assertTrue(state.isShiftActive());
        assertFalse(state.isShiftSticky());
    }

    @Test
    public void theCapsLockSpanAlsoCountsAsShiftLocked() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(ModifierState.SPAN_CAPS_LOCK).build();

        assertTrue(state.isShiftLocked());
    }

    /**
     * The two encodings. {@code 0x100} is the span tracker's shift-lock; the event's own caps-lock
     * is {@code META_CAPS_LOCK_ON} (0x100000) and lives nowhere near it.
     */
    @Test
    public void theEventCapsLockBitIsADifferentBitFromTheSpanOne() {
        ModifierState fromEvent = ModifierState.ofEventMeta(KeyEvent.META_CAPS_LOCK_ON);
        assertTrue(fromEvent.isEventCapsLockOn());
        assertTrue(fromEvent.isShiftLocked());

        ModifierState fromSpan = ModifierState.builder()
                .modifierKeyMeta(ModifierState.SPAN_SHIFT_LOCKED).build();
        assertFalse("the span bit is not the event bit", fromSpan.isEventCapsLockOn());
        assertTrue(fromSpan.isShiftLocked());

        assertEquals(0x100, ModifierState.SPAN_SHIFT_LOCKED);
        assertEquals(0x100000, KeyEvent.META_CAPS_LOCK_ON);
    }

    /** A meta mask that filters Shift out takes it away from the layout, not from the key state. */
    @Test
    public void aMaskThatFiltersShiftOutHidesItFromTheLayoutOnly() {
        ModifierState masked = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_SHIFT_ON).shiftSurvivesMask(false).build();

        assertFalse(masked.isShiftActiveForLayout());
        assertTrue("the key state itself is untouched", masked.isShiftActive());

        ModifierState unmasked = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_SHIFT_ON).shiftSurvivesMask(true).build();
        assertTrue(unmasked.isShiftActiveForLayout());
        assertTrue("tapped and released is a manual shift", unmasked.isManualShiftPending());
    }

    // ==================== Alt: held / sticky / locked ====================

    @Test
    public void aHeldAltIsHeldAndActive() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_ALT_ON).altKeyDown(true).altSpanHeld(true).build();

        assertTrue(state.isAltHeld());
        assertTrue(state.isAltHeldBySpan());
        assertTrue(state.isAltActive());
        assertFalse(state.isAltSticky());
        assertFalse(state.isAltLocked());
    }

    @Test
    public void aTappedAltIsSticky() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_ALT_ON).build();

        assertTrue(state.isAltSticky());
        assertTrue(state.isAltActive());
        assertFalse(state.isAltHeld());
    }

    @Test
    public void aDoubleTappedAltIsLocked() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(ModifierState.SPAN_ALT_LOCKED).build();

        assertTrue(state.isAltLocked());
        assertTrue(state.isAltActive());
        assertFalse(state.isAltSticky());
    }

    /**
     * The three witnesses to "Alt is held" do not always agree, and the accessibility all-keys
     * callback keys on the narrowest of them: pressing Alt while it was already sticky clears the
     * span but still marks the key down.
     */
    @Test
    public void theSpanAndTheKeyStateAreSeparateWitnessesToAHeldAlt() {
        ModifierState keyDownOnly = ModifierState.builder().altKeyDown(true).build();
        assertTrue(keyDownOnly.isAltHeld());
        assertFalse("the span is the narrow question", keyDownOnly.isAltHeldBySpan());

        ModifierState eventOnly = ModifierState.ofEventMeta(KeyEvent.META_ALT_ON);
        assertTrue(eventOnly.isAltHeld());
        assertFalse(eventOnly.isAltHeldBySpan());
        assertFalse(eventOnly.isAltKeyDown());
    }

    // ==================== the chord / character split ====================

    /**
     * The 2026-09-21 regression, pinned on the API itself: the symbol board's Alt page adds
     * {@code META_ALT_ON} to the <em>interpreted</em> state. That is Alt for typing and not Alt
     * for chording, and {@link ModifierState} must answer the two questions differently.
     */
    @Test
    public void theSymbolBoardAltPageIsAltForCharactersAndNotForChords() {
        ModifierState onTheAltPage = ModifierState.builder()
                .modifierKeyMeta(0)                        // no modifier KEY is down
                .interpretedMeta(KeyEvent.META_ALT_ON)     // ...but the page's mask adds Alt
                .build();

        assertTrue(onTheAltPage.isAltActiveForCharacter());
        assertFalse("a chord must not see the board's own Alt page",
                onTheAltPage.isAltActiveForChord());
        assertEquals("and it is not in the chord meta state either",
                0, onTheAltPage.getChordMetaState());
    }

    @Test
    public void aRealAltIsAltForBothQuestions() {
        ModifierState heldAlt = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_ALT_ON)
                .interpretedMeta(KeyEvent.META_ALT_ON)
                .build();

        assertTrue(heldAlt.isAltActiveForChord());
        assertTrue(heldAlt.isAltActiveForCharacter());
    }

    /** A locked Alt is a chord Alt too — the mask covers the span tracker's 0x200. */
    @Test
    public void aLockedAltCountsForAChord() {
        ModifierState locked = ModifierState.builder()
                .modifierKeyMeta(ModifierState.SPAN_ALT_LOCKED).build();

        assertTrue(locked.isAltActiveForChord());
    }

    /**
     * The chord state is the system's meta OR the modifier keys' own, and neither half may be
     * dropped: a held Alt is only in the first on a plain device, and only in the second on one
     * whose accessibility service eats hardware Alt.
     */
    @Test
    public void theChordStateIsTheUnionOfTheEventAndTheModifierKeys() {
        ModifierState fromEventOnly = ModifierState.builder()
                .eventMeta(KeyEvent.META_ALT_ON).build();
        assertTrue(fromEventOnly.isAltActiveForChord());

        ModifierState fromSpanOnly = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_ALT_ON).build();
        assertTrue(fromSpanOnly.isAltActiveForChord());

        ModifierState both = ModifierState.builder()
                .eventMeta(KeyEvent.META_SHIFT_ON)
                .modifierKeyMeta(KeyEvent.META_ALT_ON)
                .build();
        assertEquals(KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON, both.getChordMetaState());
    }

    // ==================== Sym ====================

    @Test
    public void theHeldKeySetAnswersWhetherSymIsHeld() {
        assertTrue(ModifierState.builder().symKeyHeld(true).build().isSymHeld());
        assertFalse(ModifierState.none().isSymHeld());
    }

    /** {@code META_SYM_ON} also means Sym — the KEY2's Sym key just never sets it. */
    @Test
    public void theEventSymBitAlsoMeansSym() {
        assertTrue(ModifierState.ofEventMeta(KeyEvent.META_SYM_ON).isSymHeld());
        assertTrue(ModifierState.ofEventMeta(KeyEvent.META_SYM_ON).isSymActive());
    }

    @Test
    public void theSymSpanCanBeLocked() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(ModifierState.SPAN_SYM_LOCKED).build();

        assertTrue(state.isSymLocked());
        assertTrue(state.isSymActive());
    }

    // ==================== Ctrl ====================

    @Test
    public void ctrlComesFromItsOneOwnerOrFromTheEvent() {
        assertTrue(ModifierState.builder().ctrlActive(true).build().isCtrlActive());
        assertTrue(ModifierState.ofEventMeta(KeyEvent.META_CTRL_ON).isCtrlActive());
        assertFalse(ModifierState.none().isCtrlActive());
    }

    /**
     * And the span tracker's "CTRL_SPAN" is not Ctrl: key code 63 is {@code KEYCODE_SYM} and the
     * flag it sets is {@code META_SYM_ON}. Reading it as Ctrl is the confusion this API ends.
     */
    @Test
    public void theSpanTrackersCtrlSpanIsReallySym() {
        ModifierState state = ModifierState.builder()
                .modifierKeyMeta(KeyEvent.META_SYM_ON | ModifierState.SPAN_SYM_LOCKED).build();

        assertTrue(state.isSymActive());
        assertFalse(state.isCtrlActive());
    }

    // ==================== held keys ====================

    @Test
    public void theHeldKeyCountIsCarriedThrough() {
        ModifierState state = ModifierState.builder().keysHeld(2).build();

        assertEquals(2, state.getKeysHeldCount());
        assertTrue(state.isAnyKeyHeld());
        assertFalse(ModifierState.none().isAnyKeyHeld());
    }

    // ==================== equivalence with the expressions it replaced ====================

    /** {@code ModifierState.ofEvent(e).isAltHeld()} is {@code KeyEvent.isAltPressed()}. */
    @Test
    public void ofEventMatchesTheRawKeyEventPredicates() {
        int[] metas = {0, KeyEvent.META_SHIFT_ON, KeyEvent.META_ALT_ON, KeyEvent.META_SYM_ON,
                KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON,
                KeyEvent.META_CAPS_LOCK_ON};
        for (int meta : metas) {
            KeyEvent event = eventWithMeta(meta);
            ModifierState state = ModifierState.ofEvent(event);
            assertEquals("alt, meta=0x" + Integer.toHexString(meta),
                    event.isAltPressed(), state.isAltHeld());
            assertEquals("shift, meta=0x" + Integer.toHexString(meta),
                    event.isShiftPressed(), state.isShiftHeld());
            assertEquals("sym, meta=0x" + Integer.toHexString(meta),
                    event.isSymPressed(), state.isSymHeld());
            assertEquals("ctrl, meta=0x" + Integer.toHexString(meta),
                    event.isCtrlPressed(), state.isCtrlActive());
        }
    }

    /**
     * {@code KeyEventConverter.isAltActive} used to read
     * {@code keyEvent.isAltPressed() || (i & 2) == 2 || (i & 512) == 512}. Same answer, over
     * every combination of the bits either spelling can see.
     */
    @Test
    public void isAltActiveForCharacterMatchesTheOldBitLiterals() {
        int[] eventMetas = {0, KeyEvent.META_ALT_ON, KeyEvent.META_SHIFT_ON};
        int[] computedMetas = {0, KeyEvent.META_ALT_ON, ModifierState.SPAN_ALT_LOCKED,
                KeyEvent.META_SHIFT_ON, KeyEvent.META_ALT_ON | ModifierState.SPAN_ALT_LOCKED,
                ModifierState.SPAN_SHIFT_LOCKED};
        for (int eventMeta : eventMetas) {
            for (int computed : computedMetas) {
                KeyEvent event = eventWithMeta(eventMeta);
                boolean old = event.isAltPressed() || (computed & 2) == 2 || (computed & 512) == 512;
                boolean now = ModifierState.builder().event(event).interpretedMeta(computed)
                        .build().isAltActiveForCharacter();
                assertEquals("event=0x" + Integer.toHexString(eventMeta)
                        + " computed=0x" + Integer.toHexString(computed), old, now);
            }
        }
    }

    /**
     * The one place the new mask is wider than the old literals: a left/right Alt bit with no
     * {@code META_ALT_ON}. {@link KeyEvent} normalizes its meta state and never delivers that, so
     * it cannot be reached from a real event — recorded here so the widening is a decision on the
     * page rather than a surprise later.
     */
    @Test
    public void theWidenedAltMaskOnlyDiffersForAMetaStateKeyEventNeverProduces() {
        int leftAltOnly = KeyEvent.META_ALT_LEFT_ON;
        assertEquals("KeyEvent normalizes this away",
                KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON,
                KeyEvent.normalizeMetaState(leftAltOnly) & ModifierState.ALT_ANY_MASK);

        ModifierState unnormalized = ModifierState.builder().interpretedMeta(leftAltOnly).build();
        assertTrue(unnormalized.isAltActiveForCharacter());
        assertFalse("which is what the old literals answered", (leftAltOnly & 2) == 2);
    }
}
