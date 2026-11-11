package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.speech.RecognitionSupport
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.distribution.DistributionConfig
import dev.bbkb.ime.core.distribution.LocalHttpServer
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageRepository
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * The Voice chip on the Language packs screen: after the name of each language the speech
 * recogniser has downloaded, and on no other row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LanguagePacksVoiceChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        // This keyboard enabled, as it is for anyone who can open its settings.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val infos = context.packageManager
            .queryIntentServices(Intent("android.view.InputMethod"), PackageManager.GET_META_DATA)
            .map { InputMethodInfo(context, it) }
        shadowOf(imm).setInputMethodInfoList(infos)
        shadowOf(imm).setEnabledInputMethodInfoList(infos)
        MultiLanguageRepository.clearInstance()
        RichInputMethodManager.init(context)
        ShadowSpeechRecognizer.reset()
        // The screen asks for the pack catalogue when it opens: keep that off the real network.
        PrefsManager.getPrefs(context).edit().clear()
            .putString(
                DistributionConfig.PREF_MANIFEST_URL,
                "http://127.0.0.1:${LocalHttpServer.closedPort()}/manifest.json",
            )
            .commit()
        ManifestSource(context).clearCache()
    }

    @After
    fun tearDown() {
        PrefsManager.getPrefs(context).edit().clear().commit()
        ShadowSpeechRecognizer.reset()
    }

    /** Has the recogniser answer once, which is what leaves the answer the screen opens with. */
    private fun recogniserHas(installed: List<String>, online: List<String>, supported: List<String>) {
        VoiceLanguages.refresh(context)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerSupportResult(
            RecognitionSupport.Builder()
                .setInstalledOnDeviceLanguages(installed)
                .setOnlineLanguages(online)
                .setPendingOnDeviceLanguages(emptyList())
                .setSupportedOnDeviceLanguages(supported)
                .build())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun showScreen() {
        composeRule.setContent { BlackBerryTheme { LanguagePacksScreen({}) } }
        // The installed packs arrive from an IO load.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("English").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun chips() =
        composeRule.onAllNodesWithContentDescription("Voice input works offline").fetchSemanticsNodes().size

    @Test
    fun `only the downloaded languages carry the chip`() {
        recogniserHas(
            installed = listOf("en-US", "fr-FR"),
            online = listOf("it-IT"),
            supported = listOf("de-DE", "es-ES"),
        )

        showScreen()

        assertEquals("English and French; not Italian (online only), German or Spanish (not fetched)",
            2, chips())
    }

    /** The top bar's search narrows the installed packs by name, and says when nothing matches. */
    @Test
    fun `search narrows the installed packs by name`() {
        showScreen()
        val before = composeRule.onAllNodesWithText("Preinstalled").fetchSemanticsNodes().size

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("engl")
        composeRule.waitForIdle()

        assertEquals(1, composeRule.onAllNodesWithText("English").fetchSemanticsNodes().size)
        assertTrue(
            "fewer installed packs are listed than before the search",
            composeRule.onAllNodesWithText("Preinstalled").fetchSemanticsNodes().size < before,
        )

        composeRule.onNode(hasSetTextAction()).performTextReplacement("zzzz")
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("No matches found").fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, composeRule.onAllNodesWithText("English").fetchSemanticsNodes().size)
    }

    @Test
    fun `no chip before the recogniser has ever answered`() {
        showScreen()

        assertEquals(0, chips())
    }

    @Test
    fun `no chip while voice input is off`() {
        recogniserHas(installed = listOf("en-US"), online = emptyList(), supported = emptyList())
        PrefsManager.getPrefs(context).edit().putBoolean("voice_input_enabled", false).commit()

        showScreen()

        assertEquals(0, chips())
    }
}
