package dev.bbkb.ime.keyboard.inputboard.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * [ClipboardHistoryStore]: the JSON file format and the AtomicFile round trip.
 *
 * The format half is strict about the envelope (not JSON, not an object, another version: nothing
 * loads) and forgiving per entry (one malformed row is dropped, the rest load), the same split
 * `DistributionManifestParser` makes for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClipboardHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private val entries = listOf(
        ClipEntry(3, "pinned \"quoted\" text", "label", "text/html", 1_000, true, 2_000),
        ClipEntry(2, "line one\nline two — ünïcödé ✓", null, "text/plain", 900),
        ClipEntry(1, "", "", "text/plain", 800),
    )

    @Test
    fun entriesRoundTripThroughTheFormat() {
        assertEquals(entries, ClipboardHistoryStore.decode(ClipboardHistoryStore.encode(entries)))
    }

    @Test
    fun entriesRoundTripThroughTheFile() {
        val file = File(tmp.root, ClipboardHistoryStore.FILE_NAME)
        val store = ClipboardHistoryStore.forFile(file, direct)

        store.save(entries)

        assertTrue(file.exists())
        assertEquals(entries, ClipboardHistoryStore.forFile(file, direct).load())
    }

    @Test
    fun aMissingFileLoadsAsNothing() {
        val store = ClipboardHistoryStore.forFile(File(tmp.root, "absent.json"), direct)

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun deleteRemovesTheFile() {
        val file = File(tmp.root, ClipboardHistoryStore.FILE_NAME)
        val store = ClipboardHistoryStore.forFile(file, direct)
        store.save(entries)

        store.delete()

        assertFalse(file.exists())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun aSaveIsASnapshotOfTheListAsPassed() {
        val queued = mutableListOf<Runnable>()
        val file = File(tmp.root, ClipboardHistoryStore.FILE_NAME)
        val store = ClipboardHistoryStore.forFile(file, Executor { queued += it })
        val live = entries.toMutableList()

        store.save(live)
        live.clear()
        queued.forEach { it.run() }

        assertEquals("the IO thread writes what was saved, not what the list became", entries, store.load())
    }

    @Test
    fun theEnvelopeIsStrict() {
        assertTrue(ClipboardHistoryStore.decode("").isEmpty())
        assertTrue(ClipboardHistoryStore.decode("{ not json").isEmpty())
        assertTrue(ClipboardHistoryStore.decode("[]").isEmpty())
        assertTrue("no version", ClipboardHistoryStore.decode("""{"entries":[]}""").isEmpty())
        assertTrue(
            "a future version is ignored, not misread",
            ClipboardHistoryStore.decode(
                """{"version":2,"entries":[{"id":1,"text":"x","createdAt":1}]}""",
            ).isEmpty(),
        )
        assertTrue("entries not an array", ClipboardHistoryStore.decode("""{"version":1,"entries":{}}""").isEmpty())
    }

    @Test
    fun aMalformedEntryIsDroppedAndTheRestLoad() {
        val json = """
            {"version":1,"entries":[
              {"id":1,"text":"good","createdAt":10},
              {"id":2,"createdAt":10},
              {"id":"three","text":"bad id","createdAt":10},
              {"id":4,"text":"no time"},
              {"id":5,"text":5,"createdAt":10},
              "not an object",
              {"id":6,"text":"also good","createdAt":20,"pinned":true,"pinnedAt":30,"unknown":"ignored"}
            ]}
        """.trimIndent()

        val loaded = ClipboardHistoryStore.decode(json)

        assertEquals(listOf("good", "also good"), loaded.map { it.text })
        assertEquals("a missing mime reads as plain text", "text/plain", loaded[0].mime)
        assertEquals(30L, loaded[1].pinnedAtMs)
        assertTrue(loaded[1].isPinned)
    }

    @Test
    fun aPinnedEntryWithoutAPinTimeFallsBackToItsCopyTime() {
        val loaded = ClipboardHistoryStore.decode(
            """{"version":1,"entries":[{"id":1,"text":"x","createdAt":10,"pinned":true}]}""",
        )

        assertEquals(10L, loaded.single().pinnedAtMs)
    }
}
