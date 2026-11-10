package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.device.config.resolver.KeyRoleTestSupport
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.InputMethodHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A hardware ENTER press reaches the app whole or not at all.
 *
 * On a physical keyboard the IME answers Enter in a multi-line field itself (it commits the
 * newline), consuming the key-down, while the key-up was still delegated by the Enter rule in
 * [KeyEventProcessor.shouldDelegateEnterKeyUp]: the app saw a release with no press. The
 * editor-action branch passes the key-down through, and there the key-up must follow it.
 * What "the app sees" is what reaches [BlackBerryIME.superOnKeyDown] / [BlackBerryIME.superOnKeyUp].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EnterKeyPairingTest {

    private lateinit var context: Context
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var helperStatic: MockedStatic<InputMethodHelper>

    private lateinit var ime: BlackBerryIME
    private lateinit var processor: KeyEventProcessor
    private lateinit var enterContext: InputEventContext

    private var appDowns = 0
    private var appUps = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
        DeviceProfile.initialize(context)
        DeviceProfile.installForTest(key2Shape())

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)

        KeyRoleTestSupport.everyKeyIs(null)

        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypeManager = mock(SubtypeManager::class.java)
        `when`(subtypeManager.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }
            .thenReturn(subtypeManager)

        helperStatic = mockStatic(InputMethodHelper::class.java)
        helperStatic.`when`<InputMethodHelper> { InputMethodHelper.getInstance() }
            .thenReturn(mock(InputMethodHelper::class.java))

        ime = mock(BlackBerryIME::class.java, RETURNS_DEEP_STUBS)
        `when`(ime.applicationContext).thenReturn(context)
        `when`(ime.packageName).thenReturn(context.packageName)
        `when`(ime.isInputActive).thenReturn(true)
        `when`(ime.isInputViewShown).thenReturn(true)
        `when`(ime.isAltEnterPressed).thenReturn(false)
        `when`(ime.auxBarManager).thenReturn(null)
        `when`(ime.vkbGestureListener).thenReturn(null)
        `when`(ime.needsKeyboardReload).thenReturn(false)
        `when`(ime.remapKeyEvent(anyInt(), anyArg())).thenAnswer { it.arguments[1] as KeyEvent }
        `when`(ime.getControlMode().handleHardKeyDown(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getControlMode().handleHardKeyUp(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder()).thenReturn(0)
        `when`(ime.getPhysicalKeyboardStateTracker().getModifierState(anyArg<KeyEvent>()))
            .thenReturn(ModifierState.none())
        `when`(ime.getInputLogic().isTrackedKeyRepeat(anyArg())).thenReturn(false)
        `when`(ime.getInputLogic().isUntrackedKeyRepeat(anyArg())).thenReturn(false)
        `when`(ime.getInputLogic().isKeyTracked(anyArg())).thenReturn(true)
        `when`(ime.fccController!!.isShowing).thenReturn(false)
        // The converter turns ENTER into the '\n' key press the typing pipeline handles.
        `when`(ime.getOrCreateKeyEventConverter(anyInt()).convertKeyEvent(anyArg(), anyInt(), anyInt()))
            .thenAnswer {
                InputEvent.createHardwareKeyPress('\n'.code, KeyEvent.KEYCODE_ENTER, null, false, 0L)
            }
        enterContext = mock(InputEventContext::class.java)
        `when`(ime.getInputLogic().processInputEvent(anyArg(), anyArg(), anyArg(), anyArg(), anyInt(), anyArg()))
            .thenReturn(enterContext)
        // The pass-through branch retires the composing word through these two fields, which a
        // mocked InputLogic leaves null.
        val inputLogic = ime.getInputLogic()
        for ((name, type) in listOf(
            "mRichInputConnection" to dev.bbkb.ime.core.textinput.connection.RichInputConnection::class.java,
            "mComposingTracker" to dev.bbkb.ime.core.textinput.composing.ComposingTextTracker::class.java,
        )) {
            dev.bbkb.ime.core.textinput.InputLogic::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(inputLogic, mock(type))
        }
        `when`(ime.superOnKeyDown(anyInt(), anyArg())).thenAnswer { appDowns++; false }
        `when`(ime.superOnKeyUp(anyInt(), anyArg())).thenAnswer { appUps++; false }

        processor = KeyEventProcessor(ime)
    }

    @After
    fun tearDown() {
        classifierStatic.close()
        DeviceInputResolver.resetConfig()
        subtypeStatic.close()
        helperStatic.close()
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    @Test
    fun `an Enter the IME answers with a newline reaches the app as neither half`() {
        `when`(enterContext.hasPendingEditorAction()).thenReturn(false)

        pressEnter()

        assertEquals("key-downs the app saw", 0, appDowns)
        assertEquals("key-ups the app saw (an orphan release)", 0, appUps)
    }

    @Test
    fun `an Enter passed to the app reaches it as a down and up pair`() {
        `when`(enterContext.hasPendingEditorAction()).thenReturn(true)

        pressEnter()

        assertEquals("key-downs the app saw", 1, appDowns)
        assertEquals("key-ups the app saw", 1, appUps)
    }

    @Test
    fun `a consumed Enter does not swallow the release of the next, passed-through one`() {
        `when`(enterContext.hasPendingEditorAction()).thenReturn(false)
        pressEnter()
        `when`(enterContext.hasPendingEditorAction()).thenReturn(true)
        pressEnter()

        assertEquals(1, appDowns)
        assertEquals(1, appUps)
    }

    private fun pressEnter() {
        processor.onKeyDownInternal(KeyEvent.KEYCODE_ENTER, key(KeyEvent.ACTION_DOWN))
        processor.onKeyUpInternal(KeyEvent.KEYCODE_ENTER, key(KeyEvent.ACTION_UP))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun key(action: Int): KeyEvent =
        KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_ENTER, 0, /* metaState */ 0, 0, 28)

    private fun key2Shape(): DeviceCapabilities = DeviceCapabilities.forShape(
        DeviceCapabilities.DetectedDeviceType.PKB,
        /* hasPhysicalKeyboard= */ true,
        /* hasTouchKeypad= */ true,
        /* isBlackBerryDevice= */ true,
        "qwerty",
        "4row"
    )
}
