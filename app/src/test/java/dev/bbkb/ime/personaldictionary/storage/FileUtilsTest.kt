package dev.bbkb.ime.personaldictionary.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [FileUtils.writeStringToFile]: the dictionary's JSON files are replaced whole, and nothing is
 * left beside them. Robolectric only for `android.util.Log`, which AtomicFile logs through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FileUtilsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writingOverAFileReplacesItAndLeavesNothingBeside() {
        val dir = folder.newFolder("pdu")
        FileUtils.writeStringToFile("personal_words", "[\"first\"]", dir)
        FileUtils.writeStringToFile("personal_words", "[\"second\"]", dir)

        assertEquals("[\"second\"]", FileUtils.readFileToString(File(dir, "personal_words").path))
        assertEquals(listOf("personal_words"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun aWriteACrashLeftHalfDoneIsReplacedByTheNextOne() {
        val dir = folder.newFolder("pdu")
        FileUtils.writeStringToFile("personal_words", "[\"first\"]", dir)
        File(dir, "personal_words.new").writeText("[\"hal")

        assertEquals("the stale .new is not read", "[\"first\"]", FileUtils.readFileToString(File(dir, "personal_words").path))
        FileUtils.writeStringToFile("personal_words", "[\"second\"]", dir)

        assertEquals("[\"second\"]", FileUtils.readFileToString(File(dir, "personal_words").path))
        assertEquals(listOf("personal_words"), dir.listFiles()!!.map { it.name })
    }
}
