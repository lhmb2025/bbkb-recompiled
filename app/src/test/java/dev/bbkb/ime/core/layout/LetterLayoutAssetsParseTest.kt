package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.subtypeswitcher.SideloadedSubtypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Every letter layout the app ships (`assets/letter_layouts/<id>.xml`) reads with the reader the
 * registry itself uses, [LetterLayoutXml], as a built-in: the generated files open with a comment
 * and carry no XML declaration, and a format change on either side (generator or reader) that
 * left them unreadable would otherwise only show on a phone, as keys quietly typing their old
 * letters. The documents are opened from the APK's assets through the application context, as
 * the registry opens them.
 *
 * It also holds the documents' `layoutSet` bindings to the keyboards that exist: a document bound
 * to a layout set must be for a language that has a keyboard with that layout set (in
 * `res/xml/method.xml`, or one of the built-in translit keyboards the app adds from
 * `predefined_builtin_keyboard_subtypes`), or it could never be selected. And it asks the
 * registry, over the shipped documents, which one each keyboard gets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutAssetsParseTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // reinit, not init: Robolectric reuses the class loader, and init is a no-op after the first.
        ResourceLocaleUtils.reinit(context)
    }

    /** One keyboard: its language in the app's spelling and the layout set it is built from. */
    private data class Keyboard(val name: String, val language: String, val layoutSet: String)

    private fun ids(): List<String> =
        context.assets.list(LetterLayoutRegistry.ASSET_DIRECTORY).orEmpty()
            .filter { it.endsWith(LetterLayoutXml.FILE_SUFFIX) }
            .map { it.removeSuffix(LetterLayoutXml.FILE_SUFFIX) }
            .sorted()

    private fun open(id: String): InputStream =
        context.assets.open("${LetterLayoutRegistry.ASSET_DIRECTORY}/$id${LetterLayoutXml.FILE_SUFFIX}")

    /** Every shipped document, parsed as the registry parses a built-in. */
    private fun layouts(): Map<String, LetterLayout> = ids().associateWith { id ->
        try {
            open(id).use { LetterLayoutXml.parse(it, LetterLayout.Origin.BUILT_IN) }
        } catch (e: InvalidLetterLayoutException) {
            throw AssertionError("$id.xml does not read: ${e.message}", e)
        }
    }

    /**
     * method.xml's keyboard subtypes, with the layout set the app gives each (the extra value, or
     * the locale-and-extra map), then the translit keyboards the app adds itself.
     */
    private fun keyboards(): List<Keyboard> {
        val out = ArrayList<Keyboard>()
        val method = LetterLayoutsMatchEngineTest.moduleFile(
            "src/main/res/xml/method.xml", "app/src/main/res/xml/method.xml", "../app/src/main/res/xml/method.xml",
        )
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(method.reader(Charsets.UTF_8))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG || p.name != "subtype") continue
            if (p.getAttributeValue(ANDROID, "imeSubtypeMode") != "keyboard") continue
            val subtype = InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeLocale(p.getAttributeValue(ANDROID, "imeSubtypeLocale"))
                .setSubtypeMode("keyboard")
                .setSubtypeExtraValue(p.getAttributeValue(ANDROID, "imeSubtypeExtraValue").orEmpty())
                .build()
            val language = LocaleUtils.languageCode(ResourceLocaleUtils.getSubtypeLocale(subtype))
            val layoutSet = ResourceLocaleUtils.getKeyboardLayoutSetName(subtype)
            out.add(Keyboard("${subtype.locale}:$layoutSet", language, layoutSet))
        }
        assertTrue("method.xml gave only ${out.size} keyboards", out.size >= 80)
        for (item in context.resources.getStringArray(R.array.predefined_builtin_keyboard_subtypes)) {
            val (locale, layoutSet) = item.split(':')
            val language = LocaleUtils.languageCode(LocaleUtils.constructLocaleFromString(locale)!!)
            out.add(Keyboard(item, language, layoutSet))
        }
        // The runtime subtypes a language gets once its pack is installed.
        for ((language, layoutSet) in SideloadedSubtypes.layouts()) {
            out.add(Keyboard("$language:$layoutSet", language, layoutSet))
        }
        return out
    }

    @Test
    fun everyShippedDocumentReadsWithTheRegistrysReader() {
        val layouts = layouts()
        assertTrue("only ${layouts.size} documents under assets/${LetterLayoutRegistry.ASSET_DIRECTORY}", layouts.size >= 50)
        for ((id, layout) in layouts) {
            assertEquals("$id.xml: the file name is the id", id, layout.id)
            assertEquals(LetterLayout.Origin.BUILT_IN, layout.origin)
            assertTrue("$id.xml: names keys", layout.keyCount > 0)
            val head = open(id).use { it.readBytes() }.toString(Charsets.UTF_8)
            if (id in SOURCE_DOCUMENTS) {
                // Kept by hand: the generator reads them for the on-screen rows and never writes them.
                assertTrue("$id.xml: a source document, with a declaration", head.startsWith("<?xml"))
                assertTrue("$id.xml: never generated", !head.contains("<!-- GENERATED"))
                // The on-screen layouts (QWERTY, AZERTY, QWERTZ, Colemak, Dvorak) are not keypad layouts.
                assertEquals("$id.xml: for the on-screen keyboard only", setOf(LetterLayout.Target.VKB), layout.targets)
            } else if (id in KEYPAD_SOURCE_DOCUMENTS) {
                // The etched keypad editions, transcribed by hand: for the physical keys, offered
                // to their languages as a choice and never applied by themselves.
                assertTrue("$id.xml: a source document, with a declaration", head.startsWith("<?xml"))
                assertTrue("$id.xml: never generated", !head.contains("<!-- GENERATED"))
                assertEquals("$id.xml: for the physical keys", setOf(LetterLayout.Target.PKB), layout.targets)
                assertTrue("$id.xml: opt-in", layout.optIn)
                assertTrue("$id.xml: bound to its language", layout.binding.isLanguageBound)
            } else {
                // Generated files begin with their GENERATED comment, the form without a declaration.
                assertTrue("$id.xml: opens with the generator's comment", head.startsWith("<!-- GENERATED"))
                assertEquals("$id.xml: for the physical keys", setOf(LetterLayout.Target.PKB), layout.targets)
            }
        }
        assertTrue(layouts.keys.containsAll(SOURCE_DOCUMENTS))
        assertTrue(layouts.keys.containsAll(KEYPAD_SOURCE_DOCUMENTS))
    }

    @Test
    fun theRestoredLigaturesAreReadAsSingleEntries() {
        val layouts = layouts()
        fun hold(id: String, keyCode: Int) = layouts.getValue(id).keys.getValue(keyCode).moreKeys.toList()
        // Arabic lam-alef, Hebrew geresh forms, Catalan l·l, Uzbek oʻ and gʻ: one entry each.
        assertTrue(hold("ar", android.view.KeyEvent.KEYCODE_F).containsAll(listOf(text(0x644, 0x627), text(0x644, 0x625))))
        assertTrue(hold("iw", android.view.KeyEvent.KEYCODE_M).contains(text(0x5E6, 0x5F3)))
        assertEquals(text(0x6C, 0xB7, 0x6C), hold("ca", android.view.KeyEvent.KEYCODE_L).first())
        assertEquals(listOf(text(0x6F, 0x2BB)), hold("uz", android.view.KeyEvent.KEYCODE_O))
        assertEquals(listOf(text(0x67, 0x2BB)), hold("uz", android.view.KeyEvent.KEYCODE_G))
        // Vietnamese keeps all 17 of its a and o entries.
        assertEquals(17, hold("vi", android.view.KeyEvent.KEYCODE_A).size)
        assertEquals(17, hold("vi", android.view.KeyEvent.KEYCODE_O).size)
    }

    @Test
    fun aDocumentBoundToALayoutSetIsForAKeyboardThatHasIt() {
        val keyboards = keyboards()
        var bound = 0
        for ((id, layout) in layouts()) {
            val layoutSet = layout.binding.layoutSet
            if (layoutSet == LetterLayout.ANY) continue
            bound++
            assertTrue("$id.xml is bound to layout set $layoutSet but to no language", layout.binding.isLanguageBound)
            for (language in layout.binding.languages) {
                assertTrue(
                    "$id.xml binds $language on layout set $layoutSet, and no $language keyboard uses it " +
                        "(keyboards: ${keyboards.filter { it.language == language }.map { it.name }})",
                    keyboards.any { it.language == language && it.layoutSet == layoutSet },
                )
            }
        }
        // The seven script languages, the three translit keyboards, Chinese pinyin and cangjie
        // (bound apart so that no document reaches the stroke and Zhuyin keyboards), and the four
        // Cyrillic languages on the Russian key map (Kyrgyz, Kazakh, Tajik, Tatar).
        assertEquals("documents bound to a layout set", 18, bound)
    }

    @Test
    fun eachKeyboardGetsTheDocumentForItsLayoutSet() {
        val registry = LetterLayoutRegistry(context, object : LetterLayoutRegistry.BuiltInDocuments {
            override fun ids(): List<String> = this@LetterLayoutAssetsParseTest.ids()
            override fun open(id: String): InputStream? = this@LetterLayoutAssetsParseTest.open(id)
        })
        val keyboards = keyboards()
        val chosen = keyboards.associate { it.name to registry.builtIn(it.language, "qwerty", "4row", it.layoutSet) }
        for (keyboard in keyboards) {
            val layout = chosen[keyboard.name] ?: continue
            assertTrue("${keyboard.name} got ${layout.id}.xml, not bound to its language", layout.binding.matchesLanguage(keyboard.language))
            assertTrue(
                "${keyboard.name} got ${layout.id}.xml, bound to layout set ${layout.binding.layoutSet}",
                layout.binding.layoutSet == LetterLayout.ANY || layout.binding.layoutSet == keyboard.layoutSet,
            )
        }
        val expected = mapOf(
            "ru:east_slavic" to "ru", "ru:translit" to "ru-translit",
            "uk:east_slavic" to "uk", "uk:translit" to "uk-translit",
            "be:east_slavic" to "be", "be:translit" to "be-translit",
            "ar:arabic" to "ar", "iw:hebrew" to "iw", "el:greek" to "el", "ko:hangul" to "ko",
            "en_US:qwerty" to "en", "de:qwertz" to "de", "fr:azerty" to "fr",
            "zh_CN_pinyin:pinyin" to "zh-pinyin", "zh_TW_pinyin:pinyin" to "zh-pinyin",
            "zh_HK_cangjie:cangjie" to "zh-cangjie",
        )
        for ((name, id) in expected) {
            assertTrue("no keyboard $name in ${chosen.keys}", chosen.containsKey(name))
            assertNotNull("$name got no document", chosen[name])
            assertEquals(name, id, chosen[name]?.id)
        }
        assertEquals("Kyrgyz types Cyrillic on the keypad, with a translit document of its own", "ky-translit", chosen["ky:translit"]?.id)
        for (name in listOf("zh_CN_stroke:stroke", "zh_HK_stroke:stroke", "zh_TW_zhuyin:zhuyin", "zh_TW_stroke:stroke")) {
            assertTrue("no keyboard $name in ${chosen.keys}", chosen.containsKey(name))
            assertNull("$name keeps the firmware key map", chosen[name])
        }
    }

    private fun text(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** The documents kept by hand rather than generated: the on-screen keyboard's sources. */
        val SOURCE_DOCUMENTS = setOf("azerty", "colemak", "dvorak", "qwerty", "qwertz")

        /** The etched keypad editions' layouts, transcribed by hand. */
        val KEYPAD_SOURCE_DOCUMENTS = setOf("thai-keypad", "zhuyin-keypad")
    }
}
