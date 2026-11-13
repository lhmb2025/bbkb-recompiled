package dev.bbkb.ime.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The current word as its JVM-visible owners see it (editor, RIC cache, tracker; the native
 * buffer is the fourth). Rules: R1 tracker composing iff the editor holds that exact region;
 * R2 RIC cached cursor equals the editor's selection start; R3 `isComposingRegionSynced()`.
 * `ricCursorStart == -1` (connection invalid) is transitional: nothing is correlated.
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
    fun assertConsistent() {
        if (ricCursorStart == -1) {
            // Connection invalidated: nothing to correlate.
            return
        }
        // R1: region correspondence.
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
        // R2: cursor agreement.
        assertEquals(
            "RIC cached cursor ($ricCursorStart) disagrees with the editor ($editorSelStart) " +
                "in text='$editorText' — copy 2 has drifted from copy 1 (SS-3)",
            editorSelStart, ricCursorStart,
        )
        // R3: the RIC's own stale-region detector.
        assertTrue(
            "RIC reports its composing region out of sync with its composing buffer " +
                "(text='$editorText', region='$editorComposing') — stale region bookkeeping",
            ricRegionSynced,
        )
    }
}
