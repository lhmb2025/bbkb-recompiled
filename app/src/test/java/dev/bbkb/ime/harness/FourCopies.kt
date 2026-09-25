package dev.bbkb.ime.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A snapshot of "the current word" as each of its owners sees it — the state whose divergence
 * produced nearly every historical typing bug (the FABLE "current word exists in four places"
 * finding). The Nuance native buffer is the fourth owner; it has no JVM-visible state here, so
 * this checker covers the three reproducible copies (editor, RIC cache, tracker) — the ones the
 * launcher-duplication, field-leak, and cursor-snap bugs lived in.
 *
 * This is the Phase-5 debug-build invariant checker: the assertions below are the consistency
 * rules a live build would scream about.
 *
 * ## Phase 1c: what the rules cover now
 *
 * The original checker asserted only the composing-REGION correspondence (tracker word vs editor
 * region). Phase 1c added the cursor rules, because the copy that actually broke most often was
 * the RIC's cached cursor, and every test that cared had to hand-assert it:
 *
 *  * **R1 region correspondence** — tracker composing ⟺ editor holds that exact region.
 *  * **R2 cursor agreement** — the RIC's cached cursor must equal the editor's selection start.
 *    This is audit SS-3: the manual-pick path commits with `newCursorPosition = 2` to step over
 *    a separator already sitting after the cursor, and the cache used to advance as if the caret
 *    always landed right after the committed text. Copy 2 then trailed copy 1 by one character
 *    until `onUpdateSelection` healed it, and every word-range / recorrection / caps-mode read in
 *    between saw the wrong position.
 *  * **R3 region-length sync** — the RIC's own `isComposingRegionSynced()` detector must agree.
 *    It compares its composing buffer's length against the region length it last told the editor
 *    about; a mismatch is how a stale region announces itself.
 *
 * A connection known-invalid (`ricCursorStart == -1`) is a declared transitional state: the
 * cursor is unknown, so nothing is correlated — but the tracker must NOT drive a fresh region
 * write, which is what the launcher bug did.
 */
data class FourCopies(
    val editorText: String,
    val editorComposing: String,
    val editorHasRegion: Boolean,
    val editorSelStart: Int,
    val ricCursorStart: Int,
    val ricRegionSynced: Boolean,
    val trackerComposing: String,
    val trackerIsComposing: Boolean,
) {
    /**
     * The rule set. In a settled (non-transitional) state:
     *  - if the tracker is composing a word, the editor must hold that exact word as its
     *    composing region (they are the same live word);
     *  - if the tracker is not composing, the editor must have no composing region;
     *  - the RIC's cached cursor must match the editor's, and its region bookkeeping must be
     *    self-consistent.
     */
    fun assertConsistent() {
        if (ricCursorStart == -1) {
            // Transitional: connection invalidated. Nothing to correlate, but this is exactly
            // the state where a stale tracker must not be re-materialized into the editor.
            return
        }
        // R1 — region correspondence.
        if (trackerIsComposing) {
            assertTrue(
                "tracker composing '$trackerComposing' but editor has no composing region " +
                    "(text='$editorText') — a copy desync (stale-region / duplicate risk)",
                editorHasRegion,
            )
            assertEquals(
                "tracker composing word != editor composing region — the four-copies desync",
                trackerComposing, editorComposing,
            )
        } else {
            assertTrue(
                "tracker not composing but editor still has a composing region " +
                    "('$editorComposing' in '$editorText') — stale editor region",
                !editorHasRegion,
            )
        }
        // R2 — cursor agreement (audit SS-3).
        assertEquals(
            "RIC cached cursor ($ricCursorStart) disagrees with the editor ($editorSelStart) " +
                "in text='$editorText' — copy 2 has drifted from copy 1 (SS-3)",
            editorSelStart, ricCursorStart,
        )
        // R3 — the RIC's own stale-region detector.
        assertTrue(
            "RIC reports its composing region out of sync with its composing buffer " +
                "(text='$editorText', region='$editorComposing') — stale region bookkeeping",
            ricRegionSynced,
        )
    }
}
