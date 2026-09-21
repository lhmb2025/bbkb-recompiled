package dev.bbkb.ime.core.settings.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What corruption recovery deletes.
 *
 * The store is `filesDir/dev.bbkb.ime.basl.pdu/` holding exactly the three
 * extension-less `LoadConfig` files (`DictionaryManager.doInitBasl` passes `getFilesDir()`,
 * `PersonalDictionaryUtil.setupPduDir` appends the directory). Recovery deletes user data, so it
 * must remove those three files and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionaryRepositoryRecoveryTest {

    private lateinit var context: Context
    private lateinit var pduDir: File

    private val storeNames = listOf("substitutions", "deleted_substitutions", "personal_words")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pduDir = File(context.filesDir, "dev.bbkb.ime.basl.pdu").apply { mkdirs() }
        storeNames.forEach { File(pduDir, it).writeText("[]") }
    }

    @After
    fun tearDown() {
        pduDir.deleteRecursively()
    }

    /** Recovery deletes the three real store files. */
    @Test
    fun recoveryDeletesTheStoreFiles() {
        DictionaryRepository.clearCorruptedStore(context)

        storeNames.forEach { assertFalse("$it deleted", File(pduDir, it).exists()) }
    }

    /** ...and nothing else: not a neighbour in the store directory, not the directory, not filesDir. */
    @Test
    fun recoveryDeletesNothingButTheStoreFiles() {
        val neighbour = File(pduDir, "substitutions.json").apply { writeText("keep") }
        val tempLeftover = File(pduDir, "substitutions123tmp").apply { writeText("keep") }
        val unrelated = File(context.filesDir, "personal_words").apply { writeText("keep") }
        try {
            DictionaryRepository.clearCorruptedStore(context)

            assertTrue(pduDir.isDirectory)
            assertTrue(neighbour.exists())
            assertTrue(tempLeftover.exists())
            assertTrue("a same-named file outside the store dir", unrelated.exists())
            assertFalse(
                "no app_ directory is created as a side effect",
                File(context.filesDir.parentFile, "app_dev.bbkb.ime.basl.pdu").exists()
            )
        } finally {
            unrelated.delete()
        }
    }
}
