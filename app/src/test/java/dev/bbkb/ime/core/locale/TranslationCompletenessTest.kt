package dev.bbkb.ime.core.locale

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Completeness gate for the shipped translations.
 *
 * The app offers a keyboard subtype for every language listed in `res/xml/method.xml`. A user who
 * picks one of those keyboards is very likely running their phone in that same language, so every
 * one of those languages must carry a full set of UI strings -- a half-translated settings screen
 * reads worse than an English one.
 *
 * This test is data-driven from `method.xml`: adding a `<subtype>` for a new language adds the
 * requirement that `res/values-<lang>/strings.xml` be complete, and the test fails until it is.
 * Nothing here needs Robolectric or an Android runtime; the resources are parsed straight off disk.
 *
 * Deliberately *not* covered: `values-*` dirs for languages the app ships no subtype for (Amharic,
 * Kazakh, Greenlandic, Burmese, Nepali, Sinhala, Swahili, Urdu, Zulu). Those files are inherited
 * from an older BlackBerry build and are left as they are rather than half-maintained.
 */
class TranslationCompletenessTest {

    /** `app/src/main`, however the test happens to be launched (cwd is usually `app/`). */
    private fun mainDir(): File {
        val dir = sequenceOf("src/main", "app/src/main", "../src/main", "../app/src/main", "../../app/src/main")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertTrue("app/src/main not found from ${File(".").canonicalPath}", dir != null)
        return dir!!
    }

    private fun resDir() = File(mainDir(), "res")

