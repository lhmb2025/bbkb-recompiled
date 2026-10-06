package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterisation tests for [ClipboardHistoryManager] — the clipboard board's history model and
 * its registration as a system primary-clip listener.
 *
 * The clipboard board had **no test coverage at all** (1,584 code lines across 14 files). These
 * tests pin **what the model does today**, not what it ought to do, so that a rewrite has to
 * reproduce it or deliberately change it rather than drift silently. Assertions that record
 * behaviour which is plainly wrong are marked `CHARACTERISED BUG:`.
 *
 * Everything is driven through the real system entry point: a clip is put on Robolectric's
 * [ClipboardManager], whose shadow notifies the registered listeners exactly as the framework
 * does, so `onPrimaryClipChanged` runs for real. No private field is read and no method is called
 * that production does not call — the only observable surface used is [getHistory], the two
 * listener interfaces, and the state of the system clipboard afterwards.
 *
 * Note that `ShadowClipboardManager` holds its clip and its listener list in **static** fields.
 * Robolectric resets them between tests; a fresh [ClipboardHistoryManager] is built in [setUp] for
 * the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClipboardHistoryManagerTest {

    private lateinit var context: Context
    private lateinit var system: ClipboardManager
    private lateinit var history: ClipboardHistoryManager

    /** Every clip the manager reported evicting, in order. */
    private val evicted = mutableListOf<ClipData?>()

    /** Number of times the manager announced that the history changed. */
    private var historyChanges = 0

    private val changeListener = ClipboardHistoryManager.OnHistoryChangedListener { historyChanges++ }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        system = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        history = ClipboardHistoryManager(context)
        history.setOnClipEvictedListener { clip -> evicted += clip }
        history.addHistoryChangedListener(changeListener)
    }

    // ── driving the real listener path ────────────────────────────────────────

    /** Put [text] on the system clipboard, which fires the manager's primary-clip listener. */
    private fun copy(text: String, label: String = "") {
        system.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Put a clip carrying [label] on the system clipboard (the Password Keeper protocol). */
    private fun copyLabelled(label: String, text: String = "secret") = copy(text, label)

    private fun texts(): List<String?> =
        history.history.map { ClipboardItem.getTextFromClipData(it.mClipData) }

    private fun primaryText(): String? = ClipboardItem.getTextFromClipData(system.primaryClip)

    private fun passwordAddLabel() = context.getString(dev.bbkb.ime.R.string.clip_password_keeper_add)

    private fun passwordClearLabel() = context.getString(dev.bbkb.ime.R.string.clip_password_keeper_clear)

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Capture and ordering
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aCopiedClipIsCaptured() {
        copy("hello")

        assertEquals(listOf("hello"), texts())
    }

    @Test
    fun successiveCopiesStackNewestFirst() {
        copy("one")
        copy("two")
        copy("three")

        assertEquals(listOf("three", "two", "one"), texts())
    }

    @Test
    fun everyAcceptedClipAnnouncesAHistoryChange() {
        copy("one")
        copy("two")

        assertEquals(2, historyChanges)
    }

    @Test
    fun aRemovedHistoryListenerStopsHearingAboutChanges() {
        copy("one")
        history.removeHistoryChangedListener(changeListener)

        copy("two")

        assertEquals("only the first copy was announced", 1, historyChanges)
        assertEquals("but the clip was still captured", listOf("two", "one"), texts())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. The seven-entry cap
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theHistoryHoldsSevenEntries() {
        repeat(7) { copy("clip $it") }

        assertEquals(7, history.history.size)
        assertEquals(listOf("clip 6", "clip 5", "clip 4", "clip 3", "clip 2", "clip 1", "clip 0"), texts())
    }

    @Test
    fun theEighthClipEvictsTheOldest() {
        repeat(8) { copy("clip $it") }

        assertEquals("still seven", 7, history.history.size)
        assertEquals("the newest is at the front", "clip 7", texts().first())
        assertFalse("the oldest is gone", texts().contains("clip 0"))
    }

    @Test
    fun evictionIsReportedToTheEvictionListenerWithTheEvictedClip() {
        repeat(8) { copy("clip $it") }

        assertEquals("exactly one eviction", 1, evicted.size)
        assertEquals("clip 0", ClipboardItem.getTextFromClipData(evicted.single()))
    }

    /**
     * Audit IB-14: the manager registers itself as a system clipboard listener from its own
     * constructor, but `setOnClipEvictedListener` is only called from `ClipboardView.initialize()`.
     * Any clip arriving in that window used to NPE here once the history filled up.
     */
    @Test
    fun evictionBeforeAnEvictionListenerIsSetDoesNotThrow() {
        val bare = ClipboardHistoryManager(context)

        repeat(8) { copy("clip $it") }

        assertEquals(7, bare.history.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. De-duplication
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun reCopyingExistingTextMovesItToTheFrontWithoutGrowingTheHistory() {
        copy("one")
        copy("two")
        copy("three")

        copy("one")

        assertEquals(listOf("one", "three", "two"), texts())
    }

    @Test
    fun duplicatesAreDetectedByTextNotByClipDataIdentity() {
        // Two distinct ClipData objects, different labels, same text.
        copy("same", label = "first label")
        copy("same", label = "second label")

        assertEquals("collapsed to one entry", 1, history.history.size)
        assertEquals(
            "and it is the ORIGINAL ClipData that survives, not the newly copied one",
            "first label",
            history.history.single().mClipData.description.label.toString(),
        )
    }

    @Test
    fun duplicateDetectionIsCaseSensitive() {
        copy("Hello")

        copy("hello")

        assertEquals("different case is a different clip", listOf("hello", "Hello"), texts())
    }

    @Test
    fun leadingAndTrailingWhitespaceMakesADifferentClip() {
        copy("hello")

        copy(" hello ")

        assertEquals("the text is compared verbatim, untrimmed", listOf(" hello ", "hello"), texts())
    }

    @Test
    fun aDuplicateOfTheFrontEntryIsStillAMoveToFront() {
        copy("one")
        copy("one")

        assertEquals(listOf("one"), texts())
        assertEquals("both copies announced a change", 2, historyChanges)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Clips that are refused
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aClipWithNoTextIsIgnored() {
        system.setPrimaryClip(ClipData.newIntent("intent", android.content.Intent("nothing")))

        assertTrue("nothing captured", history.history.isEmpty())
        // A refused clip changes nothing, so nothing is announced.
        assertEquals("and nothing announced", 0, historyChanges)
    }

    @Test
    fun aNullPrimaryClipIsIgnoredEntirely() {
        // Nothing has ever been copied, so getPrimaryClip() is null. This is the real entry point
        // the framework calls; the early return is the only thing standing between it and an NPE.
        history.onPrimaryClipChanged()

        assertTrue("nothing captured", history.history.isEmpty())
        assertEquals("and nothing announced — onPrimaryClipChanged returns early", 0, historyChanges)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Internal updates (the board pasting, not the user copying)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun anInternalSetPrimaryClipDoesNotReEnterTheHistory() {
        history.setPrimaryClip(ClipData.newPlainText("", "pasted by us"))

        assertTrue("not captured", history.history.isEmpty())
        assertEquals("and not announced", 0, historyChanges)
        assertEquals("but it did reach the system clipboard", "pasted by us", primaryText())
    }

    @Test
    fun theInternalFlagIsOneShotSoTheNextRealCopyIsCaptured() {
        history.setPrimaryClip(ClipData.newPlainText("", "pasted by us"))

        copy("typed by the user")

        assertEquals(listOf("typed by the user"), texts())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Password Keeper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aPasswordAddClipIsCapturedLikeAnyOther() {
        copyLabelled(passwordAddLabel(), "hunter2")

        assertEquals(listOf("hunter2"), texts())
    }

    @Test
    fun thePasswordEntryIsPurgedAsSoonAsTheNextClipArrives() {
        copyLabelled(passwordAddLabel(), "hunter2")

        copy("ordinary")

        assertEquals("the password row is gone", listOf("ordinary"), texts())
    }

    @Test
    fun aPasswordClearClipIsDroppedAndPurgesThePasswordEntries() {
        copy("ordinary")
        copyLabelled(passwordAddLabel(), "hunter2")

        copyLabelled(passwordClearLabel(), "irrelevant")

        assertEquals("the password row purged, the clear clip itself never stored",
            listOf("ordinary"), texts())
        assertEquals("the purge is a real change, so the clear clip is announced", 3, historyChanges)
    }

    @Test
    fun onlyPasswordAddRowsArePurgedNotEveryRow() {
        copy("keep me")
        copyLabelled(passwordAddLabel(), "hunter2")
        copy("keep me too")

        copy("newest")

        assertEquals(listOf("newest", "keep me too", "keep me"), texts())
    }

    @Test
    fun hasLabelMatchesTheExactLabelAndIsNullSafe() {
        val labelled = ClipData.newPlainText("bb.pk", "x")

        assertTrue(ClipboardHistoryManager.hasLabel(labelled, "bb.pk"))
        assertFalse(ClipboardHistoryManager.hasLabel(labelled, "bb.pk.clear"))
        assertFalse(ClipboardHistoryManager.hasLabel(null, "bb.pk"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Deleting a row
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun removingAnEntryThatIsNotTheCurrentClipLeavesTheSystemClipboardAlone() {
        copy("older")
        copy("current")

        history.removeEntry(history.history.last())

        assertEquals(listOf("current"), texts())
        assertEquals("current", primaryText())
    }

    /** The promotion is flagged as an internal update, so it does not re-enter the history. */
    @Test
    fun removingTheCurrentClipPromotesTheNextRowToTheSystemClipboard() {
        copy("older")
        copy("current")

        history.removeEntry(history.history.first())

        assertEquals("the deleted row is gone", listOf("older"), texts())
        assertEquals("and the next row became the system clip", "older", primaryText())
        assertEquals("the promotion did not re-enter through the listener", 2, historyChanges)
    }

    @Test
    fun removingTheLastEntryClearsTheSystemClipboardToEmptyText() {
        copy("only")

        history.removeEntry(history.history.single())

        assertTrue("history empty", history.history.isEmpty())
        assertEquals("system clipboard blanked", "", primaryText())
        assertEquals("blanking is an internal update, so nothing was re-captured", 1, historyChanges)
    }

    @Test
    fun removingAnEntryThatIsNotInTheHistoryIsAHarmlessNoOpWhileOtherRowsRemain() {
        copy("kept")
        val stranger = ClipboardHistoryManager.ClipEntry(ClipData.newPlainText("", "never copied"))

        history.removeEntry(stranger)

        assertEquals(listOf("kept"), texts())
        assertEquals("the system clipboard is untouched", "kept", primaryText())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7b. Incognito fields and sensitive clips
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun nothingIsCapturedWhileTheFocusedFieldIsIncognito() {
        copy("before")
        history.setNoPersonalizedLearning(true)

        copy("private")

        assertEquals("the incognito copy never entered the history", listOf("before"), texts())
        assertEquals("and nothing was announced for it", 1, historyChanges)
    }

    @Test
    fun captureResumesOnceTheFieldIsNoLongerIncognito() {
        history.setNoPersonalizedLearning(true)
        copy("private")
        history.setNoPersonalizedLearning(false)

        copy("public")

        assertEquals(listOf("public"), texts())
    }

    @Test
    fun aClipMarkedSensitiveIsNeverStored() {
        copy("ordinary")
        val sensitive = ClipData.newPlainText("", "123456").apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        system.setPrimaryClip(sensitive)

        assertEquals(listOf("ordinary"), texts())
        assertEquals(1, historyChanges)
    }

    @Test
    fun aClipWithTheSensitiveExtraFalseIsStored() {
        val notSensitive = ClipData.newPlainText("", "hello").apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
            }
        }

        system.setPrimaryClip(notSensitive)

        assertEquals(listOf("hello"), texts())
    }

    @Test
    @Config(sdk = [32])
    fun beforeApi33TheSensitiveExtraIsNotConsulted() {
        // EXTRA_IS_SENSITIVE is an API 33 contract; an older platform cannot have set it.
        val clip = ClipData.newPlainText("", "x").apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }

        assertFalse(ClipboardHistoryManager.isSensitiveClip(clip))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Teardown
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun releaseUnregistersTheManagerSoLaterClipsAreNotCaptured() {
        history.release()

        copy("after release")

        assertTrue(history.history.isEmpty())
        assertEquals(0, historyChanges)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. ClipEntry value semantics (what the history's remove() relies on)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun clipEntryEqualityIsClipDataEquality() {
        val data = ClipData.newPlainText("", "x")
        val a = ClipboardHistoryManager.ClipEntry(data)
        val b = ClipboardHistoryManager.ClipEntry(data)

        assertEquals("same ClipData instance means equal entries", a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertSame(data, a.mClipData)
    }

    @Test
    fun clipEntriesWrappingDistinctClipDataOfTheSameTextAreNotEqual() {
        val a = ClipboardHistoryManager.ClipEntry(ClipData.newPlainText("", "x"))
        val b = ClipboardHistoryManager.ClipEntry(ClipData.newPlainText("", "x"))

        // ClipData does not override equals, so this is identity — which is why the history's
        // de-duplication compares extracted TEXT rather than entries.
        assertFalse(a == b)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. ClipboardItem.getTextFromClipData — used cross-board by FccView
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun getTextFromClipDataReturnsTheFirstItemsText() {
        assertEquals("abc", ClipboardItem.getTextFromClipData(ClipData.newPlainText("", "abc")))
    }

    @Test
    fun getTextFromClipDataIsNullForNullAndForNonTextClips() {
        assertNull(ClipboardItem.getTextFromClipData(null))
        assertNull(
            ClipboardItem.getTextFromClipData(
                ClipData.newIntent("i", android.content.Intent("nothing")),
            ),
        )
    }
}
