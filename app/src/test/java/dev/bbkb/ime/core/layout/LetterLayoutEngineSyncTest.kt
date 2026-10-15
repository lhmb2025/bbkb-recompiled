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
import org.junit.Assert.assertFalse
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
        override fun list(template: Template): List<String> =
            root.list()?.filter { it.endsWith(template.fileSuffix) } ?: emptyList()

        override fun read(name: String): ByteArray? =
            File(root, name).takeIf { it.isFile }?.readBytes()
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
        builtIn: (String?) -> LetterLayout? = { null },
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
        builtInLayout = builtIn,
        shippedLayout = KeypadKdbs.shippedLetters(builtIns),
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

    /** A keypad KDB the app used to ship, kept in `xt9kdb-scaffold/reference/kdb_pkb/`. */
    private fun shippedFile(name: String): String {
        val dir = sequenceOf("../xt9kdb-scaffold/reference/kdb_pkb", "xt9kdb-scaffold/reference/kdb_pkb")
            .map { File(it).canonicalFile }
            .first { it.isDirectory }
        return File(dir, "${name}_pkb.xml").readText(Charsets.UTF_8)
    }

    /** What [device]'s keypad KDB for [primaryId] is: built from its geometry file. */
    private fun deviceKdb(device: String, primaryId: Int): String =
        KeypadKdbs.forDevice(File(kdbDir(), KeypadKdbs.templateFile(device)).readText(Charsets.UTF_8), KeypadKdbs.shippedLetters(builtIns))[primaryId]!!

    // ── the built-in layouts ─────────────────────────────────────────────────

    private val builtIns by lazy { LetterLayoutRegistry(context) }

    /** The sync with every keyboard's built-in document as the app gives it (Spanish and English here). */
    private fun withBuiltIns() {
        LetterLayoutEngineSync.setInstanceForTest(newSync(builtIn = { language ->
            builtIns.builtIn(language, keypad, null, layoutSet)
        }))
    }

    @Test
    fun withNoLayout_theKeyboardsBuiltInIsBuiltAndSelected() {
        withBuiltIns()
        deviceVariant("athena")
        keyboard("es")
        assertEquals("layout:builtin:es", Xt9KdbVariant.applied())
        // The engine reads Spanish's file (ID 10): the built-in's letters, ñ still on the n key.
        val spanish = registered("layout:builtin:es", 10)
        assertEquals(listOf('n'.code, 'ñ'.code), LetterLayoutKdb.codesByKeyCode(spanish)[KeyEvent.KEYCODE_N]!!.toList())
        assertTrue("athena's even rows", spanish.contains("keyHeight=\"108dp\""))
    }

    @Test
    fun aKeyboardWithNoBuiltIn_keepsTheShippedFiles() {
        withBuiltIns()
        language = "hi"
        deviceVariant("athena")
        keyboard("hi")
        assertEquals("athena", Xt9KdbVariant.applied())
        assertEquals(0, native.layoutRegistrations())
    }

    @Test
    fun anImportedLayout_winsOverTheBuiltIn() {
        withBuiltIns()
        deviceVariant("athena")
        keyboard("en")
        assertEquals("layout:builtin:en", Xt9KdbVariant.applied())
        activate(swap)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        activate(null)
        assertEquals("layout:builtin:en", Xt9KdbVariant.applied())
    }

    @Test
    fun switchingKeyboards_switchesBuiltIns_eachBuiltOnce() {
        withBuiltIns()
        deviceVariant("athena")
        keyboard("en")
        keyboard("es")
        keyboard("en")
        keyboard("es")
        assertEquals("layout:builtin:es", Xt9KdbVariant.applied())
        assertEquals(39, native.count("layout:builtin:en"))
        assertEquals(39, native.count("layout:builtin:es"))
    }

    @Test
    fun noUserLayoutIdCanNameABuiltInsVariant() {
        assertFalse(LetterLayout.isValidId("builtin:en"))
        assertEquals("layout:builtin:en", LetterLayoutKdb.builtInVariantName(builtIns.builtIn("en")!!))
    }

    // ── following a layout ───────────────────────────────────────────────────

    // ── the device's keypad ──────────────────────────────────────────────────

    @Test
    fun theDevicesKeypadKdbsAreBuiltFromItsGeometry_andSelected() {
        deviceVariant("athena")
        assertEquals("athena", Xt9KdbVariant.applied())
        assertEquals(KeypadKdbs.SHIPPED.map { it.primaryId }.toSet(), native.files["athena"]!!.keys)
        assertEquals(deviceKdb("athena", 25), registered("athena", 25))
        assertEquals('й'.code, LetterLayoutKdb.codesByKeyCode(registered("athena", 25))[KeyEvent.KEYCODE_Q]!![0])
    }

    @Test
    fun noVariant_isTheGenericKeypad_andAMissingGeometry_fallsBackToIt() {
        deviceVariant("")
        assertEquals(KeypadKdbs.GENERIC, Xt9KdbVariant.applied())
        assertEquals(18, native.files[KeypadKdbs.GENERIC]!!.size)
        deviceVariant("no_such_phone")
        assertEquals(KeypadKdbs.GENERIC, Xt9KdbVariant.deviceVariant())
    }

    @Test
    fun theUnevenTestGeometry_isOneDebugPropertyAway() {
        deviceVariant("athena_test")
        assertEquals("athena_test", Xt9KdbVariant.applied())
        assertTrue(registered("athena_test", 9).contains("keyHeight=\"90dp\""))
    }

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
        assertEquals("every PKB KDB, so none falls back to the root copy", 39, native.files["layout:swap"]!!.size)
        // English: athena's keypad, with the layout's letters.
        val english = registered("layout:swap", 9)
        assertEquals(LetterLayoutKdb.rewrite(deviceKdb("athena", 9), swap), english)
        assertEquals(listOf('w'.code), LetterLayoutKdb.codesByKeyCode(english)[KeyEvent.KEYCODE_Q]!!.toList())
        // Arabic too: the selection, not the files, follows the binding.
        assertEquals(listOf('w'.code), LetterLayoutKdb.codesByKeyCode(registered("layout:swap", 1))[KeyEvent.KEYCODE_Q]!!.toList())
        // A key the layout does not name keeps that id's own letters: Arabic's, on the A key.
        assertArrayEquals(
            LetterLayoutKdb.codesByKeyCode(deviceKdb("athena", 1))[KeyEvent.KEYCODE_A],
            LetterLayoutKdb.codesByKeyCode(registered("layout:swap", 1))[KeyEvent.KEYCODE_A],
        )
        assertTrue(LetterLayoutKdb.codesByKeyCode(registered("layout:swap", 1))[KeyEvent.KEYCODE_A]!![0] > 0x600)
        // An id the device does not build comes from its shipped file: Hindi's.
        assertEquals(
            LetterLayoutKdb.rewrite(File(kdbDir(), "hindi_pkb.xml").readText(Charsets.UTF_8), swap),
            registered("layout:swap", 57),
        )
    }

    @Test
    fun onTheGenericKeypad_itsKdbsAreRewritten() {
        deviceVariant("")
        activate(swap)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals(LetterLayoutKdb.rewrite(deviceKdb(KeypadKdbs.GENERIC, 9), swap), registered("layout:swap", 9))
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
        assertEquals("built once", 39, native.count("layout:de-qwertz"))
    }

    @Test
    fun editingTheActiveLayout_rebuildsItsKdbs() {
        deviceVariant("athena")
        activate(swap)
        val edited = layout("""<key code="KEYCODE_Q" keySpec="e"/><key code="KEYCODE_E" keySpec="q"/>""")
        activate(edited)
        assertEquals(78, native.count("layout:swap"))
        assertEquals(listOf('e'.code), LetterLayoutKdb.codesByKeyCode(registered("layout:swap", 9))[KeyEvent.KEYCODE_Q]!!.toList())
        assertEquals("layout:swap", Xt9KdbVariant.applied())
    }

    @Test
    fun aNewDeviceVariant_rebuildsTheLayoutFromItsFiles() {
        deviceVariant("")
        activate(swap)
        assertTrue(registered("layout:swap", 9).contains("keyHeight=\"108dp\""))
        deviceVariant("athena_test")
        assertEquals(LetterLayoutKdb.rewrite(deviceKdb("athena_test", 9), swap), registered("layout:swap", 9))
        assertTrue(registered("layout:swap", 9).contains("keyHeight=\"90dp\""))
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals("athena_test", Xt9KdbVariant.deviceVariant())
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
            val engine = LetterLayoutKdb.lettersByKeyCode(shippedFile(kdb))
            val letters = LetterLayoutEngineSync.engineLettersFor(builtIns, language, "qwerty", null, set)
            for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                assertEquals("$language:$set ${LetterLayoutXml.nameOf(keyCode)}", engine[keyCode], letters(keyCode))
            }
        }
    }

    @Test
    fun theAppsAssetsHoldTheKeypadGeometriesAndTheShippedKdbs() {
        val assets = LetterLayoutEngineSync.AssetKdbFiles(context.assets)
        val pkb = assets.list(Template.PKB)
        assertEquals(pkb.toString(), 22, pkb.size)
        assertEquals(listOf("keypad_pkb.xml"), pkb.filter { KeypadKdbs.isTemplate(it) })
        assertArrayEquals(File(kdbDir(), "keypad_pkb_athena.xml").readBytes(), assets.read(KeypadKdbs.templateFile("athena")))
        assertNull(assets.read("missing_pkb.xml"))
    }
}
