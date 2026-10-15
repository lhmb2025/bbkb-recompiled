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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
 * On screen: whatever keyboard is drawn, its letters go on the shipped on-screen file of its row
 * shape, under every on-screen id, registered as `layout:screen:<fingerprint>` and selected; the
 * Chinese stroke, zhuyin and Cangjie keyboards select the device variant and their shipped files.
 * Built files are kept in a [KdbStore]. On the keypad: a language's choice wins over the Physical
 * layouts selection for that language, and only there. The KDB files are the shipped ones, read off
 * disk.
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
        override fun list(template: Template): List<String> =
            root.list()?.filter { it.endsWith(template.fileSuffix) } ?: emptyList()

        override fun read(name: String): ByteArray? =
            File(root, name).takeIf { it.isFile }?.readBytes()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val native = Recorder()
    private val registry by lazy { LetterLayoutRegistry(context) }

    private var language: String? = "en"
    private var active: LetterLayout? = null
    private val chosen = HashMap<String, LetterLayout>()
    private var onScreen: DrawnKeyboard? = null

    @get:Rule
    val folder = TemporaryFolder()

    private val store by lazy { KdbStore(folder.newFolder("kdb")) }
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

    private fun newSync(onScreenSource: (() -> DrawnKeyboard?)? = { onScreen }, kdbStore: KdbStore? = store) = LetterLayoutEngineSync(
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
        onScreenKeyboard = onScreenSource,
        shippedLayout = KeypadKdbs.shippedLetters(registry),
        store = kdbStore,
    )

    /**
     * A drawn keyboard of [rows] (one letter per position; `.` for a period), with its positions at
     * the key centres of the shipped on-screen file [like].
     */
    private fun drawn(set: String, like: String, vararg rows: String, spaceRow: Int = 2): DrawnKeyboard {
        val centres = LetterLayoutKdb.screenRows(vkb(like))
        return DrawnKeyboard(
            set,
            rows.mapIndexed { r, row ->
                row.codePoints().toArray().mapIndexed { i, c -> DrawnKeyboard.Position(intArrayOf(c), centres[r][i]) }
            },
            spaceRow,
        )
    }

    private val colemakDrawn get() = drawn("colemak", "spanish", "qwfpgjluy;", "arstdhneio", "zxcvbkm")
    private val dvorakDrawn get() = drawn("dvorak", "spanish", "',.pyfgcrl", "aoeuidhtns", ";qjkxbm")
    private val azertyDrawn get() = drawn("azerty", "qwerty", "azertyuiop", "qsdfghjkl", "wxcvbnm")

    private fun screenVariant(drawn: DrawnKeyboard) = LetterLayoutEngineSync.SCREEN_VARIANT_PREFIX + drawn.fingerprint

    private fun letters(xml: String): String {
        val byCode = LetterLayoutKdb.lettersByKeyCode(xml, Template.VKB)
        return listOf("QWERTYUIOP", "ASDFGHJKL;", "ZXCVBNM").joinToString("|") { row ->
            row.mapNotNull { c ->
                val code = if (c == ';') KeyEvent.KEYCODE_SEMICOLON else KeyEvent.KEYCODE_A + (c - 'A')
                byCode[code]?.let { String(Character.toChars(it)) }
            }.joinToString("")
        }
    }

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
    fun theDrawnKeyboard_isBuiltOnTheFileOfItsShape_forEveryOnScreenId() {
        deviceVariant("athena")
        onScreen = colemakDrawn
        sync(pkb = false)
        val name = screenVariant(colemakDrawn)
        assertEquals(name, Xt9KdbVariant.applied())
        assertEquals("the device variant is still athena", "athena", Xt9KdbVariant.deviceVariant())
        assertEquals("one per on-screen id", OnScreenKdbs.IDS.size, native.registrations.count { it == name })
        // English: the spanish rows' geometry (ten keys in the middle), Colemak's letters, English's ids.
        val english = native.text(name, 9, 1)
        assertEquals("qwfpgjluy;|arstdhneio|zxcvbkm", letters(english))
        assertEquals(LetterLayoutKdb.withEngineIds(LetterLayoutKdb.forDrawnKeyboard(vkb("spanish"), colemakDrawn.rows.map { r -> r.map { it.codes } })!!, 9, 1), english)
        // German and Japanese romaji load their own ids, with the same drawn letters.
        assertEquals("qwfpgjluy;|arstdhneio|zxcvbkm", letters(native.text(name, 7, 1)))
        assertEquals("qwfpgjluy;|arstdhneio|zxcvbkm", letters(native.text(name, 17, 5)))
        assertEquals("no physical-keypad file is built for the on-screen keyboard", 0, native.of(name, 6))
    }

    @Test
    fun aBelgianAzertyKeyboard_isDecodedInTheLettersItDraws() {
        deviceVariant("athena")
        onScreen = azertyDrawn
        sync(pkb = false)
        // Dutch asks for English's file (9) and German for QWERTZ's (7): both now hold AZERTY.
        assertEquals("azertyuiop|qsdfghjkl|wxcvbnm", letters(native.text(screenVariant(azertyDrawn), 9, 1)))
        assertEquals("azertyuiop|qsdfghjkl|wxcvbnm", letters(native.text(screenVariant(azertyDrawn), 7, 1)))
    }

    @Test
    fun theChineseStrokeZhuyinAndCangjieKeyboards_keepTheirShippedFiles() {
        deviceVariant("")
        for (set in OnScreenKdbs.KEPT_LAYOUT_SETS) {
            onScreen = drawn(set, "qwerty", "qwertyuiop", "asdfghjkl", "zxcvbnm")
            sync(pkb = false)
            assertEquals(set, KeypadKdbs.GENERIC, Xt9KdbVariant.applied())
        }
        assertTrue(native.registrations.none { it.startsWith(LetterLayoutEngineSync.SCREEN_VARIANT_PREFIX) })
    }

    @Test
    fun aShapeNoFileHas_keepsTheDeviceVariant() {
        deviceVariant("")
        onScreen = drawn("odd", "qwerty", "qwertyuiop", "asdfghjkl", "zxcvbnm").let {
            DrawnKeyboard("odd", it.rows.take(2), 2)
        }
        sync(pkb = false)
        assertEquals(KeypadKdbs.GENERIC, Xt9KdbVariant.applied())
    }

    @Test
    fun switchingKeyboards_selectsEach_andBuildsOnce() {
        deviceVariant("")
        onScreen = colemakDrawn
        sync(pkb = false)
        onScreen = dvorakDrawn
        sync(pkb = false)
        assertEquals(screenVariant(dvorakDrawn), Xt9KdbVariant.applied())
        assertEquals(listOf('\''.code, '"'.code).take(1), LetterLayoutKdb.codesByKeyCode(native.text(screenVariant(dvorakDrawn), 9, 1), Template.VKB)[KeyEvent.KEYCODE_Q]!!.toList().take(1))
        onScreen = colemakDrawn
        sync(pkb = false)
        assertEquals(screenVariant(colemakDrawn), Xt9KdbVariant.applied())
        assertEquals("built once", OnScreenKdbs.IDS.size, native.registrations.count { it == screenVariant(colemakDrawn) })
    }

    @Test
    fun switchingBetweenTheKeyboards_decidesForEach() {
        deviceVariant("athena")
        active = swap
        changed()
        assertEquals("the keypad follows the swap", "layout:swap", Xt9KdbVariant.applied())
        onScreen = colemakDrawn
        sync(pkb = false)
        assertEquals("the screen shows Colemak", screenVariant(colemakDrawn), Xt9KdbVariant.applied())
        sync(pkb = true)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        sync(pkb = false)
        assertEquals(screenVariant(colemakDrawn), Xt9KdbVariant.applied())
        assertEquals("built once", 39, native.registrations.count { it == "layout:swap" })
    }

    @Test
    fun anOnScreenSyncWithNothingNew_queuesNothing() {
        deviceVariant("athena")
        onScreen = colemakDrawn
        sync(pkb = false)
        val before = submits
        repeat(3) { sync(pkb = false) }
        assertEquals(before, submits)
        onScreen = dvorakDrawn
        sync(pkb = false)
        assertEquals(before + 1, submits)
    }

    @Test
    fun withoutAnOnScreenSource_onScreenSyncsAreNotFollowed() {
        LetterLayoutEngineSync.setInstanceForTest(newSync(onScreenSource = null))
        deviceVariant("athena")
        val before = submits
        sync(pkb = false)
        assertEquals(before, submits)
        assertEquals("athena", Xt9KdbVariant.applied())
    }

    // ── kept on storage ──────────────────────────────────────────────────────

    @Test
    fun builtKdbs_areKeptOnStorage_andReadBackByTheNextProcess() {
        deviceVariant("athena")
        onScreen = colemakDrawn
        sync(pkb = false)
        active = swap
        changed()
        sync(pkb = true)
        assertTrue(store.has(KdbStore.key("vkb", colemakDrawn.fingerprint)))
        val first = native.text(screenVariant(colemakDrawn), 9, 1)
        val keypad = native.text("layout:swap", 9, 6)
        // A new process: a fresh sync and engine, the same store.
        val nextNative = Recorder()
        Xt9KdbVariant.setNativeBridgeForTest(nextNative)
        val next = newSync()
        LetterLayoutEngineSync.setInstanceForTest(next)
        assertEquals(store.get(KdbStore.key("vkb", colemakDrawn.fingerprint))!!.size, OnScreenKdbs.IDS.size)
        deviceVariant("athena")
        sync(pkb = false)
        assertEquals(first, nextNative.text(screenVariant(colemakDrawn), 9, 1))
        sync(pkb = true)
        assertEquals(keypad, nextNative.text("layout:swap", 9, 6))
    }

    @Test
    fun buildingAhead_fillsTheStore_withoutTouchingTheEngine() {
        deviceVariant("athena")
        val sync = LetterLayoutEngineSync.current()!!
        val before = native.registrations.size
        sync.prebuildOnScreen(dvorakDrawn)
        sync.prebuildKeypad(swap)
        assertEquals(before, native.registrations.size)
        assertTrue(store.has(KdbStore.key("vkb", dvorakDrawn.fingerprint)))
        assertTrue(store.has(KdbStore.key("pkb", "athena", LetterLayoutXml.serialize(swap))))
        sync.prebuildOnScreen(drawn("stroke", "qwerty", "qwertyuiop", "asdfghjkl", "zxcvbnm"))
        assertTrue("nothing for a kept keyboard", folder.root.walk().count { it.isFile } == 2)
    }

    // ── a language's chosen layout on the keypad ─────────────────────────────

    @Test
    fun aLanguagesChoiceDrivesTheKeypadKdbs_overThePhysicalLayoutsSelection() {
        deviceVariant("athena")
        active = swap
        chosen["en"] = colemakForKeypad
        changed()
        assertEquals("English follows its choice", "layout:colemak", Xt9KdbVariant.applied())
        assertEquals("every keypad KDB", 39, native.of("layout:colemak", 6))
        val english = native.text("layout:colemak", 9, 6)
        val athena = KeypadKdbs.forDevice(File(kdbDir(), KeypadKdbs.templateFile("athena")).readText(Charsets.UTF_8), KeypadKdbs.shippedLetters(registry))
        assertEquals(LetterLayoutKdb.rewrite(athena.getValue(9), colemakForKeypad), english)
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