    private val stringNameRegex = Regex("""<string\s+name="([^"]+)"([^>]*?)(?:/>|>)""")
    private val subtypeLocaleRegex = Regex("""imeSubtypeLocale="([^"]+)"""")

    /**
     * Android resource qualifier for a `method.xml` subtype language. `fil` resolves to the legacy
     * `tl` resource folder; `zz` is the no-language (plain alphabet) subtype, not a language.
     */
    private val subtypeLangToResLang = mapOf("fil" to "tl")
    private val notALanguage = setOf("zz")

    /**
     * Subtype languages the repo has no `values-*` folder for at all -- a keyboard layout with no
     * translated UI, so the user sees English. Empty since the 2026-09-20 pass filled in the last
     * seven (bs, ca, cy, ga, jv, sq, su). A new entry here means a language shipping untranslated,
     * so add the folder and translate it instead; only name a language here, with a reason, when
     * that is genuinely impossible.
     */
    private val languagesWithNoResourceFolder = emptySet<String>()

    /**
     * Locale folders that are knowingly still incomplete. Empty on purpose -- if a translation pass
     * has to leave a locale behind, name it here with the reason rather than weakening the test.
     */
    private val incompleteLocaleAllowlist = emptySet<String>()

    /** Every `<string name=...>` in the folder that is not marked `translatable="false"`. */
    private fun stringNames(dir: File, translatableOnly: Boolean): Set<String> {
        if (!dir.isDirectory) return emptySet()
        val names = mutableSetOf<String>()
        dir.listFiles { f -> f.extension == "xml" && !f.name.startsWith("donottranslate") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val text = file.readText()
                stringNameRegex.findAll(text).forEach { m ->
                    val untranslatable = m.groupValues[2].contains("""translatable="false"""")
                    if (!translatableOnly || !untranslatable) names += m.groupValues[1]
                }
            }
        return names
    }

    /** Resource language codes the app ships a keyboard subtype for. */
    private fun subtypeLanguages(): Set<String> {
        val methodXml = File(resDir(), "xml/method.xml")
        assertTrue("method.xml not found at ${methodXml.path}", methodXml.isFile)
        val text = methodXml.readText()
        val languages = subtypeLocaleRegex.findAll(text)
            .map { it.groupValues[1].substringBefore('_') }
            .filter { it !in notALanguage }
            .map { subtypeLangToResLang[it] ?: it }
            .toSet()
        assertTrue("no subtypes parsed out of method.xml", languages.size > 20)
        return languages
    }

    /** `values-de`, `values-pt-rPT`, `values-b+sr+Latn`, ... for one language code. */
    private fun foldersForLanguage(language: String): List<File> =
        resDir().listFiles { f -> f.isDirectory }
            ?.filter { dir ->
                val q = dir.name.removePrefix("values-")
                dir.name.startsWith("values-") &&
                    (q == language || q.startsWith("$language-r") || q.startsWith("b+$language+"))
            }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Target folders: one language -> every `values-*` folder that serves it. English is excluded
     * because `values/` already holds the English strings those folders fall back to.
     */
    private fun targetFolders(): List<File> =
        subtypeLanguages().filter { it != "en" }.flatMap { foldersForLanguage(it) }.sortedBy { it.name }

    @Test
    fun everySubtypeLanguageHasAResourceFolder() {
        val withoutFolder = subtypeLanguages()
            .filter { it != "en" && foldersForLanguage(it).isEmpty() }
            .filter { it !in languagesWithNoResourceFolder }
            .sorted()
        assertTrue(
            "method.xml ships a subtype for these languages but res/ has no values-<lang> folder " +
                "for them: $withoutFolder. Add the folder and translate it, or add the code to " +
                "languagesWithNoResourceFolder with a reason.",
            withoutFolder.isEmpty()
        )
    }

    @Test
    fun allowlistEntriesAreStillRelevant() {
        val known = resDir().listFiles { f -> f.isDirectory }?.map { it.name }?.toSet().orEmpty()
        val stale = incompleteLocaleAllowlist.filterNot { it in known }
        assertTrue("incompleteLocaleAllowlist names folders that no longer exist: $stale", stale.isEmpty())
        val languages = subtypeLanguages()
        val staleLangs = languagesWithNoResourceFolder.filterNot { it in languages }
        assertTrue(
            "languagesWithNoResourceFolder names languages with no subtype any more: $staleLangs",
            staleLangs.isEmpty()
        )
        val nowPresent = languagesWithNoResourceFolder.filter { foldersForLanguage(it).isNotEmpty() }
        assertTrue(
            "these languages now have a values-<lang> folder and must come off " +
                "languagesWithNoResourceFolder: $nowPresent",
            nowPresent.isEmpty()
        )
    }

    @Test
    fun everyTargetLocaleTranslatesEveryBaseString() {
        val base = stringNames(File(resDir(), "values"), translatableOnly = true)
        assertTrue("suspiciously few base strings: ${base.size}", base.size > 500)

        val report = StringBuilder()
        var failed = 0
        for (folder in targetFolders()) {
            if (folder.name in incompleteLocaleAllowlist) continue
            val translated = stringNames(folder, translatableOnly = false)
            val missing = (base - translated).sorted()
            if (missing.isNotEmpty()) {
                failed++
                report.append("\n  ${folder.name}: ${missing.size} untranslated, e.g. ")
                    .append(missing.take(6).joinToString(", "))
            }
        }
        assertTrue(
            "$failed locale folder(s) are missing translations for strings in values/strings.xml." +
                " Translate them, or (only as a last resort) list the folder in " +
                "incompleteLocaleAllowlist with a reason:$report",
            failed == 0
        )
    }

    @Test
    fun everyTargetLocaleFileIsWellFormedXml() {
        val factory = DocumentBuilderFactory.newInstance()
        val broken = mutableListOf<String>()
        for (folder in targetFolders()) {
            folder.listFiles { f -> f.extension == "xml" }?.forEach { file ->
                try {
                    factory.newDocumentBuilder().parse(file)
                } catch (e: Exception) {
                    broken += "${folder.name}/${file.name}: ${e.message}"
                }
            }
        }
        assertTrue("malformed resource XML: $broken", broken.isEmpty())
    }
}
