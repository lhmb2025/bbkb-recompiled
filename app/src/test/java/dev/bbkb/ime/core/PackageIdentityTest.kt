package dev.bbkb.ime.core

import dev.bbkb.ime.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app installs under its own identity, `dev.bbkb.ime`, so it can sit next to the original
 * BlackBerry Keyboard (`com.blackberry.keyboard`) on the same device.
 *
 * Three names are in play and all of them now agree:
 *
 *  - the **applicationId** — what the device installs, what `Context.getPackageName()` returns,
 *    what an IME id and a `run-as` name are built from. That is `dev.bbkb.ime`, `.debug` in debug
 *    builds.
 *  - the Gradle **namespace**, the package the generated `R`, `BuildConfig` and `databinding`
 *    classes live in. Also `dev.bbkb.ime`, so `dev.bbkb.ime.R` is the only `R` there is.
 *  - the **java root** every class this app owns lives under. Also `dev.bbkb.ime`. The sole
 *    exception is `com.blackberry.nuanceshim` — see [theOnlyComBlackberryPackageLeftIsTheJniShim].
 *
 * The failure this guards against is a *string literal* holding the old id: such a literal keeps
 * compiling, keeps passing every other test, and on device silently talks about the other
 * keyboard — `MultiLanguageUtils` read the original app's subtype count that way, and
 * `RecognizerIntent.EXTRA_CALLING_PACKAGE` attributed our dictation to it. So: no source string
 * literal and no resource value under `app/src/main` may contain `com.blackberry.keyboard`.
 * Comments may, and several deliberately do, because the original app is a real thing this code
 * has to talk about.
 */
class PackageIdentityTest {

