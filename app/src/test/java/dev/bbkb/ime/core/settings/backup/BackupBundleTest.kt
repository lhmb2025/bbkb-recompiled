package dev.bbkb.ime.core.settings.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup bundle: what a round trip preserves, which files it refuses, and that the single
 * documents earlier builds wrote still read.
 *
 * Robolectric only for a real `SharedPreferences` behind the settings part and the Android
 * `org.json`; the bundle itself is plain zip and plain JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupBundleTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("bundle_source", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean("auto_cap", true)
            .putString("control_mode", "2")
            .putString("pref_vkb_symbol_page_layout", "a͸b")
            .putString("pref_currency_key", "€")
            .commit()
    }

    private fun layouts() = LayoutsBundle.Layouts(
        vkbSymbolPage = List(LayoutsBundle.VKB_SLOTS) { "v$it" },
        pkbSymbolPage = List(LayoutsBundle.PKB_SLOTS) { "p$it" },
        palette = listOf("★", "→"),
        slideboard = List(LayoutsBundle.SLIDEBOARD_SLOTS) { "s$it" },
        quickPhrases = listOf("On my way!", null, null, null, null),
        currency = "€",
    )

    private fun words() = WordsBundle.Words(
        dictionary = listOf(
            DictionaryEntry(word = "BBKB", locale = "", fixedCase = true),
            DictionaryEntry(word = "on my way", shortcut = "omw", locale = "en_US"),
        ),
        learnedWords = listOf("keypad", "athena"),
    )

    private fun fullContents() = BackupBundle.Contents(
        settingsJson = SettingsBackup.serialize(prefs, "5.0.0-test", 1, excludeKeys = LayoutsBundle.PREF_KEYS),
        macrosJson = MacrosBundle.serialize(listOf(CustomMacro("p", "Phone", "+1 555 0100", 1L))),
        deviceConfigs = listOf(BackupBundle.DeviceConfigFile("my-phone.xml", "<device-input-config/>".toByteArray())),
        layoutsJson = LayoutsBundle.serialize(layouts(), "5.0.0-test", 1),
        wordsJson = WordsBundle.serialize(words(), "5.0.0-test", 1),
    )

    private fun writeBytes(contents: BackupBundle.Contents): ByteArray {
        val out = ByteArrayOutputStream()
        BackupBundle.write(out, contents, "5.0.0-test", 1, BackupBundle.DeviceInfo("BlackBerry", "KEY2"), 1_700_000_000_000L)
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): Result<BackupBundle.Bundle> = BackupBundle.read(ByteArrayInputStream(bytes))

    // ── round trip ───────────────────────────────────────────────────────────

    @Test
    fun aFullBundleRoundTrips() {
        val bundle = read(writeBytes(fullContents())).getOrThrow()

        assertEquals(setOf(BackupBundle.Part.SETTINGS, BackupBundle.Part.LAYOUTS, BackupBundle.Part.WORDS), bundle.parts())
        assertTrue(bundle.origin.isBundle)
        assertEquals("5.0.0-test", bundle.origin.app)
        assertEquals("2023-11-14T22:13:20Z", bundle.origin.created)
        assertEquals("KEY2", bundle.origin.device?.model)

        val settings = bundle.settings!!
        assertEquals(true, settings.values["auto_cap"])
        assertEquals("2", settings.values["control_mode"])
        // The layout keys travel in the layouts part, never in settings.json.
        assertNull(settings.values["pref_vkb_symbol_page_layout"])
        assertNull(settings.values["pref_currency_key"])

        assertEquals(listOf("p"), bundle.macros!!.map { it.tag })
        assertEquals(listOf("my-phone.xml"), bundle.deviceConfigs.map { it.name })
        assertEquals("<device-input-config/>", String(bundle.deviceConfigs[0].xml))

        val layouts = bundle.layouts!!
        assertEquals(listOf("★", "→"), layouts.palette)
        assertEquals("On my way!", layouts.quickPhrases!![0])
        assertEquals("€", layouts.currency)

        val words = bundle.words!!
        assertEquals(2, words.dictionary!!.size)
        assertEquals("omw", words.dictionary!!.first { it.word == "on my way" }.shortcut)
        assertEquals(listOf("athena", "keypad"), words.learnedWords)
    }

    @Test
    fun partsLeftOutAreAbsentNotEmpty() {
        val bundle = read(writeBytes(BackupBundle.Contents(wordsJson = WordsBundle.serialize(words(), "t", 1)))).getOrThrow()
        assertEquals(setOf(BackupBundle.Part.WORDS), bundle.parts())
        assertFalse(bundle.hasSettings)
        assertFalse(bundle.hasLayouts)
        assertNull(bundle.settings)
        assertNull(bundle.macros)
        assertTrue(bundle.deviceConfigs.isEmpty())
    }

    @Test
    fun anEmptyBundleIsRefusedOnRead() {
        val failure = read(writeBytes(BackupBundle.Contents())).exceptionOrNull()
        assertTrue(failure is BackupBundle.NotABackupException)
        assertTrue(failure!!.message!!.contains("empty"))
    }

    @Test
    fun theManifestListsEveryPartWithItsChecksum() {
        val entries = entriesOf(writeBytes(fullContents()))
        val manifest = JSONObject(String(entries.getValue(BackupBundle.MANIFEST)))
        assertEquals(BackupBundle.FORMAT, manifest.getString("format"))
        val listed = manifest.getJSONArray("parts")
        val paths = (0 until listed.length()).map { listed.getJSONObject(it).getString("path") }
        assertEquals(
            listOf("settings.json", "macros.json", "device-configs/my-phone.xml", "layouts.json", "words.json"),
            paths,
        )
        assertEquals(64, listed.getJSONObject(0).getString("sha256").length)
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun aTamperedPartIsRefusedByItsChecksum() {
        val entries = entriesOf(writeBytes(fullContents()))
        val tampered = entries.getValue(BackupBundle.WORDS).copyOf()
        val i = String(tampered).indexOf("keypad")
        tampered[i] = 'K'.code.toByte()   // same length, different bytes
        entries[BackupBundle.WORDS] = tampered

        val failure = read(zipOf(entries)).exceptionOrNull()
        assertTrue(failure is BackupBundle.NotABackupException)
        assertTrue(failure!!.message!!.contains("checksum"))
    }

    @Test
    fun anEntryTheManifestDoesNotListIsRefused() {
        val entries = entriesOf(writeBytes(fullContents()))
        entries["device-configs/extra.xml"] = "<device-input-config/>".toByteArray()
        val failure = read(zipOf(entries)).exceptionOrNull()
        assertTrue(failure!!.message!!.contains("not listed"))
    }

    @Test
    fun anEntryWithAPathIsRefusedBeforeAnythingIsParsed() {
        val entries = entriesOf(writeBytes(fullContents()))
        entries["../evil.json"] = "{}".toByteArray()
        val failure = read(zipOf(entries)).exceptionOrNull()
        assertTrue(failure!!.message!!.contains("unexpected entry"))

        val nested = entriesOf(writeBytes(fullContents()))
        nested["device-configs/../../x.xml"] = "<a/>".toByteArray()
        assertTrue(read(zipOf(nested)).exceptionOrNull()!!.message!!.contains("unexpected entry"))
    }

    @Test
    fun aMissingManifestIsNotABackup() {
        val entries = entriesOf(writeBytes(fullContents()))
        entries.remove(BackupBundle.MANIFEST)
        assertTrue(read(zipOf(entries)).exceptionOrNull()!!.message!!.contains("manifest.json"))
    }

    @Test
    fun aPartThatDoesNotParseRefusesTheWholeFile() {
        val entries = entriesOf(writeBytes(fullContents()))
        // Replace the layouts document by a well-formed one of the wrong shape, keeping the
        // manifest honest about it.
        val bad = """{"format":"bbkb-layouts","version":1,"palette":["ok"],"vkbSymbolPage":["too","few"]}""".toByteArray()
        val rebuilt = BackupBundle.Contents(
            settingsJson = String(entries.getValue(BackupBundle.SETTINGS)),
            layoutsJson = String(bad),
            wordsJson = String(entries.getValue(BackupBundle.WORDS)),
        )
        val failure = read(writeBytes(rebuilt)).exceptionOrNull()
        assertTrue(failure is BackupBundle.NotABackupException)
        assertTrue(failure!!.message!!.startsWith("layouts.json:"))
    }

    @Test
    fun aFutureVersionIsRefused() {
        val entries = entriesOf(writeBytes(fullContents()))
        val manifest = JSONObject(String(entries.getValue(BackupBundle.MANIFEST)))
        manifest.put("version", BackupBundle.VERSION + 1)
        entries[BackupBundle.MANIFEST] = manifest.toString().toByteArray()
        assertTrue(read(zipOf(entries)).exceptionOrNull()!!.message!!.contains("version"))
    }

    @Test
    fun somethingThatIsNeitherZipNorJsonIsNotABackup() {
        val failure = read("hello there".toByteArray()).exceptionOrNull()
        assertTrue(failure is BackupBundle.NotABackupException)
        val other = read("""{"format":"somebody-elses","version":1}""".toByteArray()).exceptionOrNull()
        assertTrue(other!!.message!!.contains("not a BBKB backup"))
    }

    // ── the single documents earlier builds wrote ────────────────────────────

    @Test
    fun anOldSettingsDocumentReadsAsASettingsOnlyBundle() {
        val json = SettingsBackup.serialize(prefs, "5.0.0-beta.24", 1437)
        val bundle = read(json.toByteArray()).getOrThrow()
        assertFalse(bundle.origin.isBundle)
        assertEquals("5.0.0-beta.24", bundle.origin.app)
        assertEquals(setOf(BackupBundle.Part.SETTINGS), bundle.parts())
        assertEquals(true, bundle.settings!!.values["auto_cap"])
    }

    @Test
    fun anOldLayoutsDocumentReadsAsALayoutsOnlyBundle() {
        val json = LayoutsBundle.serialize(layouts(), "5.0.0-beta.24", 1437)
        val bundle = read(json.toByteArray()).getOrThrow()
        assertEquals(setOf(BackupBundle.Part.LAYOUTS), bundle.parts())
        assertEquals("€", bundle.layouts!!.currency)
    }

    @Test
    fun aWordsDocumentReadsAsAWordsOnlyBundle() {
        val json = WordsBundle.serialize(words(), "t", 1)
        val bundle = read(json.toByteArray()).getOrThrow()
        assertEquals(setOf(BackupBundle.Part.WORDS), bundle.parts())
        assertEquals(listOf("athena", "keypad"), bundle.words!!.learnedWords)
    }

    // ── selection ────────────────────────────────────────────────────────────

    @Test
    fun aSelectionIsLimitedToWhatTheBundleHolds() {
        val bundle = read(writeBytes(BackupBundle.Contents(wordsJson = WordsBundle.serialize(words(), "t", 1)))).getOrThrow()
        val limited = BackupBundle.Selection.ALL.limitedTo(bundle)
        assertEquals(BackupBundle.Selection(settings = false, layouts = false, words = true), limited)
        assertTrue(BackupBundle.Selection.NONE.isEmpty)
        assertEquals(BackupBundle.Selection(settings = false, layouts = true, words = true), BackupBundle.Selection.ALL.with(BackupBundle.Part.SETTINGS, false))
    }

    @Test
    fun theDefaultFileNameCarriesTheDate() {
        assertEquals("bbkb-backup-2023-11-14.zip", BackupBundle.defaultFileName(1_700_000_000_000L))
    }

    // ── zip helpers ──────────────────────────────────────────────────────────

    private fun entriesOf(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
