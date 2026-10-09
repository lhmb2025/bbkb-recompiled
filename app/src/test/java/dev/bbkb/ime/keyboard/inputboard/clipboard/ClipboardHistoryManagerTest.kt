package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * [ClipboardHistoryManager] — the clipboard board's history model, driven through the real system
 * entry point.
 *
 * A clip is put on Robolectric's [ClipboardManager], whose shadow notifies the registered
 * listeners exactly as the framework does, so `onPrimaryClipChanged` runs for real. The manager is
 * built on a store over a temp file with a direct executor (so a save has landed by the next
 * line), a clock the test moves, and an unlock state the test flips.
 *
 * `ShadowClipboardManager` holds its clip and listener list in static fields that Robolectric
 * resets between tests; `PrefsManager`'s preferences instance is shared by every test in the
 * sandbox, so it is cleared on both sides of each test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClipboardHistoryManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var system: ClipboardManager
    private lateinit var file: File
    private lateinit var history: ClipboardHistoryManager

    private var now = START
    private var unlocked = true

    /** Number of times the manager announced that the history changed. */
    private var historyChanges = 0

    private val changeListener = ClipboardHistoryManager.OnHistoryChangedListener { historyChanges++ }

    private val direct = Executor { it.run() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
        system = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        file = File(tmp.root, ClipboardHistoryStore.FILE_NAME)
        history = newManager()
        history.addHistoryChangedListener(changeListener)
    }

    @After
    fun tearDown() {
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    private fun newManager(): ClipboardHistoryManager = ClipboardHistoryManager(
        context,
        ClipboardHistoryStore.forFile(file, direct),
        { now },
        { unlocked },
    )

    // ── driving the real listener path ────────────────────────────────────────

    /** Put [text] on the system clipboard, which fires the manager's primary-clip listener. */
    private fun copy(text: String, label: String = "") {
        system.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Put a clip carrying [label] on the system clipboard (the Password Keeper protocol). */
    private fun copyLabelled(label: String, text: String = "secret") = copy(text, label)

    private fun texts(manager: ClipboardHistoryManager = history): List<String> = manager.history.map { it.text }

    private fun pinnedTexts(): List<String> = history.pinned.map { it.text }

    private fun recentTexts(): List<String> = history.recent.map { it.text }

    private fun entry(text: String): ClipEntry = history.history.single { it.text == text }

    private fun primaryText(): String? = ClipboardItem.getTextFromClipData(system.primaryClip)

    private fun stored(): List<ClipEntry> =
        if (file.exists()) ClipboardHistoryStore.decode(file.readText()) else emptyList()

    private fun setPref(key: String, value: Any) {
        val editor = PrefsManager.getPrefs(context).edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        }
        editor.commit()
    }

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
    fun theOrderHoldsWhenEveryCopyHasTheSameTimestamp() {
        // The clock does not move between these: insertion order, not the timestamp, keeps them.
        repeat(4) { copy("clip $it") }

        assertEquals(listOf("clip 3", "clip 2", "clip 1", "clip 0"), texts())
    }

    @Test
    fun aCapturedRowRecordsLabelMimeAndTime() {
        system.setPrimaryClip(ClipData.newHtmlText("page", "plain side", "<b>html side</b>"))

        val captured = history.history.single()
        assertEquals("plain side", captured.text)
        assertEquals("page", captured.label)
        assertEquals(ClipDescription.MIMETYPE_TEXT_HTML, captured.mime)
        assertEquals(START, captured.createdAtMs)
        assertFalse(captured.isPinned)
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
    // 2. The cap: 25 unpinned rows, pinned rows exempt
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theHistoryHoldsTwentyFiveUnpinnedEntries() {
        repeat(25) { copy("clip $it") }

        assertEquals(25, history.history.size)
        assertEquals("clip 24", texts().first())
        assertEquals("clip 0", texts().last())
    }

    @Test
    fun theTwentySixthClipEvictsTheOldest() {
        repeat(26) { copy("clip $it") }

        assertEquals("still twenty-five", 25, history.history.size)
        assertEquals("the newest is at the front", "clip 25", texts().first())
        assertFalse("the oldest is gone", texts().contains("clip 0"))
    }

    @Test
    fun pinnedEntriesNeitherCountAgainstTheCapNorAreEvicted() {
        copy("keep me")
        history.setPinned(entry("keep me").id, true)

        repeat(30) { copy("clip $it") }

        assertEquals(listOf("keep me"), pinnedTexts())
        assertEquals("twenty-five unpinned besides the pin", 25, recentTexts().size)
        assertEquals(26, history.history.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. De-duplication and refresh
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun reCopyingExistingTextMovesItToTheFrontWithoutGrowingTheHistory() {
        copy("one")
        copy("two")
        copy("three")
        now += 1_000

        copy("one")

        assertEquals(listOf("one", "three", "two"), texts())
        assertEquals("and its copy time is refreshed", START + 1_000, entry("one").createdAtMs)
    }

    @Test
    fun duplicatesAreDetectedByTextAndTheOriginalRowSurvives() {
        copy("same", label = "first label")
        val original = entry("same")

        copy("same", label = "second label")

        assertEquals("collapsed to one entry", 1, history.history.size)
        assertEquals("the original row keeps its id", original.id, entry("same").id)
        assertEquals("and its label", "first label", entry("same").label)
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
    fun aDuplicateOfTheFrontEntryIsStillAChange() {
        copy("one")
        copy("one")

        assertEquals(listOf("one"), texts())
        assertEquals("both copies announced a change", 2, historyChanges)
    }

    @Test
    fun copyingTextIdenticalToAPinnedEntryRefreshesItAndKeepsItPinned() {
        copy("pinned text")
        val pinnedId = entry("pinned text").id
        history.setPinned(pinnedId, true)
        copy("other")
        now += 5_000

        copy("pinned text")

        assertEquals("still pinned, not duplicated into the recent section", listOf("pinned text"), pinnedTexts())
        assertEquals(listOf("other"), recentTexts())
        assertEquals("the same row", pinnedId, entry("pinned text").id)
        assertEquals("with its copy time refreshed", START + 5_000, entry("pinned text").createdAtMs)
        assertEquals("but its pin time kept", START, entry("pinned text").pinnedAtMs)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Clips that are refused
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aClipWithNoTextIsIgnored() {
        system.setPrimaryClip(ClipData.newIntent("intent", android.content.Intent("nothing")))

        assertTrue("nothing captured", history.history.isEmpty())
        assertEquals("and nothing announced", 0, historyChanges)
    }

    @Test
    fun aNullPrimaryClipIsIgnoredEntirely() {
        history.onPrimaryClipChanged()

        assertTrue("nothing captured", history.history.isEmpty())
        assertEquals("and nothing announced", 0, historyChanges)
    }

    @Test
    fun copyingARowBackToTheSystemClipboardDoesNotReEnterTheHistory() {
        copy("older")
        copy("newer")

        history.copyToSystemClipboard(entry("older"))

        assertEquals("it reached the system clipboard", "older", primaryText())
        assertEquals("but did not move or duplicate the row", listOf("newer", "older"), texts())
        assertEquals("and was not announced", 2, historyChanges)
    }

    @Test
    fun theInternalFlagIsOneShotSoTheNextRealCopyIsCaptured() {
        copy("first")
        history.copyToSystemClipboard(entry("first"))

        copy("typed by the user")

        assertEquals(listOf("typed by the user", "first"), texts())
    }

    @Test
    fun nothingIsCapturedWhileHistoryIsTurnedOff() {
        copy("before")
        setPref(ClipboardPrefs.KEY_HISTORY_ENABLED, false)

        copy("after")

        assertTrue("off holds nothing, so the earlier clip is dropped too", history.history.isEmpty())
        assertFalse("and nothing is left on disk", file.exists())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Password Keeper
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aPasswordAddClipIsCapturedAndMarkedAsAPasswordRow() {
        copyLabelled(passwordAddLabel(), "hunter2")

        assertEquals(listOf("hunter2"), texts())
        assertTrue(history.isPasswordKeeperEntry(entry("hunter2")))
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
    fun aPasswordRowIsNeverWrittenToDisk() {
        copy("ordinary")
        copyLabelled(passwordAddLabel(), "hunter2")

        assertEquals("in memory", listOf("hunter2", "ordinary"), texts())
        assertEquals("but not on disk", listOf("ordinary"), stored().map { it.text })
    }

    @Test
    fun aPasswordRowCannotBePinned() {
        copyLabelled(passwordAddLabel(), "hunter2")

        assertFalse(history.setPinned(entry("hunter2").id, true))
        assertTrue(pinnedTexts().isEmpty())
    }

    @Test
    fun aPasswordRowGoesBackToTheClipboardMarkedSensitiveWithItsLabel() {
        copyLabelled(passwordAddLabel(), "hunter2")

        history.copyToSystemClipboard(entry("hunter2"))

        val clip = system.primaryClip!!
        assertEquals(passwordAddLabel(), clip.description.label.toString())
        assertTrue(ClipboardHistoryManager.isSensitiveClip(clip))
    }

    @Test
    fun hasLabelMatchesTheExactLabelAndIsNullSafe() {
        val labelled = ClipData.newPlainText("bb.pk", "x")

        assertTrue(ClipboardHistoryManager.hasLabel(labelled, "bb.pk"))
        assertFalse(ClipboardHistoryManager.hasLabel(labelled, "bb.pk.clear"))
        assertFalse(ClipboardHistoryManager.hasLabel(null, "bb.pk"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Deleting a row
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun removingAnEntryThatIsNotTheCurrentClipLeavesTheSystemClipboardAlone() {
        copy("older")
        copy("current")

        assertTrue(history.removeEntry(entry("older").id))

        assertEquals(listOf("current"), texts())
        assertEquals("current", primaryText())
    }

    @Test
    fun removingTheCurrentClipPromotesTheMostRecentlyCopiedRow() {
        copy("oldest")
        now += 1
        copy("older")
        now += 1
        copy("current")

        history.removeEntry(entry("current").id)

        assertEquals("the deleted row is gone", listOf("older", "oldest"), texts())
        assertEquals("and the next most recent became the system clip", "older", primaryText())
        assertEquals("the promotion did not re-enter through the listener", listOf("older", "oldest"), texts())
    }

    @Test
    fun removingTheLastEntryWhileItIsTheCurrentClipBlanksTheSystemClipboard() {
        copy("only")

        history.removeEntry(entry("only").id)

        assertTrue("history empty", history.history.isEmpty())
        assertEquals("system clipboard blanked", "", primaryText())
        assertTrue("blanking is an internal update, so nothing was re-captured", history.history.isEmpty())
    }

    @Test
    fun removingAnUnknownIdIsAHarmlessNoOp() {
        copy("kept")
        val changesBefore = historyChanges

        assertFalse(history.removeEntry(9_999))

        assertEquals(listOf("kept"), texts())
        assertEquals("kept", primaryText())
        assertEquals("nothing announced", changesBefore, historyChanges)
    }

    @Test
    fun removalIsAnnouncedAndWritten() {
        copy("a")
        copy("b")
        val changesBefore = historyChanges

        history.removeEntry(entry("a").id)

        assertEquals(changesBefore + 1, historyChanges)
        assertEquals(listOf("b"), stored().map { it.text })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Incognito fields and sensitive clips
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
        assertEquals("nor written", listOf("ordinary"), stored().map { it.text })
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
        val clip = ClipData.newPlainText("", "x").apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }

        assertFalse(ClipboardHistoryManager.isSensitiveClip(clip))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. Retention
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun byDefaultAnUnpinnedClipExpiresAfterAnHour() {
        copy("old")
        now += HOUR + 1

        copy("new")

        assertEquals("expired on capture", listOf("new"), texts())
    }

    @Test
    fun aClipExactlyAtTheLimitIsKept() {
        copy("old")
        now += HOUR

        history.prune()

        assertEquals(listOf("old"), texts())
    }

    @Test
    fun pruneExpiresWithoutACaptureAndAnnouncesIt() {
        copy("old")
        val changesBefore = historyChanges
        now += HOUR + 1

        history.prune()

        assertTrue(history.history.isEmpty())
        assertEquals(changesBefore + 1, historyChanges)
        assertTrue("and the file follows", stored().isEmpty())
    }

    @Test
    fun aPruneThatChangesNothingAnnouncesNothing() {
        copy("fresh")
        val changesBefore = historyChanges

        history.prune()

        assertEquals(changesBefore, historyChanges)
    }

    @Test
    fun theRetentionSettingIsHonoured() {
        setPref(ClipboardPrefs.KEY_RETENTION, ClipboardPrefs.RETENTION_24H)
        copy("a day")
        now += 23 * HOUR
        history.prune()
        assertEquals("still inside 24 hours", listOf("a day"), texts())

        now += 2 * HOUR
        history.prune()
        assertTrue("past 24 hours", history.history.isEmpty())

        setPref(ClipboardPrefs.KEY_RETENTION, ClipboardPrefs.RETENTION_7D)
        copy("a week")
        now += 6 * 24 * HOUR
        history.prune()
        assertEquals(listOf("a week"), texts())
        now += 2 * 24 * HOUR
        history.prune()
        assertTrue(history.history.isEmpty())
    }

    @Test
    fun noTimeLimitKeepsUnpinnedClipsIndefinitely() {
        setPref(ClipboardPrefs.KEY_RETENTION, ClipboardPrefs.RETENTION_NEVER)
        copy("forever")
        now += 365 * 24 * HOUR

        history.prune()

        assertEquals(listOf("forever"), texts())
    }

    @Test
    fun anUnknownRetentionValueFallsBackToOneHour() {
        setPref(ClipboardPrefs.KEY_RETENTION, "fortnight")
        copy("old")
        now += HOUR + 1

        history.prune()

        assertTrue(history.history.isEmpty())
    }

    @Test
    fun pinnedClipsNeverExpire() {
        copy("pinned")
        history.setPinned(entry("pinned").id, true)
        copy("unpinned")
        now += 30 * 24 * HOUR

        history.prune()

        assertEquals(listOf("pinned"), texts())
    }

    @Test
    fun unpinningGivesTheClipAFreshRetentionWindowAtTheTopOfRecent() {
        copy("was pinned")
        history.setPinned(entry("was pinned").id, true)
        now += 10 * HOUR
        copy("recent")
        now += 1

        history.setPinned(entry("was pinned").id, false)
        history.prune()

        assertEquals("it did not expire on the spot", listOf("was pinned", "recent"), recentTexts())
        assertEquals(now, entry("was pinned").createdAtMs)
    }

    @Test
    fun aCreatedAtInTheFutureIsTreatedAsNow() {
        // A row written while the clock was ahead (or a clock set back since).
        file.writeText(ClipboardHistoryStore.encode(listOf(
            ClipEntry(1, "from the future", null, "text/plain", START + 30 * 24 * HOUR),
        )))
        val reloaded = newManager()

        reloaded.prune()
        assertEquals("clamped, not expired", START, reloaded.history.single().createdAtMs)

        now += HOUR + 1
        reloaded.prune()
        assertTrue("and it now expires on the normal schedule", reloaded.history.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. Pinning
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun pinningMovesARowIntoThePinnedSectionAheadOfRecent() {
        copy("one")
        copy("two")
        copy("three")

        assertTrue(history.setPinned(entry("one").id, true))

        assertEquals(listOf("one"), pinnedTexts())
        assertEquals(listOf("three", "two"), recentTexts())
        assertEquals("pinned first in the combined list", listOf("one", "three", "two"), texts())
        assertTrue(entry("one").isPinned)
    }

    @Test
    fun theNewestPinComesFirst() {
        copy("a")
        copy("b")
        history.setPinned(entry("a").id, true)
        now += 1
        history.setPinned(entry("b").id, true)

        assertEquals(listOf("b", "a"), pinnedTexts())
    }

    @Test
    fun pinningAnAlreadyPinnedOrUnknownRowChangesNothing() {
        copy("a")
        history.setPinned(entry("a").id, true)
        val changesBefore = historyChanges

        assertFalse(history.setPinned(entry("a").id, true))
        assertFalse(history.setPinned(9_999, true))
        assertFalse(history.setPinned(9_999, false))

        assertEquals(changesBefore, historyChanges)
    }

    @Test
    fun unpinningIntoAFullRecentSectionEvictsTheOldestUnpinned() {
        copy("pinned")
        history.setPinned(entry("pinned").id, true)
        repeat(25) { copy("clip $it") }

        history.setPinned(entry("pinned").id, false)

        assertEquals(25, recentTexts().size)
        assertEquals("pinned", recentTexts().first())
        assertFalse(recentTexts().contains("clip 0"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. Persistence
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theHistoryRoundTripsThroughTheStore() {
        copy("one")
        copy("two")
        history.setPinned(entry("one").id, true)
        copy("three")
        val before = history.history

        val reloaded = newManager()
        reloaded.prune()

        assertEquals(before, reloaded.history)
        assertEquals(listOf("one"), reloaded.pinned.map { it.text })
    }

    @Test
    fun nothingIsReadOrWrittenBeforeTheUserUnlocks() {
        file.writeText(ClipboardHistoryStore.encode(listOf(ClipEntry(7, "stored", null, "text/plain", START))))
        unlocked = false
        val locked = newManager()

        copy("copied while locked")

        assertEquals("held in memory", listOf("copied while locked"), texts(locked))
        assertEquals("the file is untouched", listOf("stored"), stored().map { it.text })
    }

    @Test
    fun clipsCopiedWhileLockedAreMergedIntoTheStoredHistoryOnUnlock() {
        file.writeText(ClipboardHistoryStore.encode(listOf(
            ClipEntry(7, "stored pin", null, "text/plain", START - 10, true, START - 10),
            ClipEntry(8, "stored", null, "text/plain", START - 5),
            ClipEntry(9, "copied again", null, "text/plain", START - 20),
        )))
        unlocked = false
        val manager = newManager()
        copy("copied again")
        now += 1
        copy("copied while locked")

        unlocked = true
        manager.prune()

        assertEquals(listOf("stored pin"), manager.pinned.map { it.text })
        assertEquals(listOf("copied while locked", "copied again", "stored"), manager.recent.map { it.text })
        assertEquals("ids stay unique", 4, manager.history.map { it.id }.toSet().size)
        assertEquals("and the merge is written", manager.history.map { it.text }, stored().map { it.text })
    }

    @Test
    fun clipsTooLongToPersistStayInMemoryOnly() {
        copy("short")
        copy("x".repeat(ClipboardHistoryManager.MAX_PERSISTED_CHARS + 1))

        assertEquals(2, history.history.size)
        assertEquals(listOf("short"), stored().map { it.text })
    }

    @Test
    fun aCorruptFileLoadsAsAnEmptyHistory() {
        file.writeText("{ not json")
        val manager = newManager()

        manager.prune()
        copy("fresh")

        assertEquals(listOf("fresh"), texts(manager))
        assertEquals(listOf("fresh"), stored().map { it.text })
    }

    @Test
    fun turningHistoryOffWipesMemoryAndFileOnTheNextPrune() {
        copy("a")
        assertTrue(file.exists())
        setPref(ClipboardPrefs.KEY_HISTORY_ENABLED, false)

        history.prune()

        assertTrue(history.history.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun aManagerStartingWithHistoryOffDeletesWhatWasStored() {
        file.writeText(ClipboardHistoryStore.encode(listOf(ClipEntry(1, "old", null, "text/plain", START))))
        setPref(ClipboardPrefs.KEY_HISTORY_ENABLED, false)

        newManager().prune()

        assertFalse(file.exists())
    }

    @Test
    fun whenEveryRowIsGoneTheFileIsDeletedRatherThanLeftEmpty() {
        copy("only")
        assertTrue(file.exists())

        history.removeEntry(entry("only").id)

        assertFalse(file.exists())
    }

    @Test
    fun clearHistoryEmptiesEveryLiveManagerAndDeletesTheFile() {
        copy("one")
        history.setPinned(entry("one").id, true)
        copy("two")
        val changesBefore = historyChanges

        history.clearHistory()

        assertTrue(history.history.isEmpty())
        assertEquals(changesBefore + 1, historyChanges)
        assertFalse(file.exists())
    }

    @Test
    fun theStaticClearReachesALiveManager() {
        copy("one")

        ClipboardHistoryManager.clearHistory(context)

        assertTrue(history.history.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 11. Teardown
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun releaseUnregistersTheManagerSoLaterClipsAreNotCaptured() {
        history.release()

        copy("after release")

        assertTrue(history.history.isEmpty())
        assertEquals(0, historyChanges)
    }

    @Test
    fun aReleasedManagerIsNotReachedByTheStaticClear() {
        copy("kept")
        history.release()

        ClipboardHistoryManager.clearHistory(context)

        assertEquals(listOf("kept"), texts())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 12. ClipboardItem.getTextFromClipData — used cross-board by FccView
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

    private companion object {
        const val START = 1_700_000_000_000L
        const val HOUR = 60L * 60L * 1000L
    }
}