    /** `app/src/<name>`, however the test happens to be launched (cwd is usually `app/`). */
    private fun sourceSet(name: String): File {
        val dir = sequenceOf("src", "app/src", "../src", "../app/src", "../../app/src")
            .map { File(it, name).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertTrue("app/src/$name not found from ${File(".").canonicalPath}", dir != null)
        return dir!!
    }

    private fun mainDir(): File = sourceSet("main")

    /** The id the *release* build installs as: the test runs on the debug variant. */
    private val releaseApplicationId = BuildConfig.APPLICATION_ID.removeSuffix(".debug")

    private val oldId = "com.blackberry.keyboard"

    @Test
    fun applicationIdIsOurOwn() {
        assertEquals("dev.bbkb.ime", releaseApplicationId)
        assertTrue(
            "debug builds keep the .debug suffix, got ${BuildConfig.APPLICATION_ID}",
            BuildConfig.APPLICATION_ID == "dev.bbkb.ime" || BuildConfig.APPLICATION_ID == "dev.bbkb.ime.debug"
        )
    }

    /**
     * The generated classes live under the same name as everything else. A namespace that drifts
     * back would resurrect a second `R` and the split-brain imports this move existed to remove.
     */
    @Test
    fun theGeneratedClassesLiveUnderOurOwnNamespace() {
        assertEquals("dev.bbkb.ime", BuildConfig::class.java.`package`!!.name)
    }

    /** XML comments, which are allowed to name the original app. */
    private val xmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Every `"..."` literal in a Java/Kotlin source, as (line number, text) — skipping `//` and
     * `/* */` comments, which are prose and may say whatever they need to about the other app.
     * A hand-rolled scan rather than a regex because the two states are mutually escaping: a `"`
     * inside a comment must not open a literal, and a `//` inside a literal must not open a
     * comment. Kotlin raw strings (`"""`) fall out of this as three adjacent empty literals,
     * which is harmless here — none of them can contain a package name.
     */
    private fun stringLiterals(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\n' -> { line++; i++ }
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) {
                        if (source[i] == '\n') line++
                        i++
                    }
                    i += 2
                }
                c == '\'' -> {           // char literal, or a Kotlin char; skip with its escape
                    i++
                    if (i < source.length && source[i] == '\\') i++
                    while (i < source.length && source[i] != '\'' && source[i] != '\n') i++
                    i++
                }
                c == '"' -> {
                    val start = i
                    val startLine = line
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        if (i < source.length && source[i] == '\n') line++
                        i++
                    }
                    i++
                    out += startLine to source.substring(start, minOf(i, source.length))
                }
                else -> i++
            }
        }
        return out
    }

    /**
     * The scan is the whole test, so it is itself tested: a scanner that quietly returned nothing
     * would make [noSourceStringLiteralNamesTheOldPackage] pass forever.
     */
    @Test
    fun theLiteralScannerSeesCodeAndIgnoresComments() {
        val sample = """
            // a line comment mentioning "com.blackberry.keyboard"
            /* a block comment mentioning "com.blackberry.keyboard"
               over two lines */
            String a = "com.blackberry.keyboard";           // <- the one that counts
            String b = "not // a comment";
            String c = "an escaped quote \" and com.blackberry.keyboard";
            char q = '"';
        """.trimIndent()
        val found = stringLiterals(sample).filter { it.second.contains(oldId) }.map { it.second }
        assertEquals(
            listOf("\"com.blackberry.keyboard\"", "\"an escaped quote \\\" and com.blackberry.keyboard\""),
            found
        )
    }

    @Test
    fun noSourceStringLiteralNamesTheOldPackage() {
        val offenders = mutableListOf<String>()
        mainDir().resolve("java").walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file ->
                stringLiterals(file.readText())
                    .filter { it.second.contains(oldId) }
                    .forEach { offenders += "${file.name}:${it.first}: ${it.second}" }
            }
        assertTrue(
            "these string literals still name the original app's package; read " +
                "BuildConfig.APPLICATION_ID or Context.getPackageName() instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun noResourceValueNamesTheOldPackage() {
        val offenders = mutableListOf<String>()
        val res = mainDir().resolve("res")
        (res.walkTopDown().filter { it.isFile && it.extension == "xml" } +
            sequenceOf(mainDir().resolve("AndroidManifest.xml")))
            .forEach { file ->
                val body = xmlComment.replace(file.readText(), "")
                if (body.contains(oldId)) {
                    offenders += "${file.parentFile.name}/${file.name}"
                }
            }
        assertTrue(
            "these resources still name the original app's package outside a comment:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * The default preferences file is named `<applicationId>_preferences.xml` by the platform, and
     * `res/xml` cannot interpolate `${'$'}{applicationId}` — so the backup allowlists hardcode it and
     * this is what keeps them honest. Naming the wrong file is silent: backup simply restores
     * nothing, which is how the personal dictionary was already lost once.
     */
    @Test
    fun theBackupAllowlistsNameThisAppsPreferencesFile() {
        val expected = "$releaseApplicationId" + "_preferences.xml"
        listOf("backupscheme.xml", "data_extraction_rules.xml").forEach { name ->
            val text = mainDir().resolve("res/xml/$name").readText()
            val paths = Regex("""domain="sharedpref"\s+path="([^"]+)"""").findAll(text)
                .map { it.groupValues[1] }.toList()
            assertTrue("$name declares no sharedpref include", paths.isNotEmpty())
            assertTrue(
                "$name must include $expected, found $paths",
                paths.contains(expected)
            )
        }
    }

    /**
     * Nothing in the *source* manifest may declare a name the original app also declares. A
     * `<permission>` is the case that genuinely blocks a side-by-side install: two APKs signed
     * with different keys cannot both define the same permission name, and the second one fails
     * with INSTALL_FAILED_DUPLICATE_PERMISSION. We declare none, and the one BlackBerry-named
     * entry that used to be here — `<uses-library android:name="com.blackberry.only">`, a vendor
     * shared library, never a permission — is gone.
     *
     * The merged manifest does carry one custom permission,
     * `${'$'}{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` from androidx.core, and one
     * `<uses-library>` pair from androidx.window. Both are already namespaced by the applicationId
     * or by `androidx.`, so neither can collide; this test reads the source manifest, which is the
     * only part we write.
     */
    @Test
    fun theManifestDeclaresNoSharedNames() {
        val body = xmlComment.replace(mainDir().resolve("AndroidManifest.xml").readText(), "")
        assertTrue("the app must declare no custom <permission>", !body.contains("<permission"))
        assertTrue("the app must declare no <permission-group>", !body.contains("<permission-group"))
        assertTrue("the app must declare no <uses-library>", !body.contains("<uses-library"))
        assertTrue("sharedUserId would tie us to another app", !body.contains("sharedUserId"))

        // Every authority we *declare* must be namespaced by the applicationId, or it collides
        // with the original app's FileProvider. "user_dictionary" is the system provider we
        // <queries> for, not one of ours.
        val authorities = Regex("""android:authorities="([^"]+)"""").findAll(body)
            .map { it.groupValues[1] }.toList()
        val ours = authorities.filter { it != "user_dictionary" }
        assertEquals("expected exactly one declared authority", 1, ours.size)
        assertTrue(
            "declared authorities must start with \${applicationId}, got $ours",
            ours.all { it.startsWith("\${applicationId}") }
        )
    }

    // ==================== the class root ====================

    /** `main`, `test` and `debug` — every source set we write by hand. */
    private fun ourSourceSets() = listOf("main", "test", "debug").map(::sourceSet)

    private fun textFilesIn(dir: File) = dir.walkTopDown().filter { file ->
        file.isFile && file.extension.lowercase() in
            setOf("java", "kt", "kts", "xml", "pro", "txt", "json", "properties", "cfg")
    }

    /**
     * The private commands `BlackBerryIME` sends to the *focused editor* via
     * `InputConnection.performPrivateCommand` were the last strings under the old root: they are
     * wire identifiers BlackBerry's own apps (Hub+ and friends) listened for, not class names.
     * The owner chose to own that namespace too (2026-09-21), so they now read
     * `dev.bbkb.ime.<COMMAND>` and nothing is struck out before the scan any more. The list is
     * kept empty on purpose: a future exception must be added here by name, never by pattern.
     */
    private val privateCommandWireNames = emptyList<String>()

    /**
     * The old class root, spelled in halves so that this file — which the scan below reads like
     * any other — does not trip its own guard. The dotted form is the one source and XML use; the
     * slashed form is what `baseline-prof.txt` descriptors and JNI signatures use.
     */
    private val oldRoots = listOf("com.blackberry." + "inputmethod", "com/blackberry/" + "inputmethod")

    /**
     * Nothing we write names the old class root any more — not a package line, not an import, not
     * a custom-view tag in a layout, not a manifest component, not a proguard keep, not a
     * baseline-profile descriptor. A leftover in XML is the dangerous kind: it compiles, and it
     * fails at inflation time on the device.
     *
     * [privateCommandWireNames] is the only allow-list, and it is empty.
     */
    @Test
    fun nothingWeWriteNamesTheOldClassRoot() {
        val offenders = mutableListOf<String>()
        (ourSourceSets().flatMap { textFilesIn(it).toList() } + proguardRules()).forEach { file ->
            var body = file.readText()
            privateCommandWireNames.forEach { body = body.replace(it, "") }
            oldRoots.forEach { old ->
                if (body.contains(old)) offenders += "${file.path}: $old"
            }
        }
        assertTrue(
            "the class root is dev.bbkb.ime; these still name the old one:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** `app/proguard-rules.pro`, which sits beside `src/` rather than inside it. */
    private fun proguardRules(): File {
        val file = File(mainDir().parentFile.parentFile, "proguard-rules.pro")
        assertTrue("proguard-rules.pro not found at ${file.path}", file.isFile)
        return file
    }

    /**
     * The generated classes moved with the namespace, so an `R`, `BuildConfig` or `databinding`
     * reference qualified by the old namespace cannot resolve — but a stale one would only
     * surface as a confusing compile error, and the point here is to say why it is wrong. Bare
     * `com.blackberry.keyboard` is *not* forbidden: it names the original app, which the
     * side-by-side fixtures and a good deal of prose legitimately talk about, and
     * [noSourceStringLiteralNamesTheOldPackage] is what keeps it out of `app/src/main`'s literals.
     */
    @Test
    fun nothingReferencesTheOldNamespacesGeneratedClasses() {
        val stale = Regex("""com\.blackberry\.keyboard\.(R\b|R\$|BuildConfig\b|databinding\b)""")
        val offenders = mutableListOf<String>()
        (ourSourceSets().flatMap { textFilesIn(it).toList() } + proguardRules()).forEach { file ->
            stale.find(file.readText())?.let { offenders += "${file.path}: ${it.value}" }
        }
        assertTrue(
            "the generated classes live under dev.bbkb.ime now:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * `com.blackberry.nuanceshim` is the sole package this app still owns under a `com.blackberry`
     * root, and it is allow-listed here rather than tolerated: the prebuilt engine blob binds to
     * its classes *by name*. It exports 54 `Java_com_blackberry_nuanceshim_NuanceSDK_*` entry
     * points and `FindClass`es `com/blackberry/nuanceshim/{NuanceSDK,KeyInfo,WordInfo}`, and our
     * own C in `app/src/main/cpp` defines `Java_com_blackberry_nuanceshim_*` for `Xt9Kdb`,
     * `Xt9KdbVariant`, `Xt9Trace` and `Et9Probe`. Renaming the package is an
     * `UnsatisfiedLinkError` at the first keystroke — see that package's `package-info.java`.
     *
     * A second `com.blackberry.*` package appearing here means somebody put a non-JNI class in
     * the blob's namespace, which is the mistake this catches.
     */
    @Test
    fun theOnlyComBlackberryPackageLeftIsTheJniShim() {
        val found = mutableSetOf<String>()
        ourSourceSets().forEach { root ->
            val com = File(root, "java/com/blackberry")
            if (com.isDirectory) com.listFiles()?.filter { it.isDirectory }?.forEach { found += it.name }
        }
        assertEquals(setOf("nuanceshim"), found)
    }
}
