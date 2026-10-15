package dev.bbkb.ime.core.layout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [KdbStore]: entries round-trip whole, a damaged one reads as none, and the oldest go first. */
class KdbStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun anEntryReadsBackFileForFile() {
        val store = KdbStore(folder.newFolder())
        assertNull(store.get("a"))
        assertFalse(store.has("a"))
        store.put("a", listOf("one".toByteArray(), ByteArray(0), "three".toByteArray()))
        val back = store.get("a")!!
        assertEquals(3, back.size)
        assertArrayEquals("one".toByteArray(), back[0])
        assertEquals(0, back[1].size)
        assertArrayEquals("three".toByteArray(), back[2])
        assertTrue(store.has("a"))
        store.put("a", listOf("replaced".toByteArray()))
        assertArrayEquals("replaced".toByteArray(), store.get("a")!!.single())
    }

    @Test
    fun aDamagedEntryReadsAsNone_andIsRemoved() {
        val dir = folder.newFolder()
        val store = KdbStore(dir)
        store.put("a", listOf(ByteArray(100)))
        val file = dir.listFiles()!!.single()
        file.writeBytes(file.readBytes().copyOf(20))
        assertNull(store.get("a"))
        assertFalse(file.exists())
    }

    @Test
    fun theLeastRecentlyUsedEntriesGoFirst() {
        val dir = folder.newFolder()
        val store = KdbStore(dir)
        for (i in 0 until KdbStore.MAX_ENTRIES) {
            store.put("e$i", listOf(byteArrayOf(i.toByte())))
            File(dir, "e$i.kdbs").setLastModified(1_000_000L + i * 1000L)
        }
        store.put("new", listOf(byteArrayOf(1)))
        assertFalse("the oldest went", store.has("e0"))
        assertTrue(store.has("e1"))
        assertTrue(store.has("new"))
        assertEquals(KdbStore.MAX_ENTRIES, dir.listFiles()!!.count { it.name.endsWith(".kdbs") })
    }

    @Test
    fun keysAreShortHashesOfTheirParts() {
        val key = KdbStore.key("pkb", "athena", "x")
        assertEquals(32, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(key, KdbStore.key("pkb", "athena", "x"))
        assertNotEquals("parts are kept apart", KdbStore.key("ab", "c"), KdbStore.key("a", "bc"))
    }
}
