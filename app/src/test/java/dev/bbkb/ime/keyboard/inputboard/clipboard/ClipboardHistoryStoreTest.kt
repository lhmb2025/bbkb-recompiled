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
 * [ClipboardHistoryStore]'s JSON format and AtomicFile round trip: strict about the envelope (nothing loads),
 * forgiving per entry (a malformed row is dropped), as `DistributionManifestParser` is.
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

    /**
     * The bytes Gson wrote for [entries] (compact, no HTML escaping, missing label omitted); the org.json
     * writer matches them.
     */
    private val gsonWritten =
        """{"version":1,"entries":[""" +
            """{"id":3,"text":"pinned \"quoted\" text","label":"label","mime":"text/html","createdAt":1000,"pinned":true,"pinnedAt":2000},""" +
            """{"id":2,"text":"line one\nline two — ünïcödé ✓","mime":"text/plain","createdAt":900,"pinned":false,"pinnedAt":0},""" +
            """{"id":1,"text":"","label":"","mime":"text/plain","createdAt":800,"pinned":false,"pinnedAt":0}]}"""

    @Test
    fun aFileTheGsonBuildWroteLoadsAndIsWrittenTheSameWay() {
        assertEquals(entries, ClipboardHistoryStore.decode(gsonWritten))
        assertEquals(gsonWritten, ClipboardHistoryStore.encode(entries))
    }

    @Test
    fun escapedTextReadsBack() {
        val loaded = ClipboardHistoryStore.decode(
            """{"version":1,"entries":[{"id":1,"text":"a \u003c b \u0026 c \u003d d\u0027s https:\/\/e.test","createdAt":1}]}""",
        )
        assertEquals("a < b & c = d's https://e.test", loaded.single().text)
    }

    @Test
    fun anythingAfterTheEnvelopeIsNotJson() {
        assertTrue(ClipboardHistoryStore.decode("""{"version":1,"entries":[]} trailing""").isEmpty())
        assertTrue(ClipboardHistoryStore.decode("""{"version":1,"entries":[]}{}""").isEmpty())
        assertEquals(
            "whitespace after it is fine",
            1,
            ClipboardHistoryStore.decode("""{"version":1,"entries":[{"id":1,"text":"x","createdAt":1}]}""" + "\n  ").size,
        )
    }

    @Test
    fun numbersAreNotReadFromStringsAndFractionsTruncateAsGsonDid() {
        val loaded = ClipboardHistoryStore.decode(
            """{"version":1.0,"entries":[{"id":"1","text":"string id","createdAt":1},""" +
                """{"id":2.7,"text":"fraction","createdAt":10,"pinned":"true"},""" +
                """{"id":3,"text":"null label","label":null,"mime":null,"createdAt":5,"pinned":null}]}""",
        )
        assertEquals(listOf("fraction", "null label"), loaded.map { it.text })
        assertEquals(2L, loaded[0].id)
        assertFalse("a quoted boolean is not a boolean", loaded[0].isPinned)
        assertEquals(null, loaded[1].label)
        assertEquals("text/plain", loaded[1].mime)
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
