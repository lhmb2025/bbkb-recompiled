package dev.bbkb.ime.keyboard.inputboard.emoji

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * The compact asset (generateEmojibaseCompact in app/build.gradle) must load to exactly what the
 * old Gson parse of emojibase's data.json loaded: same entries in the same order with the same
 * fields, the same skin variants, and so the same categories and indexes. The reference is the
 * JSON in node_modules, parsed the way the app used to; the test is skipped when npm has not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojibaseCompactAssetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun compact(): EmojibaseCompactFormat.Parsed =
        context.assets.open(EmojibaseCompactFormat.ASSET).bufferedReader(Charsets.UTF_8).use {
            EmojibaseCompactFormat.read(it)
        }

    /** Relative to the app module, which is the unit tests' working directory. */
    private val dataJson = File("node_modules/emojibase-data/en/data.json")

    @Before
    fun needsTheJson() = assumeTrue("no ${dataJson.absolutePath} (npm install not run)", dataJson.isFile)

    /** The pre-compact-asset load, verbatim: Gson reflecting into EmojiData through its @SerializedName names. */
    private fun json(): List<EmojiData> = dataJson.reader(Charsets.UTF_8).use { reader ->
        Gson().fromJson<List<EmojiData>>(reader, object : TypeToken<List<EmojiData>>() {}.type)
    }

    @Test
    fun theAssetWasGeneratedFromTheJson() {
        val bytes = dataJson.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertTrue(compact().identity, compact().identity.endsWith("sha256=$sha"))
    }

    @Test
    fun everyEntryMatchesTheJsonParse() {
        val fromJson = json()
        val fromCompact = compact().entries
        assertEquals(1941, fromJson.size)
        assertEquals(fromJson.size, fromCompact.size)
        for (i in fromJson.indices) {
            val j = fromJson[i]
            val c = fromCompact[i]
            // Everything but the skins' own metadata, which the asset leaves out on purpose.
            assertEquals("entry $i", j.copy(skins = null), c.copy(skins = null))
            assertEquals("skins of entry $i", j.skins?.map { it.emoji }, c.skins?.map { it.emoji })
        }
        assertEquals(1875, fromCompact.sumOf { it.skins?.size ?: 0 })
    }

    @Test
    fun theCategoriesAndIndexesMatchTheJsonParse() {
        val a = EmojibaseDataProvider(context).apply { applyDataset(json()) { BooleanArray(it.size) { true } } }
        val b = EmojibaseDataProvider(context).apply { applyDataset(compact().entries) { BooleanArray(it.size) { true } } }
        for (category in EmojiCategory.values()) {
            assertEquals(category.name, a.getEmojisByCategory(category).map(::norm), b.getEmojisByCategory(category).map(::norm))
        }
        assertEquals(a.getAllEmojis().map(::norm), b.getAllEmojis().map(::norm))
        assertTrue(b.getAllEmojis().size > 1500)
        assertEquals(skins(a), skins(b))
        for (e in b.getAllEmojis()) assertEquals(a.getSkinToneVariants(e.emoji), b.getSkinToneVariants(e.emoji))
    }

    private fun norm(e: EmojiData) = e.copy(skins = null) to e.skins?.map { it.emoji }

    @Suppress("UNCHECKED_CAST")
    private fun skins(p: EmojibaseDataProvider) =
        EmojiTestEnv.read(p, "skinsByEmoji") as Map<String, List<String>>

    @Test
    fun aRealLoadReadsTheCompactAsset() {
        // Robolectric has no emoji font, so nothing survives the glyph filter; this only proves the
        // load path runs end to end (and writes the glyph cache) without falling back or throwing.
        val p = EmojibaseDataProvider(context)
        p.loadEmojiData()
        assertTrue(java.io.File(context.cacheDir, EmojiGlyphCache.FILE_NAME).isFile)
    }
}
