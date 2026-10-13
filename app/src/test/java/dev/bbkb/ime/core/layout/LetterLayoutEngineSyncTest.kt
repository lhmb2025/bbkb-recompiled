package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.blackberry.nuanceshim.Xt9KdbVariant
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * The engine's key letters following the active letter layout ([LetterLayoutEngineSync]), end to
 * end through [Xt9KdbVariant] with only the owned library's two registry calls replaced by a
 * recorder: which KDBs a layout registers and from which device files, which variant is selected
 * for which keyboard, and when nothing needs doing at all.
 *
 * The KDB files are the shipped ones, read off disk; the device variant comes from the app's
 * assets, as on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutEngineSyncTest {

    /** The native registry: variant -> primaryId -> the bytes last registered for it. */
    private class Recorder : Xt9KdbVariant.NativeBridge {
        val files: MutableMap<String, MutableMap<Int, ByteArray>> = Collections.synchronizedMap(LinkedHashMap())
        val registrations: MutableList<String> = Collections.synchronizedList(ArrayList())
        val selections: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun register(variant: String, xml: ByteArray): Int {
            val pid = PRIMARY_ID.find(String(xml, Charsets.UTF_8))?.groupValues?.get(1)?.toInt() ?: return -1
            files.getOrPut(variant) { LinkedHashMap() }[pid] = xml
            registrations += variant
            return 0
        }

        override fun setVariant(variant: String) {
            selections += variant
        }

        fun count(variant: String) = registrations.count { it == variant }
        fun layoutRegistrations() = registrations.count { it.startsWith(LetterLayoutKdb.VARIANT_PREFIX) }

        companion object {
            val PRIMARY_ID = Regex("primaryId=\"(\\d+)\"")
        }
    }

    /** The shipped KDB files on disk. */
    private class DiskKdbFiles(private val root: File) : LetterLayoutEngineSync.KdbFiles {
        override fun list(variant: String, template: Template): List<String> =
            dir(variant).list()?.filter { it.endsWith(template.fileSuffix) } ?: emptyList()

        override fun read(variant: String, name: String): ByteArray? =
            File(dir(variant), name).takeIf { it.isFile }?.readBytes()

        private fun dir(variant: String) = if (variant.isEmpty()) root else File(root, variant)
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val native = Recorder()

    private var activeId = ""
    private var active: LetterLayout? = null
    private var language: String? = "en"
    private var keypad: String? = "qwerty"
    private var layoutSet: String? = null
    private var submits = 0

    /** [Xt9KdbVariant] as the app uses it, counting the jobs queued. */
    private val engine = object : LetterLayoutEngineSync.Engine by LetterLayoutEngineSync.XtEngine {
        override fun submit(job: Runnable) {
            submits++
            LetterLayoutEngineSync.XtEngine.submit(job)
        }
    }

    private val latin: (String?) -> (Int) -> Int = { _ -> { keyCode -> LetterLayoutEngineSync.latinLetter(keyCode) } }

    @Before
    fun setUp() {
        Xt9KdbVariant.setNativeBridgeForTest(native)
        LetterLayoutEngineSync.setInstanceForTest(newSync())
    }

    @After
    fun tearDown() {
        LetterLayoutEngineSync.setInstanceForTest(null)
        Xt9KdbVariant.setNativeBridgeForTest(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
        LetterLayoutRegistry.directoryOf(context).deleteRecursively()
        LetterLayoutRegistry.invalidate()
    }

    private fun newSync(
        activeId: () -> String = { this.activeId },
        activeLayout: () -> LetterLayout? = { active },
        letters: (String?) -> (Int) -> Int = latin,
    ) = LetterLayoutEngineSync(
        engine = engine,
        kdbFiles = DiskKdbFiles(kdbDir()),
        activeId = activeId,
        activeLayout = activeLayout,
        currentLanguage = { language },
        keypad = { keypad },
        keypadType = { null },
        layoutSet = { layoutSet },
        engineLetters = letters,
    )

    private fun kdbDir(): File {
        val dir = sequenceOf("src/main/assets/kdb", "app/src/main/assets/kdb", "../app/src/main/assets/kdb")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("assets/kdb not found from ${File(".").canonicalPath}", dir)
        return dir!!
    }

    private fun settle() = Xt9KdbVariant.awaitReady(5000)

    private fun deviceVariant(variant: String) {
        Xt9KdbVariant.apply(context, variant)
        settle()
    }

    private fun activate(layout: LetterLayout?) {
        active = layout
        activeId = layout?.id ?: ""
        LetterLayoutRegistry.invalidate()
        settle()
    }

    private fun keyboard(language: String?) {
        this.language = language
        LetterLayoutEngineSync.beforeEngineLayoutSync(true)
        settle()
    }

    private fun layout(keys: String, id: String = "swap", languages: String = "", extra: String = "") =
        LetterLayoutXml.parse(
            """<letter-layout version="1" id="$id" name="Test" $extra>
                <bind languages="$languages" keypad="qwerty"/>
                $keys
            </letter-layout>"""
        )

    private val swap get() = layout("""<key code="KEYCODE_Q" keySpec="w"/><key code="KEYCODE_W" keySpec="q"/>""")

    private fun registered(variant: String, primaryId: Int): String =
        String(native.files[variant]!![primaryId]!!, Charsets.UTF_8)

    private fun kdbFile(name: String, variant: String = ""): String =
        File(if (variant.isEmpty()) kdbDir() else File(kdbDir(), variant), "${name}_pkb.xml").readText(Charsets.UTF_8)

    // ── following a layout ───────────────────────────────────────────────────

    @Test
    fun withNoLayout_theDeviceVariantStaysAndNothingIsBuilt() {
        deviceVariant("athena")
        activate(null)
        assertEquals("athena", Xt9KdbVariant.applied())
        assertEquals(0, native.layoutRegistrations())
    }

    @Test
    fun aLetterChangingLayout_isBuiltFromEveryDeviceKdbAndSelected() {
        deviceVariant("athena")
        activate(swap)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals("the device variant is still athena", "athena", Xt9KdbVariant.deviceVariant())
        assertEquals("every PKB KDB, so none falls back to the root copy", 40, native.files["layout:swap"]!!.size)
        // English: athena's uneven rows, with the layout's letters.
        val english = registered("layout:swap", 9)
        assertEquals(LetterLayoutKdb.rewrite(kdbFile("qwerty", "athena"), swap), english)
        assertTrue(english.contains("keyHeight=\"90dp\""))
        assertEquals(listOf('w'.code), LetterLayoutKdb.codesByKeyCode(english)[KeyEvent.KEYCODE_Q]!!.toList())
        // Arabic too: the selection, not the files, follows the binding.
        assertEquals(listOf('w'.code), LetterLayoutKdb.codesByKeyCode(registered("layout:swap", 1))[KeyEvent.KEYCODE_Q]!!.toList())
    }

    @Test
    fun onTheRootLayouts_theRootKdbsAreRewritten() {
        deviceVariant("")
        activate(swap)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals(LetterLayoutKdb.rewrite(kdbFile("qwerty"), swap), registered("layout:swap", 9))
    }

    @Test
    fun aLayoutThatChangesNoLetter_leavesTheEngineAlone() {
        deviceVariant("athena")
        activate(layout("""<key code="KEYCODE_Q" keySpec="q" alt="#" multitapKeys="ꝗ"/><key code="KEYCODE_PERIOD" keySpec="·"/>"""))
        assertEquals("athena", Xt9KdbVariant.applied())
        assertEquals(0, native.layoutRegistrations())
    }

    @Test
    fun switchingTheLayoutOff_bringsTheDeviceVariantBack() {
        deviceVariant("athena")
        activate(swap)
        activate(null)
        assertEquals("athena", Xt9KdbVariant.applied())
        assertEquals(listOf("athena", "layout:swap", "athena"), native.selections)
    }

    @Test
    fun aLayoutForOneLanguage_followsTheKeyboard_withoutRebuilding() {
        deviceVariant("athena")
        language = "en"
        activate(layout("""<key code="KEYCODE_Y" keySpec="z"/><key code="KEYCODE_Z" keySpec="y"/>""", id = "de-qwertz", languages = "de"))
        assertEquals("not for English", "athena", Xt9KdbVariant.applied())
        keyboard("de")
        assertEquals("layout:de-qwertz", Xt9KdbVariant.applied())
        keyboard("en")
        assertEquals("athena", Xt9KdbVariant.applied())
        keyboard("de")
        assertEquals("layout:de-qwertz", Xt9KdbVariant.applied())
        assertEquals("built once", 40, native.count("layout:de-qwertz"))
    }

    @Test
    fun editingTheActiveLayout_rebuildsItsKdbs() {
        deviceVariant("athena")
        activate(swap)
        val edited = layout("""<key code="KEYCODE_Q" keySpec="e"/><key code="KEYCODE_E" keySpec="q"/>""")
        activate(edited)
        assertEquals(80, native.count("layout:swap"))
        assertEquals(listOf('e'.code), LetterLayoutKdb.codesByKeyCode(registered("layout:swap", 9))[KeyEvent.KEYCODE_Q]!!.toList())
        assertEquals("layout:swap", Xt9KdbVariant.applied())
    }

    @Test
    fun aNewDeviceVariant_rebuildsTheLayoutFromItsFiles() {
        deviceVariant("")
        activate(swap)
        assertTrue(registered("layout:swap", 9).contains("keyHeight=\"108dp\""))
        deviceVariant("athena")
        assertEquals(LetterLayoutKdb.rewrite(kdbFile("qwerty", "athena"), swap), registered("layout:swap", 9))
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals("athena", Xt9KdbVariant.deviceVariant())
    }

    @Test
    fun beforeTheDeviceVariantIsApplied_nothingIsBuilt_thenItIs() {
        activate(swap)
        assertEquals(0, native.layoutRegistrations())
        assertNull(Xt9KdbVariant.applied())
        deviceVariant("athena")
        assertEquals("layout:swap", Xt9KdbVariant.applied())
    }

    @Test
    fun aSyncWithNothingNew_queuesNothing() {
        deviceVariant("athena")
        activate(swap)
        val before = submits
        repeat(5) { keyboard("en") }
        assertEquals(before, submits)
        keyboard("de")
        assertEquals(before + 1, submits)
    }

    @Test
    fun onScreenLayoutSyncs_changeNothing() {
        deviceVariant("athena")
        activate(swap)
        val before = submits
        language = "de"
        LetterLayoutEngineSync.beforeEngineLayoutSync(false)
        settle()
        assertEquals(before, submits)
    }

    @Test
    fun aLayoutTheEngineIsNotFor_leavesItAlone() {
        deviceVariant("athena")
        activate(layout("""<key code="KEYCODE_Q" keySpec="w"/>""", extra = "targets=\"vkb\""))
        assertEquals("on-screen only", "athena", Xt9KdbVariant.applied())
        keypad = "azerty"
        activate(swap)
        assertEquals("bound to a QWERTY keypad", "athena", Xt9KdbVariant.applied())
        keypad = null
        activate(swap)
        assertEquals("an unknown keypad is not held against it", "layout:swap", Xt9KdbVariant.applied())
    }

    @Test
    fun withoutTheOwnedModule_nothingIsQueued() {
        Xt9KdbVariant.setNativeBridgeForTest(null)
        activate(swap)
        keyboard("de")
        assertEquals(0, submits)
    }

    // ── the real sources ─────────────────────────────────────────────────────

    @Test
    fun theRegistryDrivesIt() {
        deviceVariant("athena")
        LetterLayoutEngineSync.setInstanceForTest(
            newSync(
                activeId = { PrefsManager.getPrefs(context).getString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, "") ?: "" },
                activeLayout = { LetterLayoutRegistry.activeForTyping(context) },
            )
        )
        val registry = LetterLayoutRegistry(context)
        registry.save(swap)
        settle()
        assertEquals("saved, not yet chosen", "athena", Xt9KdbVariant.applied())
        registry.activeId = "swap"
        settle()
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        registry.delete("swap")
        settle()
        assertEquals("athena", Xt9KdbVariant.applied())
    }

    @Test
    fun aScriptKeyboardIsComparedWithItsOwnLetters() {
        val builtIns = LetterLayoutRegistry(context)
        fun engineLetters(language: String?, layoutSet: String?) =
            LetterLayoutEngineSync.engineLettersFor(builtIns, language, "qwerty", null, layoutSet)
        assertEquals('ص'.code, engineLetters("ar", "arabic")(KeyEvent.KEYCODE_Q))
        assertEquals('q'.code, engineLetters("en", "qwerty")(KeyEvent.KEYCODE_Q))
        assertEquals('q'.code, engineLetters(null, null)(KeyEvent.KEYCODE_Q))
        // Russian has a document per layout set; both carry the engine's Cyrillic letters.
        assertEquals('й'.code, engineLetters("ru", "east_slavic")(KeyEvent.KEYCODE_Q))
        assertEquals('й'.code, engineLetters("ru", "translit")(KeyEvent.KEYCODE_Q))

        // The Arabic keycaps written out as a layout change nothing on an Arabic keyboard, and
        // every letter on an English one.
        LetterLayoutEngineSync.setInstanceForTest(newSync(letters = { engineLetters(it, layoutSet) }))
        deviceVariant("athena")
        val keycaps = layout("""<key code="KEYCODE_Q" keySpec="ص" characterMapKeys="ض"/><key code="KEYCODE_W" keySpec="ث"/>""", id = "keycaps")
        language = "ar"
        layoutSet = "arabic"
        activate(keycaps)
        assertEquals("athena", Xt9KdbVariant.applied())
        layoutSet = "qwerty"
        keyboard("en")
        assertEquals("layout:keycaps", Xt9KdbVariant.applied())
    }

    @Test
    fun theLettersALayoutIsComparedWith_areTheShippedKdbs() {
        // What a layout is compared with must be what the engine really has on each key, or a
        // layout of the keycaps would switch the engine (harmless) and, worse, one that changes a
        // letter could be taken for one that does not.
        val builtIns = LetterLayoutRegistry(context)
        val keyboards = listOf(
            Triple("ar", "arabic", "arabic"), Triple("ru", "east_slavic", "east_slavic"),
            Triple("ru", "translit", "east_slavic"), Triple("uk", "east_slavic", "ukranian"),
            Triple("uk", "translit", "ukranian"), Triple("be", "east_slavic", "belarusian"),
            Triple("be", "translit", "belarusian"), Triple("iw", "hebrew", "hebrew"),
            Triple("el", "greek", "greek"), Triple("ko", "hangul", "korean"),
            Triple("en", "qwerty", "qwerty"), Triple("de", "qwertz", "qwertz"), Triple("fr", "azerty", "azerty"),
        )
        for ((language, set, kdb) in keyboards) {
            val engine = LetterLayoutKdb.lettersByKeyCode(kdbFile(kdb))
            val letters = LetterLayoutEngineSync.engineLettersFor(builtIns, language, "qwerty", null, set)
            for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                assertEquals("$language:$set ${LetterLayoutXml.nameOf(keyCode)}", engine[keyCode], letters(keyCode))
            }
        }
    }

    @Test
    fun theAppsAssetsHoldEveryPhysicalKeyboardKdb() {
        val assets = LetterLayoutEngineSync.AssetKdbFiles(context.assets)
        assertEquals(assets.list("", Template.PKB).toString(), 40, assets.list("", Template.PKB).size)
        assertEquals(40, assets.list("athena", Template.PKB).size)
        assertTrue(assets.list("", Template.PKB).all { it.endsWith("_pkb.xml") })
        assertArrayEquals(File(kdbDir(), "athena/qwerty_pkb.xml").readBytes(), assets.read("athena", "qwerty_pkb.xml"))
        assertNull(assets.read("athena", "missing_pkb.xml"))
    }
}
