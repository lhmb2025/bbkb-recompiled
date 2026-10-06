package dev.bbkb.ime.core.textinput

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.harness.PipelineHarness
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.MockedStatic
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * `InputLogic.updateDynamicLearningState()` and the incognito flag
 * (`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`).
 *
 * The cache used to key on `inputType` alone, so moving between two fields of the same type
 * where only one is incognito (an ordinary and a private tab of the same browser) kept the first
 * field's learning state. imeOptions is now part of the key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class InputLogicDynamicLearningStateTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>
    private val learning = mock(DynamicLearningManager::class.java)
    private val clipboard = mock(ClipboardController::class.java)
    private var editor = EditorInfo()

    private val TEXT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val INCOGNITO = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    @Before
    fun setUp() {
        h = PipelineHarness()
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settingsWith("isDynamicLearningEnabled" to true))
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        `when`(h.ime.dynamicLearningManager).thenReturn(learning)
        `when`(h.ime.clipboardController).thenReturn(clipboard)
        `when`(h.ime.currentInputEditorInfo).thenAnswer { editor }
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    private fun focus(inputType: Int, imeOptions: Int = 0) {
        editor = EditorInfo().apply { this.inputType = inputType; this.imeOptions = imeOptions }
    }

    @Test
    fun sameInputType_differentImeOptions_isReEvaluated() {
        focus(TEXT)
        h.inputLogic.updateDynamicLearningState()
        verify(learning).setDynamicLearningEnabled(true)
        verify(learning).setNoPersonalizedLearning(false)
        clearInvocations(learning, clipboard)

        focus(TEXT, INCOGNITO)
        h.inputLogic.updateDynamicLearningState()

        verify(learning).setDynamicLearningEnabled(false)
        verify(learning).setNoPersonalizedLearning(true)
        verify(clipboard).setNoPersonalizedLearning(true)
    }

    @Test
    fun leavingTheIncognitoField_turnsLearningBackOn() {
        focus(TEXT, INCOGNITO)
        h.inputLogic.updateDynamicLearningState()
        clearInvocations(learning, clipboard)

        focus(TEXT)
        h.inputLogic.updateDynamicLearningState()

        verify(learning).setDynamicLearningEnabled(true)
        verify(learning).setNoPersonalizedLearning(false)
        verify(clipboard).setNoPersonalizedLearning(false)
    }

    @Test
    fun anUnchangedEditor_stillShortCircuits() {
        focus(TEXT, INCOGNITO)
        h.inputLogic.updateDynamicLearningState()
        clearInvocations(learning, clipboard)

        h.inputLogic.updateDynamicLearningState()

        verify(learning, never()).setDynamicLearningEnabled(anyBoolean())
        verify(learning, never()).setNoPersonalizedLearning(anyBoolean())
    }

    @Test
    fun theLearningPreferenceIsRecordedSeparately_forContactsImport() {
        focus(TEXT, INCOGNITO)
        h.inputLogic.updateDynamicLearningState()
        verify(learning).setLearningPreferenceEnabled(true)
    }

    @Test
    fun isLearningAllowed_combinesInputTypeAndTheIncognitoFlag() {
        val normal = EditorInfo().apply { inputType = TEXT }
        val incognito = EditorInfo().apply { inputType = TEXT; imeOptions = INCOGNITO }
        val password = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertTrue(InputLogic.isLearningAllowed(normal))
        assertFalse(InputLogic.isLearningAllowed(incognito))
        assertFalse(InputLogic.isLearningAllowed(password))
    }

    @Test
    fun editorCapabilities_mirrorsTheFlag_onEveryInputClass() {
        fun caps(inputType: Int, imeOptions: Int) = EditorCapabilities(
            EditorInfo().apply { this.inputType = inputType; this.imeOptions = imeOptions },
            false, "dev.bbkb.ime.debug", Locale.US, false,
        )
        assertTrue(caps(TEXT, INCOGNITO).noPersonalizedLearning)
        assertFalse(caps(TEXT, 0).noPersonalizedLearning)
        // The non-text early return must still record it.
        assertTrue(caps(InputType.TYPE_CLASS_NUMBER, INCOGNITO).noPersonalizedLearning)
        assertFalse(EditorCapabilities.hasNoPersonalizedLearningFlag(null))
    }
}
