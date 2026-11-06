package dev.bbkb.ime.keyboard.inputboard.emoji

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
 * JSON in node_modules, read with org.json into what that Gson parse produced (see [json]); the
 * test is skipped when npm has not run.
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

    /**
     * The pre-compact-asset load, reproduced: Gson reflected into EmojiData through @SerializedName
     * names (emoji, hexcode, label, tags, shortcodes, group, subgroup, version, order, type,
     * gender, tone, skins). It allocated EmojiData without running a constructor, so a missing
     * non-null number (group, subgroup and order on the component entries; version) became 0, a
     * missing nullable field null, and unknown keys (text, emoticon) were ignored. A skin's tone
     * is left null: it is an Int or an array, nothing reads it, and the asset does not carry it.
     */
    private fun json(): List<EmojiData> {
        val array = JSONArray(dataJson.readText(Charsets.UTF_8))
        return List(array.length()) { emojiData(array.getJSONObject(it)) }
    }

    private fun emojiData(o: JSONObject) = EmojiData(
        emoji = o.getString("emoji"),
        codepoints = o.getString("hexcode"),
        description = o.getString("label"),
        tags = o.strings("tags"),
        aliases = o.strings("shortcodes"),
        group = o.optInt("group", 0),
        subgroup = o.optInt("subgroup", 0),
        emojiVersion = o.optDouble("version", 0.0),
        order = o.optInt("order", 0),
        type = o.intOrNull("type"),
        gender = o.intOrNull("gender"),
        tone = o.intOrNull("tone"),
        skins = o.optJSONArray("skins")?.let { skins ->
            List(skins.length()) {
                val skin = skins.getJSONObject(it)
                EmojiData.EmojiSkin(
                    emoji = skin.getString("emoji"),
                    hexcode = skin.getString("hexcode"),
                    label = skin.opt("label") as? String,
                )
            }
        },
    )

    private fun JSONObject.strings(name: String): List<String>? =
        optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it) } }

    private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)

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
