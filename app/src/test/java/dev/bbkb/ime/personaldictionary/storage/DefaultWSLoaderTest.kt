package dev.bbkb.ime.personaldictionary.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The built-in shortcuts in assets/substitution_macros: each macros.json is a JSON array of
 * "<caps flag> <key> <expansion>" strings. Robolectric for the real assets and `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultWSLoaderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = DefaultWSLoader.invalidateCache()

    private fun load(locale: Locale, base: Boolean = false) =
        DefaultWSLoader.load(context, ROOT, listOf(locale), emptySet(), base)

    @Test
    fun englishLoadsItsShortcutsAndTheBaseOnes() {
        val map = load(Locale.ENGLISH, base = true)
        assertEquals("a lot", map["alot"]?.word)
        assertEquals("it's a", map["itsa"]?.word)
        assertEquals("BlackBerry", map["bb"]?.word)
        assertEquals("I", map["i"]?.word)
        assertTrue(map.values.all { it.type == WordSubstitution.Type.DEFAULT })
    }

    @Test
    fun deletedKeysAreLeftOut() {
        val map = DefaultWSLoader.load(context, ROOT, listOf(Locale.ENGLISH), setOf("alot"), false)
        assertEquals(null, map["alot"])
        assertEquals("it's a", map["itsa"]?.word)
    }

    /** Every language directory's file parses: none comes back empty. */
    @Test
    fun everyShippedFileLoads() {
        // Robolectric's list() can answer with the files below as "ca/macros.json".
        val dirs = context.assets.list(ROOT)!!.map { it.substringBefore('/') }.filter { it != "macros.json" }.distinct()
        assertTrue(dirs.size > 10)
        for (dir in dirs) {
            DefaultWSLoader.invalidateCache()
            val parts = dir.split("_")
            val locale = if (parts.size > 1) Locale(parts[0], parts[1]) else Locale(parts[0])
            assertTrue("$dir loaded nothing", load(locale).isNotEmpty())
        }
    }

    private companion object {
        const val ROOT = "substitution_macros"
    }
}
