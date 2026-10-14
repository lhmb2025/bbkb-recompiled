package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.blackberry.nuanceshim.Xt9KdbVariant
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
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
 * [LetterLayoutEngineSync] for the on-screen keyboard and for a keyboard language's chosen layout
 * ([LetterLayoutChoice]), end to end through [Xt9KdbVariant] with only the owned library's two
 * registry calls replaced by a recorder keyed as the native registry keys it: by variant,
 * primaryId and secondaryId.
 *
 * On screen: while the keyboard is drawn from Colemak or Dvorak, every on-screen KDB is replaced by
 * one with the layout's rows template's geometry and letters under its own ids, and selected; a
 * keyboard's own layout selects the device variant again. On the keypad: a language's choice wins
 * over the Physical layouts selection for that language, and only there. The KDB files are the
 * shipped ones, read off disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutEngineSyncOnScreenTest {

    /** The native registry: "variant pid/sid" -> the bytes last registered. */
    private class Recorder : Xt9KdbVariant.NativeBridge {
        val files: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
        val registrations: MutableList<String> = Collections.synchronizedList(ArrayList())
        val selections: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun register(variant: String, xml: ByteArray): Int {
            val ids = LetterLayoutKdb.engineIds(String(xml, Charsets.UTF_8)) ?: return -1
            files["$variant ${ids.first}/${ids.second}"] = xml
            registrations += variant
            return 0
        }

        override fun setVariant(variant: String) {
            selections += variant
        }

        fun of(variant: String, secondaryId: Int) = files.keys.count { it.startsWith("$variant ") && it.endsWith("/$secondaryId") }
        fun text(variant: String, primaryId: Int, secondaryId: Int) = String(files.getValue("$variant $primaryId/$secondaryId"), Charsets.UTF_8)
    }

    private class DiskKdbFiles(private val root: File) : LetterLayoutEngineSync.KdbFiles {
        override fun list(variant: String, template: Template): List<String> =
            dir(variant).list()?.filter { it.endsWith(template.fileSuffix) } ?: emptyList()

        override fun read(variant: String, name: String): ByteArray? =
            File(dir(variant), name).takeIf { it.isFile }?.readBytes()

        private fun dir(variant: String) = if (variant.isEmpty()) root else File(root, variant)
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val native = Recorder()
    private val registry by lazy { LetterLayoutRegistry(context) }

    private var language: String? = "en"
    private var active: LetterLayout? = null
    private val chosen = HashMap<String, LetterLayout>()
    private var onScreen: LetterLayout? = null
    private var submits = 0

    private val engine = object : LetterLayoutEngineSync.Engine by LetterLayoutEngineSync.XtEngine {
        override fun submit(job: Runnable) {
            submits++
            LetterLayoutEngineSync.XtEngine.submit(job)
        }
    }

    private fun kdbDir(): File {
        val dir = sequenceOf("src/main/assets/kdb", "app/src/main/assets/kdb", "../app/src/main/assets/kdb")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("assets/kdb not found from ${File(".").canonicalPath}", dir)
        return dir!!
    }

    private fun vkb(name: String) = File(kdbDir(), "${name}_vkb.xml").readText(Charsets.UTF_8)

    private fun newSync(onScreenSource: (() -> LetterLayout?)? = { onScreen }) = LetterLayoutEngineSync(
        engine = engine,
        kdbFiles = DiskKdbFiles(kdbDir()),
        activeId = { active?.id ?: "" },
        activeLayout = { active },
        currentLanguage = { language },
        keypad = { "qwerty" },
        keypadType = { null },
        layoutSet = { null },
        engineLetters = { { keyCode -> LetterLayoutEngineSync.latinLetter(keyCode) } },
        chosenId = { lang -> chosen[lang]?.id ?: "" },
        chosenLayout = { lang -> chosen[lang] },
        onScreenLayout = onScreenSource,
    )

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

    private fun settle() = Xt9KdbVariant.awaitReady(5000)

    private fun deviceVariant(variant: String) {
        Xt9KdbVariant.apply(context, variant)
        settle()
    }

    private fun sync(pkb: Boolean) {
        LetterLayoutEngineSync.beforeEngineLayoutSync(pkb)
        settle()
    }

    private fun changed() {
        LetterLayoutRegistry.invalidate()
        settle()
    }

    private val colemak get() = registry.builtIn("colemak")!!
    private val dvorak get() = registry.builtIn("dvorak")!!

    /**
     * The shipped Colemak marked for the physical keys: what a chosen built-in that drives them
     * would be. The shipped one is for the on-screen keyboard only.
     */
    private val colemakForKeypad
        get() = colemak.let { LetterLayout(it.id, it.name, it.origin, setOf(LetterLayout.Target.PKB), it.binding, it.rowsTemplate, it.keys) }

    private val swap
        get() = LetterLayoutXml.parse(
            """<letter-layout version="1" id="swap" name="Swap"><bind/>
                <key code="KEYCODE_Q" keySpec="w"/><key code="KEYCODE_W" keySpec="q"/></letter-layout>"""
        )

    // ── the on-screen keyboard ───────────────────────────────────────────────

    @Test
    fun aKeyboardDrawnFromColemak_getsColemakKdbsForEveryLanguage() {
        deviceVariant("athena")
        onScreen = colemak
        sync(pkb = false)
        assertEquals("layout:colemak", Xt9KdbVariant.applied())
        assertEquals("the device variant is still athena", "athena", Xt9KdbVariant.deviceVariant())
        val count = kdbDir().list()!!.count { it.endsWith("_vkb.xml") }
        assertEquals("one per on-screen KDB the engine could load", count, native.registrations.count { it == "layout:colemak" })
        // English: the spanish rows' geometry, Colemak's letters, English's ids.
        val english = native.text("layout:colemak", 9, 1)
        assertEquals(LetterLayoutKdb.forOnScreenKeyboard(vkb("spanish"), vkb("qwerty"), colemak), english)
        assertEquals(listOf('o'.code), LetterLayoutKdb.codesByKeyCode(english, Template.VKB)[KeyEvent.KEYCODE_SEMICOLON]!!.toList())
        // German loads its own ids, with the same drawn letters.
        val german = native.text("layout:colemak", 7, 1)
        assertEquals(listOf('f'.code), LetterLayoutKdb.codesByKeyCode(german, Template.VKB)[KeyEvent.KEYCODE_E]!!.toList())
        assertEquals("no physical-keypad file is built for the on-screen keyboard", 0, native.of("layout:colemak", 6))
    }

    @Test
    fun aKeyboardsOwnLayoutOnScreen_selectsTheDeviceVariant() {
        deviceVariant("")
        onScreen = colemak
        sync(pkb = false)
        assertEquals("layout:colemak", Xt9KdbVariant.applied())
        onScreen = null
        sync(pkb = false)
        assertEquals("", Xt9KdbVariant.applied())
        onScreen = dvorak
        sync(pkb = false)
        assertEquals("layout:dvorak", Xt9KdbVariant.applied())
        val english = native.text("layout:dvorak", 9, 1)
        assertEquals(listOf('\''.code, '"'.code), LetterLayoutKdb.codesByKeyCode(english, Template.VKB)[KeyEvent.KEYCODE_Q]!!.toList())
    }

    @Test
    fun switchingBetweenTheKeyboards_decidesForEach_andBuildsOnce() {
        deviceVariant("athena")
        active = swap
        changed()
        assertEquals("the keypad follows the swap", "layout:swap", Xt9KdbVariant.applied())
        onScreen = colemak
        sync(pkb = false)
        assertEquals("the screen shows Colemak", "layout:colemak", Xt9KdbVariant.applied())
        sync(pkb = true)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        sync(pkb = false)
        assertEquals("layout:colemak", Xt9KdbVariant.applied())
        assertEquals(listOf("athena", "layout:swap", "layout:colemak", "layout:swap", "layout:colemak"), native.selections)
        val onScreenFiles = kdbDir().list()!!.count { it.endsWith("_vkb.xml") }
        assertEquals("built once", onScreenFiles, native.registrations.count { it == "layout:colemak" })
        assertEquals("built once", 40, native.registrations.count { it == "layout:swap" })
    }

    @Test
    fun anOnScreenSyncWithNothingNew_queuesNothing() {
        deviceVariant("athena")
        onScreen = colemak
        sync(pkb = false)
        val before = submits
        repeat(3) { sync(pkb = false) }
        assertEquals(before, submits)
        onScreen = dvorak
        sync(pkb = false)
        assertEquals(before + 1, submits)
    }

    @Test
    fun withoutAnOnScreenSource_onScreenSyncsAreNotFollowed() {
        LetterLayoutEngineSync.setInstanceForTest(newSync(onScreenSource = null))
        deviceVariant("athena")
        onScreen = colemak
        val before = submits
        sync(pkb = false)
        assertEquals(before, submits)
        assertEquals("athena", Xt9KdbVariant.applied())
    }

    @Test
    fun theKeyboardOnScreenIsReadByItsLayoutSet() {
        assertEquals("colemak", LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "colemak", "qwerty")!!.id)
        assertEquals("dvorak", LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "dvorak", "azerty")!!.id)
        assertEquals("AZERTY chosen for an English keyboard", "azerty", LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "azerty", "qwerty")!!.id)
        assertEquals("QWERTY chosen for a German keyboard", "qwerty", LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "qwerty", "qwertz")!!.id)
        assertNull("a keyboard's own set is not a choice, even with a document of that name", LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "qwerty", "qwerty"))
        assertNull(LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "azerty", "azerty"))
        assertNull(LetterLayoutEngineSync.onScreenLayoutOf(context, registry, "east_slavic", "east_slavic"))
        assertNull(LetterLayoutEngineSync.onScreenLayoutOf(context, registry, null, null))
    }

    // ── a language's chosen layout on the keypad ─────────────────────────────

    @Test
    fun aLanguagesChoiceDrivesTheKeypadKdbs_overThePhysicalLayoutsSelection() {
        deviceVariant("athena")
        active = swap
        chosen["en"] = colemakForKeypad
        changed()
        assertEquals("English follows its choice", "layout:colemak", Xt9KdbVariant.applied())
        assertEquals("every keypad KDB", 40, native.of("layout:colemak", 6))
        val english = native.text("layout:colemak", 9, 6)
        assertEquals(LetterLayoutKdb.rewrite(File(kdbDir(), "athena/qwerty_pkb.xml").readText(Charsets.UTF_8), colemakForKeypad), english)
        language = "de"
        sync(pkb = true)
        assertEquals("German has no choice: the Physical layouts selection", "layout:swap", Xt9KdbVariant.applied())
        language = "en"
        sync(pkb = true)
        assertEquals("layout:colemak", Xt9KdbVariant.applied())
    }

    @Test
    fun aChoiceForTheScreenOnly_leavesTheKeypadToThePhysicalLayoutsSelection() {
        deviceVariant("athena")
        active = swap
        // The shipped Colemak is for the on-screen keyboard only.
        chosen["en"] = colemak
        changed()
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals("no keypad KDB is built for it", 0, native.of("layout:colemak", 6))
        chosen["en"] = LetterLayoutXml.parse(
            """<letter-layout version="1" id="screen" name="Screen" targets="vkb"><bind/>
                <key code="KEYCODE_Q" keySpec="z"/></letter-layout>"""
        )
        changed()
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        chosen.clear()
        chosen["en"] = LetterLayoutXml.parse(
            """<letter-layout version="1" id="same" name="Same"><bind/>
                <key code="KEYCODE_Q" keySpec="q"/></letter-layout>"""
        )
        changed()
        assertEquals(
            "a choice that changes no letter still wins over the selection; the engine keeps its own letters",
            "athena",
            Xt9KdbVariant.applied(),
        )
        assertTrue(native.registrations.none { it == "layout:same" })
    }
}
