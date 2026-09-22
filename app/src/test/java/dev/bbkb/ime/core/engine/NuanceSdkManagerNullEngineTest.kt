package dev.bbkb.ime.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Audit L8 (docs/2026-09_kdb-touch-abi_audit.md §5): the touch call sites dereferenced a
 * nullable singleton.
 *
 * The null path is reachable for real — [NuanceSDKManager.getInstance] returns null whenever
 * `NuanceSDK`'s construction fails, including the `UnsatisfiedLinkError` case of the engine's
 * native library not loading, which is exactly the situation where the keyboard must still
 * type. On the JVM it is reachable for the simplest reason of all: `ImeApplication.getInstance()`
 * is null outside an Android process, so the calls below take the same branch the device takes
 * when the engine fails to load.
 */
class NuanceSdkManagerNullEngineTest {

    @Test
    fun sdkOrWarnReturnsNullInsteadOfThrowing() {
        assertNull(
            "with no engine the choke point must report unavailability, not hand out an instance",
            NuanceSDKManager.sdkOrWarn("test.sdkOrWarn"),
        )
    }

    @Test
    fun withSdkSkipsTheBlockWhenThereIsNoEngine() {
        var ran = false
        NuanceSDKManager.withSdk("test.withSdk") { ran = true }
        assertFalse("withSdk must not invoke its block with a null engine", ran)
    }

    @Test
    fun eachSiteWarnsOnlyOnce() {
        // A touch event fires per DOWN/MOVE/UP sample; a persistent load failure must not put a
        // line in the log for every one of them.
        val site = "test.warnOnce.${System.nanoTime()}"
        NuanceSDKManager.sdkOrWarn(site)
        NuanceSDKManager.sdkOrWarn(site)
        NuanceSDKManager.sdkOrWarn(site)
        assertEquals(
            "a site must be recorded exactly once",
            1,
            NuanceSDKManager.warnedSites.count { it == site },
        )
    }

    /**
     * The behavioural checks above cover the choke point; this covers the call sites, which a
     * JVM test cannot instantiate (PointerTracker needs a live keyboard view). A bare
     * `getInstance().touch*` in either file is the L8 defect itself, and it is what regresses
     * when someone adds the next touch entry point.
     */
    @Test
    fun noTouchCallSiteDereferencesTheNullableSingleton() {
        val offenders = sequenceOf(
            "dev/bbkb/ime/keyboard/internal/PointerTracker.java",
            "dev/bbkb/ime/keyboard/internal/GestureEventProcessor.java",
        ).flatMap { path ->
            sourceFile(path).lineSequence().withIndex()
                .filter { (_, line) -> line.contains("NuanceSDKManager.getInstance().") }
                .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }.toList()

        assertEquals(
            "touch call sites must go through NuanceSDKManager.sdkOrWarn(site) (audit L8): " +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    private fun sourceFile(path: String): String {
        val mainJava = sequenceOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("app/src/main/java not found from ${File(".").canonicalPath}", mainJava)
        return File(mainJava, path).readText()
    }
}
