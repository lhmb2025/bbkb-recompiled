package dev.bbkb.ime.core.device.touch.shizuku

import dev.bbkb.ime.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The debug probe broadcast's extras. `adb shell am broadcast` sends --es/--ei/--ez as String,
 * Integer and Boolean, and people type whichever they remember, so all are accepted.
 */
class ShizukuTouchProbeArgsTest {

    private fun ok(vararg extras: Pair<String, Any?>): ShizukuTouchProbeArgs {
        val parsed = ShizukuTouchProbeArgs.parse(mapOf(*extras))
        assertTrue("expected Ok, got $parsed", parsed is ShizukuTouchProbeArgs.Parsed.Ok)
        return (parsed as ShizukuTouchProbeArgs.Parsed.Ok).args
    }

    private fun error(vararg extras: Pair<String, Any?>): String {
        val parsed = ShizukuTouchProbeArgs.parse(mapOf(*extras))
        assertTrue("expected Error, got $parsed", parsed is ShizukuTouchProbeArgs.Parsed.Error)
        return (parsed as ShizukuTouchProbeArgs.Parsed.Error).message
    }

    @Test
    fun aDeviceNameAloneUsesTheDefaults() {
        val args = ok("device" to "touch_keypad")
        assertEquals(TouchDeviceMatcher.exact("touch_keypad"), args.matcher)
        assertEquals(false, args.grab)
        assertEquals(ShizukuTouchProbeArgs.DEFAULT_SECONDS, args.seconds)
    }

    @Test
    fun aRegexSelectsByPattern() {
        val args = ok("regex" to "^(touchPad|mtk-pad)$", "grab" to true, "seconds" to 30)
        assertEquals(TouchDeviceMatcher.regex("^(touchPad|mtk-pad)$"), args.matcher)
        assertTrue(args.grab)
        assertEquals(30, args.seconds)
    }

    @Test
    fun exactlyOneOfDeviceAndRegexIsRequired() {
        assertTrue(error().contains("missing"))
        assertTrue(error("device" to "", "regex" to "").contains("missing"))
        assertTrue(error("device" to "a", "regex" to "b").contains("not both"))
        assertTrue(error("regex" to "([oops").contains("not a valid pattern"))
    }

    @Test
    fun grabAcceptsBooleansStringsAndNumbers() {
        assertTrue(ok("device" to "d", "grab" to "true").grab)
        assertTrue(ok("device" to "d", "grab" to "1").grab)
        assertTrue(ok("device" to "d", "grab" to "YES").grab)
        assertEquals(false, ok("device" to "d", "grab" to "false").grab)
        assertTrue(ok("device" to "d", "grab" to 1).grab)
        assertEquals(false, ok("device" to "d", "grab" to 0).grab)
        assertTrue(error("device" to "d", "grab" to "maybe").contains("grab"))
    }

    @Test
    fun secondsAcceptsIntsLongsAndStringsAndIsBounded() {
        assertEquals(20, ok("device" to "d", "seconds" to "20").seconds)
        assertEquals(20, ok("device" to "d", "seconds" to " 20 ").seconds)
        assertEquals(45, ok("device" to "d", "seconds" to 45L).seconds)
        assertEquals(ShizukuTouchProbeArgs.MAX_SECONDS, ok("device" to "d", "seconds" to 100_000).seconds)
        assertTrue(error("device" to "d", "seconds" to "soon").contains("not a number"))
        assertTrue(error("device" to "d", "seconds" to 0).contains("positive"))
        assertTrue(error("device" to "d", "seconds" to -5).contains("positive"))
    }

    /**
     * The receiver is registered only by the debug manifest, with `${applicationId}` actions;
     * the code builds the same strings from BuildConfig. Drift would make the probe silently deaf.
     */
    @Test
    fun theDebugManifestDeclaresTheReceiverWithTheSameActions() {
        val manifest = sequenceOf("src/debug", "app/src/debug", "../app/src/debug")
            .map { File(it, "AndroidManifest.xml") }
            .firstOrNull { it.isFile }
        assertTrue("app/src/debug/AndroidManifest.xml not found from ${File(".").canonicalPath}", manifest != null)
        val text = manifest!!.readText()
        assertTrue(text.contains("android:name=\"${ShizukuTouchProbeReceiver::class.java.name}\""))
        assertTrue("only adb's shell may send it", text.contains("android:permission=\"android.permission.DUMP\""))
        for (action in listOf(
            ShizukuTouchProbeReceiver.ACTION_PROBE,
            ShizukuTouchProbeReceiver.ACTION_DEVICES,
            ShizukuTouchProbeReceiver.ACTION_STOP,
        )) {
            val declared = action.replace(BuildConfig.APPLICATION_ID, "\${applicationId}")
            assertTrue("missing <action> $declared", text.contains("<action android:name=\"$declared\"/>"))
        }
        // And nothing in the main manifest registers it for release builds.
        val main = File(manifest.parentFile.parentFile, "main/AndroidManifest.xml").readText()
        assertTrue(!main.contains(ShizukuTouchProbeReceiver::class.java.simpleName))
    }
}
