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
 * Pins how [InputViewCoordinator] applies the bar's remembered choice: what the bar does when the
 * last board closes, what the menu's keyboard key does, and which events choose or end the menu.
 * The choice's own table is `AuxBarChoiceTest`.
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

    /** ...and so does one the show-menu key raised (the key chooses the menu too). */
    @Test
    fun aMenuTheKeyRaisedStaysUp() {
        field()
        Mockito.`when`(uim.isShowing).thenReturn(false)
        Mockito.`when`(uim.showMenuForKey()).thenReturn(true)
        coordinator.toggleInputMenu()
        Mockito.`when`(uim.isShowing).thenReturn(true)
        assertTrue(coordinator.barChoice.isMenuChosen)

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

    /**
     * Where the field has a strip, the key brings it back with its words and requests nothing
     * (a fresh request often came back empty and blanked the strip).
     */
    @Test
    fun theKeyboardKeyUncoversTheStripWithItsWords() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        coordinator.putMenuAway()

        verify(uim).lowerMenuOntoStrip()
        verify(ime, never()).restoreSuggestionStrip(Mockito.anyBoolean())
        assertFalse(coordinator.barChoice.isMenuChosen)
    }

    /** Where the field's bar is the menu, the key restores the strip with a fresh request. */
    @Test
    fun theKeyboardKeyInAMenuFieldDoesWhatItDidBefore() {
        field(predictions = false)

        coordinator.putMenuAway()

        verify(uim, never()).lowerMenuOntoStrip()
        verify(ime).restoreSuggestionStrip(true)
    }

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

    /** The user raised the menu and types: the strip comes back in its place, with its words. */
    @Test
    fun typingOverARaisedMenuBringsTheStripBack() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        coordinator.onUserTyped(true)

        verify(uim).lowerMenuOntoStrip()
        assertFalse(coordinator.barChoice.isMenuChosen)
    }

    /** Ordinary typing with nothing chosen touches nothing (the common case, every keystroke). */
    @Test
    fun typingWithNothingChosenDoesNothing() {
        field()

        coordinator.onUserTyped(true)

        verify(uim, never()).lowerMenuOntoStrip()
        verify(uim, never()).hide()
    }

    /** A board is open: it keeps the menu, but the typing puts the menu away for its close. */
    @Test
    fun aKeypadLetterWithABoardOpenPutsTheMenuAwayForLater() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()
        Mockito.`when`(uim.isPanelBoardShowing).thenReturn(true)

        coordinator.onUserTyped(true)

        verify(uim, never()).lowerMenuOntoStrip()
        assertFalse("the board's close will uncover the strip", coordinator.barChoice.isMenuChosen)
    }

    /** On-screen input while a board covers the on-screen keys comes from the board: not typing. */
    @Test
    fun onScreenInputWithABoardOpenIsTheBoardsNotTyping() {
        field()
        coordinator.showUnifiedInputMenuFromSuggestionStrip()
        Mockito.`when`(uim.isPanelBoardShowing).thenReturn(true)

        coordinator.onUserTyped(false)

        assertTrue(coordinator.barChoice.isMenuChosen)
    }

    /** Typing ends the show-menu key's menu too, and its override with it. */
    @Test
    fun typingOverTheKeysMenuEndsItAndItsOverride() {
        field()
        Mockito.`when`(uim.isShowing).thenReturn(false)
        Mockito.`when`(uim.showMenuForKey()).thenReturn(true)
        coordinator.toggleInputMenu()
        Mockito.`when`(uim.isShowing).thenReturn(true)

        coordinator.onUserTyped(true)

        verify(uim).lowerMenuOntoStrip()
        assertFalse(SettingsValues.isInputMenuRequestedByKey())
    }

    /** Where the field's own bar is the menu, typing leaves it. */
    @Test
    fun typingInAMenuFieldKeepsTheMenu() {
        field(predictions = false)
        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        coordinator.onUserTyped(true)

        verify(uim, never()).lowerMenuOntoStrip()
        verify(uim, never()).hide()
    }

    /** A menu raised in the last field (choice reset by the field start) is hidden at the field start. */
    @Test
    fun aMenuLeftFromTheLastFieldGoesAtTheFieldStart() {
        field()

        coordinator.onStartInputView()

        verify(uim).hide()
    }

    /** ...but a board still open keeps it. */
    @Test
    fun aMenuOverAnOpenBoardStaysAtTheFieldStart() {
        field()
        Mockito.`when`(uim.isPanelBoardShowing).thenReturn(true)

        coordinator.onStartInputView()

        verify(uim, never()).hide()
    }

    /**
     * Exiting a temporary bar (cursor mode, accent bar, language chips) restores "the strip or the
     * menu" through shouldShowUim(); a menu the user chose counts.
     */
    @Test
    fun aChosenMenuIsWhatTheBarGoesBackTo() {
        field()
        assertFalse(coordinator.shouldShowUim())
        coordinator.showUnifiedInputMenuFromSuggestionStrip()

        assertTrue(coordinator.shouldShowUim())
        Mockito.`when`(uim.isShowing).thenReturn(false)
        coordinator.showSuggestionStripOrUim()

        verify(uim).show(false)
    }
}
