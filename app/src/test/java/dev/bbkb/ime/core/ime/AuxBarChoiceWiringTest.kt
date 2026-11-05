package dev.bbkb.ime.core.ime

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import dev.bbkb.ime.keyboard.state.AuxBarChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * The bar's remembered choice as [InputViewCoordinator] applies it (owner decision 2026-11-05):
 * what the bar does when the last board closes, what the menu's keyboard key does, and which
 * events choose the menu. The choice's own table is `AuxBarChoiceTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuxBarChoiceWiringTest {

    private lateinit var context: Context
    private lateinit var ime: BlackBerryIME
    private lateinit var coordinator: InputViewCoordinator
    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var boards: UnifiedBoardCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.setOnScreenKeyboardShowing(false)
        DeviceProfile.setForceVkbMode(false)
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB, true, false, true, "qwerty", "4row"),
        )
        SettingsManager.initialize(context)
        val subtype = InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeLocale("en_US").build()
        ReflectionHelpers.setField(SubtypeManager.getInstance(), "currentSubtype", subtype)

        ime = Mockito.mock(BlackBerryIME::class.java)
        coordinator = InputViewCoordinator(ime)
        coordinator.setAuxBarManager(Mockito.mock(AuxBarManager::class.java))
        ReflectionHelpers.setField(coordinator, "settingsManager", SettingsManager.getInstance())
        ReflectionHelpers.setField(coordinator, "subtypeManager", SubtypeManager.getInstance())

        uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        boards = UnifiedBoardCoordinator(Mockito.mock(UnifiedBoardCoordinator.BoardHost::class.java))
        Mockito.`when`(uim.boardCoordinator).thenReturn(boards)
        Mockito.`when`(uim.isShowing).thenReturn(true)
        val switcher = Mockito.mock(KeyboardSwitcher::class.java)
        Mockito.`when`(switcher.unifiedInputBoardManager).thenReturn(uim)
        ReflectionHelpers.setField(coordinator, "keyboardSwitcher", switcher)
    }

    @After
    fun tearDown() {
        SettingsValues.setInputMenuRequestedByKey(false)
        val manager = SettingsManager.getInstance()
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(manager, null)
        }
        prefs().edit().clear().commit()
        ReflectionHelpers.setField(SubtypeManager.getInstance(), "currentSubtype", null)
        DeviceProfile.setOnScreenKeyboardShowing(false)
        DeviceProfile.setForceVkbMode(false)
        DeviceProfile.initialize(null)
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    /** A plain text field; [predictions] off with the menu on makes the menu the field's bar. */
    private fun field(predictions: Boolean = true) {
        prefs().edit().putBoolean("show_predictions", predictions).putBoolean("pref_uim_enabled", true).commit()
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(info, false, context.packageName, Locale.US, false),
        )
    }

    // ── the field's own bar ─────────────────────────────────────────────────

    @Test
    fun aTextFieldWithPredictionsHasTheStripAsItsBar() {
        field()
        assertEquals(AuxBarChoice.FieldBar.STRIP, coordinator.fieldBar())
    }

    @Test
    fun aFieldWithNoStripAndTheMenuOnHasTheMenuAsItsBar() {
        field(predictions = false)
        assertEquals(AuxBarChoice.FieldBar.MENU, coordinator.fieldBar())
    }

    // ── the last board closed ───────────────────────────────────────────────

    /** The menu came up with the board; the board is gone, so the strip comes back. */
    @Test
    fun theMenuABoardBroughtComesDownOntoTheStrip() {
        field()

        coordinator.uncoverAfterBoardsClosed()

        verify(uim).lowerMenuOntoStrip()
    }

    /** A menu the user raised (the hamburger) before opening the board stays up. */
    @Test
    fun aMenuTheUserRaisedStaysUp() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()
        assertTrue(coordinator.barChoice.isMenuChosen)

        coordinator.uncoverAfterBoardsClosed()

        verify(uim, never()).lowerMenuOntoStrip()
        verify(uim, never()).hide()
    }

    /** ...and so does one the show-menu key raised. */
    @Test
    fun aMenuTheKeyRaisedStaysUp() {
        field()
        SettingsValues.setInputMenuRequestedByKey(true)

        coordinator.uncoverAfterBoardsClosed()

        verify(uim, never()).lowerMenuOntoStrip()
    }

    /** A field whose own bar is the menu keeps it. */
    @Test
    fun aFieldWhoseBarIsTheMenuKeepsIt() {
        field(predictions = false)

        coordinator.uncoverAfterBoardsClosed()

        verify(uim, never()).lowerMenuOntoStrip()
        verify(uim, never()).hide()
    }

    /** A switch from one board to another: by the time the uncover runs, the next board is open. */
    @Test
    fun nothingHappensWhileABoardIsOpenAgain() {
        field()
        boards.notifyBoardOpened(-46)

        coordinator.uncoverAfterBoardsClosed()

        verify(uim, never()).lowerMenuOntoStrip()
    }

    @Test
    fun nothingHappensWhenTheMenuIsAlreadyDown() {
        field()
        Mockito.`when`(uim.isShowing).thenReturn(false)

        coordinator.uncoverAfterBoardsClosed()

        verify(uim, never()).lowerMenuOntoStrip()
        verify(uim, never()).hide()
    }

    // ── the menu's keyboard key ─────────────────────────────────────────────

    /**
     * Where the field has a strip, the key brings it back with the words it holds, and asks for
     * nothing: the request it used to make (`restoreSuggestionStrip(true, true)`) often came back
     * empty and blanked the strip.
     */
    @Test
    fun theKeyboardKeyUncoversTheStripWithItsWords() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        coordinator.putMenuAway()

        verify(uim).lowerMenuOntoStrip()
        verify(ime, never()).restoreSuggestionStrip(Mockito.anyBoolean(), Mockito.anyBoolean())
        assertFalse(coordinator.barChoice.isMenuChosen)
    }

    /** Where the field's bar is the menu, the key does what it did before. */
    @Test
    fun theKeyboardKeyInAMenuFieldDoesWhatItDidBefore() {
        field(predictions = false)

        coordinator.putMenuAway()

        verify(uim, never()).lowerMenuOntoStrip()
        verify(ime).restoreSuggestionStrip(true, true)
    }

    // ── what chooses the menu, and what ends it ─────────────────────────────

    @Test
    fun theHamburgerChoosesTheMenuOnlyWhenTheMenuCameUp() {
        field()
        Mockito.`when`(uim.isShowing).thenReturn(false)

        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        assertFalse("the menu did not come up", coordinator.barChoice.isMenuChosen)
    }

    @Test
    fun takingTheMenuDownEndsTheChoice() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        coordinator.onMenuTakenDown()

        assertFalse(coordinator.barChoice.isMenuChosen)
    }
}
