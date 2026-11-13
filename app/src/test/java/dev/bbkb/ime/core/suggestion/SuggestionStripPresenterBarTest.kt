package dev.bbkb.ime.core.suggestion

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * New suggestions fill the strip and never switch the bar: FCC and Sym closing, a tap in the text
 * and the dictionary loading must not take down a menu the user raised. With the menu up the words
 * go to the hidden strip; typing is what puts the menu away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SuggestionStripPresenterBarTest {

    private lateinit var context: Context
    private lateinit var ime: BlackBerryIME
    private lateinit var auxBar: AuxBarManager
    private lateinit var uiCoordinator: InputViewCoordinator
    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var presenter: SuggestionStripPresenter

    private val words = SuggestedWords(
        arrayListOf(SuggestedWords.SuggestedWordInfo("hello", 100, 0, null, -1, -1, null)),
        null, false, false, 0,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().putBoolean("show_predictions", true).commit()
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB, true, false, true, "qwerty", "4row"),
        )
        SettingsManager.initialize(context)
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(info, false, context.packageName, Locale.US, false),
        )

        ime = Mockito.mock(BlackBerryIME::class.java)
        val inputLogic = Mockito.mock(InputLogic::class.java)
        ReflectionHelpers.setField(inputLogic, "mComposingTracker", Mockito.mock(ComposingTextTracker::class.java))
        Mockito.`when`(ime.getInputLogic()).thenReturn(inputLogic)
        Mockito.`when`(ime.getSettingsManager()).thenReturn(SettingsManager.getInstance())
        Mockito.`when`(ime.getUiUpdateHandler()).thenReturn(Mockito.mock(UIUpdateHandler::class.java))
        Mockito.`when`(ime.getSubtypeManager()).thenReturn(SubtypeManager.getInstance())
        Mockito.`when`(ime.hasAuxBarView()).thenReturn(true)
        Mockito.`when`(ime.onEvaluateInputViewShown()).thenReturn(true)

        auxBar = Mockito.mock(AuxBarManager::class.java)
        Mockito.`when`(ime.auxBarManager).thenReturn(auxBar)

        uiCoordinator = Mockito.mock(InputViewCoordinator::class.java)
        Mockito.`when`(uiCoordinator.shouldShowLatinSuggestionStrip(Mockito.any(), Mockito.anyBoolean(), Mockito.any()))
            .thenReturn(true)
        Mockito.`when`(uiCoordinator.shouldShowSuggestionStrip(Mockito.any(), Mockito.anyBoolean(), Mockito.any()))
            .thenReturn(true)
        Mockito.`when`(ime.getUiCoordinator()).thenReturn(uiCoordinator)

        uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        val switcher = Mockito.mock(KeyboardSwitcher::class.java)
        Mockito.`when`(switcher.getUnifiedInputBoardManager()).thenReturn(uim)
        Mockito.`when`(ime.getKeyboardSwitcher()).thenReturn(switcher)

        presenter = SuggestionStripPresenter(ime)
    }

    @After
    fun tearDown() {
        val manager = SettingsManager.getInstance()
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(manager, null)
        }
        PrefsManager.getPrefs(context).edit().clear().commit()
        DeviceProfile.initialize(null)
    }

    private fun menuUp(up: Boolean) {
        Mockito.`when`(uim.isShowing).thenReturn(up)
    }

    @Test
    fun withTheMenuUpNewWordsGoBehindIt() {
        menuUp(true)

        presenter.showOrNeutral(words)

        verify(auxBar).updateHiddenSuggestionStrip(words, false)
        verify(auxBar, never()).showSuggestionStrip(Mockito.any(SuggestedWords::class.java), Mockito.anyBoolean())
        verify(uiCoordinator, never()).hideUnifiedInputBoard()
    }

    @Test
    fun withTheStripUpNewWordsShowOnIt() {
        menuUp(false)

        presenter.showOrNeutral(words)

        verify(auxBar).showSuggestionStrip(words, false)
        verify(uiCoordinator, never()).hideUnifiedInputBoard()
    }

    /** A swipe's words take the other delivery path; it never takes the menu down either. */
    @Test
    fun aSwipesWordsWithTheMenuUpGoBehindIt() {
        menuUp(true)

        presenter.display(words, true)

        verify(auxBar).updateHiddenSuggestionStrip(words, false)
        verify(auxBar, never()).showSuggestionStrip(Mockito.any(SuggestedWords::class.java), Mockito.anyBoolean())
        verify(uiCoordinator, never()).hideUnifiedInputBoard()
    }

    /** With a board open, a swipe's words do not close it or the menu. */
    @Test
    fun aSwipesWordsWithABoardOpenLeaveTheBoardAndTheMenu() {
        menuUp(true)
        Mockito.`when`(ime.isUnifiedInputBoardShowing()).thenReturn(true)

        presenter.display(words, true)

        verify(uiCoordinator, never()).hideUnifiedInputBoard()
        verify(auxBar).updateHiddenSuggestionStrip(words, false)
    }
}
