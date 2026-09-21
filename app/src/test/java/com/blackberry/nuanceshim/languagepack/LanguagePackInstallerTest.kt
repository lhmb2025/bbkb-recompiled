package com.blackberry.nuanceshim.languagepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for [LanguagePackInstaller.unpackArchive], the public entry point that extracts an LDB
 * archive into `getNoBackupFilesDir()/nuance/<locale>`.
 *
 * Covers the two defects the September 2026 audit found there:
 *  - PD-3: Zip Slip. The entry name was joined to the destination with no containment check,
 *    so `../../<anything>` escaped the language-pack directory. Sibling app directories
 *    include the one holding the dynamic learning model.
 *  - PD-2: a hand-decompiled try/finally that collapsed into an outer catch assigning the
 *    throwable to a dead local and returning normally, so a completely failed extraction was
 *    reported as a successful install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LanguagePackInstallerTest {

    private lateinit var installer: LanguagePackInstaller
    private lateinit var destDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val registry = LanguagePackRegistry()
        registry.addLanguagePack(LanguagePackInfo().apply {
            language = "xx"
            country = "YY"
            name = "Test Pack"
            path = "ldb/xx_YY"
            version = 1.0
            setPreinstalledFlag(false)
        })
        installer = LanguagePackInstaller(context, "xx", "YY", registry)
        destDir = LanguagePackInstaller.getLanguagePackDirectory(context, "xx_YY")
        LanguagePackInstaller.deleteRecursively(destDir)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun ordinaryEntriesExtractIntoTheLanguagePackDirectory() {
        val ok = installer.unpackArchive(
            zipOf(
                "version.txt" to "1.0\n".toByteArray(),
                "sub/xx_YY.ldb" to ByteArray(64) { it.toByte() },
            ).inputStream()
        )

        assertTrue("a well-formed archive should install", ok)
        assertEquals("1.0\n", File(destDir, "version.txt").readText())
        assertEquals(64, File(destDir, "sub/xx_YY.ldb").length())
    }

    @Test
    fun entryEscapingTheDestinationIsRejected() {
        val escapee = File(destDir.parentFile!!, "escaped.txt")
        escapee.delete()

        val ok = installer.unpackArchive(
            zipOf("../escaped.txt" to "pwned".toByteArray()).inputStream()
        )

        assertFalse("a Zip Slip entry must fail the install, not silently succeed", ok)
        assertFalse(
            "entry must not be written outside the language-pack directory: $escapee",
            escapee.exists()
        )
    }

    @Test
    fun deeplyEscapingEntryIsRejected() {
        // The shape that matters: nuance/<locale>/../../databases/x reaches a sibling of the
        // whole nuance directory.
        val ok = installer.unpackArchive(
            zipOf("../../databases/evil.db" to "pwned".toByteArray()).inputStream()
        )

        assertFalse(ok)
        val escapee = File(destDir.parentFile!!.parentFile, "databases/evil.db")
        assertFalse("entry escaped to $escapee", escapee.exists())
    }

    @Test
    fun absolutePathEntryStaysInsideTheDestination() {
        // new File(parent, "/tmp/x") normalises the leading separator away, so this is not an
        // escape -- but the canonical-path check must not reject it either, and it must land
        // under the language-pack directory rather than at the filesystem root.
        val ok = installer.unpackArchive(
            zipOf("/tmp/lp-absolute.txt" to "content".toByteArray()).inputStream()
        )

        assertTrue(ok)
        assertEquals("content", File(destDir, "tmp/lp-absolute.txt").readText())
        assertFalse(File("/tmp/lp-absolute.txt").exists())
    }

    @Test
    fun truncatedArchiveIsReportedAsFailure() {
        val complete = zipOf("xx_YY.ldb" to ByteArray(8192) { (it % 251).toByte() })
        val truncated = complete.copyOf(complete.size / 2)

        val ok = installer.unpackArchive(truncated.inputStream())

        assertFalse("a failed extraction must not be reported as a successful install", ok)
    }
}
