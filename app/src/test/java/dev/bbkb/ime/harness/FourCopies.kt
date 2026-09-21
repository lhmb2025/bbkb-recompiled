package dev.bbkb.ime.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A snapshot of "the current word" as each of its four owners sees it — the state whose
 * divergence produced nearly every historical typing bug (the FABLE "current word exists in
 * four places" finding). The Nuance native buffer is the fourth owner; it has no JVM-visible
 * state here, so this checker covers the three reproducible copies (editor, RIC cache,
 * tracker) — the ones the launcher-duplication, field-leak, and cursor-snap bugs lived in.
 *
 * This is the seed of the Phase-5 debug-build invariant checker: the assertions below are the
 * consistency rules a live build would scream about.
 */
data class FourCopies(
    val editorText: String,
    val editorComposing: String,
    val editorHasRegion: Boolean,
    val ricCursorStart: Int,
    val trackerComposing: String,
    val trackerIsComposing: Boolean,
) {
    /**
     * The rule set. In a settled (non-transitional) state:
     *  - if the tracker is composing a word, the editor must hold that exact word as its
     *    composing region (they are the same live word);
     *  - if the tracker is not composing, the editor must have no composing region.
     * A connection known-invalid (ricCursorStart == -1) is a declared transitional state:
     * the cursor is unknown, so region correspondence is not asserted — but the tracker must
     * NOT drive a fresh region write, which is what the launcher bug did.
     */
    fun assertConsistent() {
        if (ricCursorStart == -1) {
            // Transitional: connection invalidated. Nothing to correlate, but this is exactly
            // the state where a stale tracker must not be re-materialized into the editor.
            return
        }
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
    }
}
