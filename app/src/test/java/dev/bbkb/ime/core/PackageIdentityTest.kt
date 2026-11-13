package dev.bbkb.ime.core

import dev.bbkb.ime.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the app identity to `dev.bbkb.ime` (applicationId, namespace, class root) so it installs
 * beside `com.blackberry.keyboard`. A string literal holding the old id compiles and passes
 * everything else but talks about the other keyboard on device, so no source literal or resource
 * value under `app/src/main` may contain it. Comments may.
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

    /** A drifting namespace would bring back a second `R`. */
    @Test
    fun theGeneratedClassesLiveUnderOurOwnNamespace() {
        assertEquals("dev.bbkb.ime", BuildConfig::class.java.`package`!!.name)
    }

    /** XML comments, which may name the other app. */
    private val xmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Every `"..."` literal in a Java/Kotlin source as (line, text), skipping comments. Hand-rolled
     * because a `"` in a comment and a `//` in a literal must each be ignored; Kotlin raw strings
     * come out as three empty literals, which is harmless here.
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

    /** A scanner that returned nothing would make [noSourceStringLiteralNamesTheOldPackage] pass forever. */
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
     * The platform names the default prefs file `<applicationId>_preferences.xml` and `res/xml`
     * cannot interpolate, so the backup allowlists hardcode it. A wrong name silently backs up nothing.
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
     * Two differently signed APKs cannot define the same `<permission>` (INSTALL_FAILED_DUPLICATE_PERMISSION),
     * so the source manifest declares none. The merged manifest's androidx permission and
     * `<uses-library>` are namespaced already; only the source manifest is checked.
     */
    @Test
    fun theManifestDeclaresNoSharedNames() {
        val body = xmlComment.replace(mainDir().resolve("AndroidManifest.xml").readText(), "")
        assertTrue("the app must declare no custom <permission>", !body.contains("<permission"))
        assertTrue("the app must declare no <permission-group>", !body.contains("<permission-group"))
        assertTrue("the app must declare no <uses-library>", !body.contains("<uses-library"))
        assertTrue("sharedUserId would tie us to another app", !body.contains("sharedUserId"))

        // Declared authorities must be namespaced by the applicationId or they collide with the
        // other app's FileProvider. "user_dictionary" is the system provider we <queries> for.
        val authorities = Regex("""android:authorities="([^"]+)"""").findAll(body)
            .map { it.groupValues[1] }.toList()
        val ours = authorities.filter { it != "user_dictionary" }
        assertEquals(
            "the declared authorities",
            setOf(
                "\${applicationId}.fileprovider",
                "\${applicationId}.shizuku",
                "\${applicationId}.androidx-startup",
            ),
            ours.toSet()
        )
        assertTrue(
            "declared authorities must start with \${applicationId}, got $ours",
            ours.all { it.startsWith("\${applicationId}") }
        )
    }

    /** Every source set we write by hand. */
    private fun ourSourceSets() = listOf("main", "test", "debug").map(::sourceSet)

    private fun textFilesIn(dir: File) = dir.walkTopDown().filter { file ->
        file.isFile && file.extension.lowercase() in
            setOf("java", "kt", "kts", "xml", "pro", "txt", "json", "properties", "cfg")
    }

    /**
     * Wire names of private editor commands allowed to keep the old root. Empty on purpose: a
     * future exception must be added here by name, never by pattern.
     */
    private val privateCommandWireNames = emptyList<String>()

    /**
     * The old class root, split so this file does not trip its own scan. Dotted for source and XML,
     * slashed for `baseline-prof.txt` descriptors and JNI signatures.
     */
    private val oldRoots = listOf("com.blackberry." + "inputmethod", "com/blackberry/" + "inputmethod")

    /**
     * No package, import, layout tag, manifest component, keep rule or baseline-profile descriptor
     * names the old class root. An XML leftover compiles and fails at inflation on device.
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

    /** `app/proguard-rules.pro`, beside `src/`. */
    private fun proguardRules(): File {
        val file = File(mainDir().parentFile.parentFile, "proguard-rules.pro")
        assertTrue("proguard-rules.pro not found at ${file.path}", file.isFile)
        return file
    }

    /**
     * A stale `R`/`BuildConfig`/`databinding` reference under the old namespace would only fail
     * as a confusing compile error; this says why. Bare `com.blackberry.keyboard` is allowed here.
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
     * `com.blackberry.nuanceshim` must keep its name: the engine blob exports
     * `Java_com_blackberry_nuanceshim_NuanceSDK_*` and `FindClass`es `NuanceSDK`, `KeyInfo`, `WordInfo`
     * there, and our C defines JNI entries for `Xt9Kdb`, `Xt9KdbVariant`, `Xt9Trace`, `Et9Probe`.
     * Renaming is an `UnsatisfiedLinkError`. Any second `com.blackberry.*` package is a mistake.
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
