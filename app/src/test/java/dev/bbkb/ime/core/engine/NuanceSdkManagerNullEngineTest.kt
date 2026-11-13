package dev.bbkb.ime.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Touch call sites must survive a null [NuanceSDKManager.getInstance] (engine construction failed,
 * e.g. `UnsatisfiedLinkError`), where the keyboard must still type. On the JVM
 * `ImeApplication.getInstance()` is null, so these calls take that same branch.
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
    fun factsAreNoneWithoutAnEngine() {
        // The main thread reads these instead of calling into the engine; with no engine it
        // must get the documented defaults, not a null or a throw.
        assertNull(NuanceSDKManager.peekInstance())
        assertEquals(EngineFacts.NONE, NuanceSDKManager.facts())
        assertEquals(" ", NuanceSDKManager.facts().defaultWordSeparator)
        assertNull(NuanceSDKManager.facts().primaryLanguage)
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
     * Covers the call sites, which a JVM test cannot instantiate (PointerTracker needs a live view):
     * a bare `getInstance().touch*` in either file is the defect, likely when a touch entry is added.
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
