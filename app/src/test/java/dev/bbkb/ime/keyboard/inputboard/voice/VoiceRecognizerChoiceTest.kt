package dev.bbkb.ime.keyboard.inputboard.voice

import dev.bbkb.ime.keyboard.inputboard.voice.VoiceRecognizerChoice.Kind
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceRecognizerChoice.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Speech recognizer" setting's decisions, as plain values: which services the list offers,
 * what a stored choice builds, where each recogniser's languages are remembered, and when a
 * permission error names the chosen app. The services are the KEY2's (Google TTS, the Google app,
 * the Claude app) plus the open-source recognisers the empty-list summary suggests.
 */
class VoiceRecognizerChoiceTest {

    private val googleTts = Service(
        "com.google.android.tts",
        "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
        "Speech Recognition & Synthesis",
    )
    private val googleApp = Service(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        "Google",
    )
    private val claude = Service("com.anthropic.claude", "com.anthropic.claude.voice.RecognitionService", "Claude")
    private val sayboard = Service("com.elishaazaria.sayboard", "com.elishaazaria.sayboard.recognition.RecognitionService", "Sayboard")

    private val key2 = listOf(googleTts, googleApp, claude)

    private fun component(service: Service) = service.packageName + "/" + service.className

    // ── the provider list ────────────────────────────────────────────────────

    @Test
    fun `providers are listed by app label in alphabetical order`() {
        val providers = VoiceRecognizerChoice.buildProviders(key2, null)

        assertEquals(listOf("Claude", "Google", "Speech Recognition & Synthesis"), providers.map { it.label })
        assertEquals(component(claude), providers[0].component)
        assertEquals("com.anthropic.claude", providers[0].packageName)
    }

    @Test
    fun `sorting ignores case`() {
        val lower = Service("org.example.whisper", "org.example.whisper.Service", "whisperIME")
        val providers = VoiceRecognizerChoice.buildProviders(listOf(lower, sayboard, claude), null)

        assertEquals(listOf("Claude", "Sayboard", "whisperIME"), providers.map { it.label })
    }

    @Test
    fun `the service the phone's setting names is marked as the system default`() {
        val providers = VoiceRecognizerChoice.buildProviders(key2, component(googleApp))

        assertEquals(listOf(false, true, false), providers.map { it.isSystemDefault })
        assertEquals("Google", VoiceRecognizerChoice.systemDefault(providers)?.label)
    }

    /** The secure setting may hold the short ".Class" form; it is the same service. */
    @Test
    fun `a shorthand default component still marks its service`() {
        val providers = VoiceRecognizerChoice.buildProviders(listOf(sayboard), "com.elishaazaria.sayboard/.recognition.RecognitionService")

        assertTrue(providers.single().isSystemDefault)
    }

    @Test
    fun `with no default selected or installed nothing is marked`() {
        assertNull(VoiceRecognizerChoice.systemDefault(VoiceRecognizerChoice.buildProviders(key2, "")))
        assertNull(VoiceRecognizerChoice.systemDefault(VoiceRecognizerChoice.buildProviders(key2, "com.gone/com.gone.Service")))
    }

    @Test
    fun `an app with no readable label is listed by its package`() {
        val unlabelled = Service("org.example.asr", "org.example.asr.Recognizer", null)

        assertEquals("org.example.asr", VoiceRecognizerChoice.buildProviders(listOf(unlabelled), null).single().label)
    }

    /** One app exporting two services would otherwise show two identical rows. */
    @Test
    fun `two services from one app are told apart by class name`() {
        val second = Service(googleApp.packageName, "com.google.android.voicesearch.serviceapi.OnDeviceRecognitionService", "Google")
        val labels = VoiceRecognizerChoice.buildProviders(listOf(googleApp, second, claude), null).map { it.label }

        assertEquals(listOf("Claude", "Google (GoogleRecognitionService)", "Google (OnDeviceRecognitionService)"), labels)
    }

    @Test
    fun `duplicate and malformed services are dropped`() {
        val providers = VoiceRecognizerChoice.buildProviders(
            listOf(claude, claude, Service("", "x.Y", "Blank"), Service("a.b", null, "Null class")),
            null,
        )

        assertEquals(listOf("Claude"), providers.map { it.label })
    }

    @Test
    fun `nothing installed is an empty list`() {
        assertTrue(VoiceRecognizerChoice.buildProviders(emptyList(), "x/y").isEmpty())
        assertTrue(VoiceRecognizerChoice.buildProviders(null, null).isEmpty())
    }

    // ── setting → what dictation builds ──────────────────────────────────────

    private val installed = VoiceRecognizerChoice.buildProviders(key2, component(googleApp))

    @Test
    fun `an empty setting is the system default`() {
        for (setting in listOf("", "  ", null)) {
            val selection = VoiceRecognizerChoice.select(setting, 34, true, installed)
            assertEquals(Kind.SYSTEM_DEFAULT, selection.kind)
            assertEquals("", selection.id())
        }
    }

    @Test
    fun `an installed app builds a recogniser bound to its component`() {
        val selection = VoiceRecognizerChoice.select(component(claude), 34, false, installed)

        assertEquals(Kind.COMPONENT, selection.kind)
        assertEquals(component(claude), selection.component)
        assertEquals("Claude", selection.label)
        assertEquals("com.anthropic.claude", selection.packageName)
        assertEquals(component(claude), selection.id())
    }

    @Test
    fun `a shorthand stored component resolves to the same app`() {
        val selection = VoiceRecognizerChoice.select("com.anthropic.claude/.voice.RecognitionService", 34, false, installed)

        assertEquals(component(claude), selection.component)
    }

    /** The app was uninstalled since it was chosen: fall back, and let the default path explain. */
    @Test
    fun `an app that is no longer installed falls back to the system default`() {
        val selection = VoiceRecognizerChoice.select(component(sayboard), 34, true, installed)

        assertEquals(Kind.SYSTEM_DEFAULT, selection.kind)
        assertEquals(VoiceRecognizerChoice.Selection.DEFAULT, selection)
    }

    @Test
    fun `a value that is not a component falls back to the system default`() {
        assertEquals(Kind.SYSTEM_DEFAULT, VoiceRecognizerChoice.select("not a component", 34, true, installed).kind)
        assertEquals(Kind.SYSTEM_DEFAULT, VoiceRecognizerChoice.select("pkg/", 34, true, installed).kind)
    }

    @Test
    fun `the on-device recogniser needs API 31 and a device that has one`() {
        assertEquals(Kind.ON_DEVICE, VoiceRecognizerChoice.select("ondevice", 31, true, installed).kind)
        assertEquals(Kind.ON_DEVICE, VoiceRecognizerChoice.select("ondevice", 35, true, emptyList()).kind)
        assertEquals("ondevice", VoiceRecognizerChoice.select("ondevice", 34, true, installed).id())

        assertEquals("below API 31 there is no on-device recogniser",
            Kind.SYSTEM_DEFAULT, VoiceRecognizerChoice.select("ondevice", 30, true, installed).kind)
        assertEquals("a phone without one falls back",
            Kind.SYSTEM_DEFAULT, VoiceRecognizerChoice.select("ondevice", 34, false, installed).kind)
    }

    /** What VoiceRecognitionManager compares to decide whether to rebuild. */
    @Test
    fun `selections are equal exactly when they name the same recogniser`() {
        val claudeAgain = VoiceRecognizerChoice.select(component(claude), 34, false, installed)
        val google = VoiceRecognizerChoice.select(component(googleApp), 34, false, installed)

        assertEquals(VoiceRecognizerChoice.select(component(claude), 34, false, installed), claudeAgain)
        assertFalse(claudeAgain == google)
        assertFalse(claudeAgain == VoiceRecognizerChoice.Selection.DEFAULT)
        assertFalse(VoiceRecognizerChoice.Selection.DEVICE == VoiceRecognizerChoice.Selection.DEFAULT)
    }

    // ── each recogniser's remembered languages ───────────────────────────────

    /** The default keeps the key it always had, so a cache from before this setting is still found. */
    @Test
    fun `the system default keeps the original language cache key`() {
        assertEquals("voice_input_language_cache", VoiceRecognizerChoice.languageCacheKey(""))
        assertEquals("voice_input_language_cache", VoiceRecognizerChoice.languageCacheKey(null))
        assertEquals("voice_input_language_cache", VoiceRecognizerChoice.languageCacheKey(VoiceRecognizerChoice.Selection.DEFAULT.id()))
    }

    @Test
    fun `every recogniser remembers its languages under its own key`() {
        val keys = listOf(
            VoiceRecognizerChoice.Selection.DEFAULT.id(),
            VoiceRecognizerChoice.Selection.DEVICE.id(),
            component(claude),
            component(googleApp),
            component(googleTts),
        ).map { VoiceRecognizerChoice.languageCacheKey(it) }

        assertEquals("switching recognisers must never read another's list", keys.size, keys.distinct().size)
        assertEquals("voice_input_language_cache_ondevice", keys[1])
        assertEquals("voice_input_language_cache_" + component(claude), keys[2])
        // The settings backup excludes the whole prefix, so none of these travel to another phone.
        assertTrue(keys.all { it.startsWith("voice_input_language_cache") })
    }

    // ── the permission error ─────────────────────────────────────────────────

    @Test
    fun `a chosen app without the microphone is named`() {
        val selection = VoiceRecognizerChoice.select(component(claude), 34, false, installed)

        assertEquals("Claude", VoiceRecognizerChoice.appNeedingPermission(selection, true))
    }

    /** The recogniser checks the caller too, so while we lack it, asking for ours comes first. */
    @Test
    fun `a chosen app is not blamed while this keyboard lacks the permission itself`() {
        val selection = VoiceRecognizerChoice.select(component(claude), 34, false, installed)

        assertNull(VoiceRecognizerChoice.appNeedingPermission(selection, false))
    }

    @Test
    fun `the default and on-device recognisers keep asking for our own permission`() {
        assertNull(VoiceRecognizerChoice.appNeedingPermission(VoiceRecognizerChoice.Selection.DEFAULT, true))
        assertNull(VoiceRecognizerChoice.appNeedingPermission(VoiceRecognizerChoice.Selection.DEVICE, true))
        assertNull(VoiceRecognizerChoice.appNeedingPermission(null, true))
    }
}
