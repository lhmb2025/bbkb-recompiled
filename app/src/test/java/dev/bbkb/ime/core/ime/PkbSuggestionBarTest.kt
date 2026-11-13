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
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import org.junit.After
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
 * Pins [SettingsValues.isPkbSuggestionBarHidden] across device shapes, and what it switches off:
 * the effective unified-input-menu setting in both readers and the Latin strip in
 * [InputViewCoordinator], while a CJK candidate strip keeps working. The aux bar's backstop is
 * in `AuxBarManagerTransitionTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PkbSuggestionBarTest {

    private lateinit var context: Context
    private lateinit var auxBar: AuxBarManager
    private lateinit var ime: BlackBerryIME
    private lateinit var coordinator: InputViewCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.setOnScreenKeyboardShowing(false)
        DeviceProfile.setForceVkbMode(false)
        pkbDevice()
        SettingsManager.initialize(context)
        subtype("en_US")

        auxBar = Mockito.mock(AuxBarManager::class.java)
        ime = Mockito.mock(BlackBerryIME::class.java)
        coordinator = InputViewCoordinator(ime)
        coordinator.setAuxBarManager(auxBar)
        // Skips initialize(); these two fields are all the predicates under test read.
        ReflectionHelpers.setField(coordinator, "settingsManager", SettingsManager.getInstance())
        ReflectionHelpers.setField(coordinator, "subtypeManager", SubtypeManager.getInstance())
    }

    @After
    fun tearDown() {
        SettingsValues.setInputMenuRequestedByKey(false)
        // SettingsManager is a process singleton whose preference listener would outlive this class.
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

    private fun pkbDevice() = DeviceProfile.installForTest(
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB, true, false, true, "qwerty", "4row"),
    )

    private fun touchOnlyDevice() = DeviceProfile.installForTest(
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.VKB, false, false, false, "qwerty", "none"),
    )

    /**
     * Settings for a plain text field. The PKB bar hides when "Show suggestions" and the unified
     * input menu are both off: [showBar] false turns both off, true turns suggestions on and leaves
     * the menu setting alone, null leaves both untouched.
     */
    private fun load(showBar: Boolean?, editorLocale: Locale = Locale.US): SettingsValues {
        if (showBar == false) {
            prefs().edit().putBoolean("show_predictions", false).putBoolean("pref_uim_enabled", false).commit()
        } else if (showBar == true) {
            prefs().edit().putBoolean("show_predictions", true).commit()
        }
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US, EditorCapabilities(info, false, context.packageName, editorLocale, false))
        return manager.settingsValues
    }

    /** The keyboard the user has active, by its subtype locale ("zh_CN", "ja_JP", ...). */
    private fun subtype(locale: String) {
        val subtype = InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeLocale(locale).build()
        ReflectionHelpers.setField(SubtypeManager.getInstance(), "currentSubtype", subtype)
    }

    private fun preShowSuggestionStripForPkb() =
        ReflectionHelpers.callInstanceMethod<Any?>(coordinator, "preShowSuggestionStripForPkb")

    /** The menu manager the coordinator reaches through the keyboard switcher, its bar up or not. */
    private fun boardManager(showing: Boolean): UnifiedInputBoardManager {
        val uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        Mockito.`when`(uim.isShowing).thenReturn(showing)
        val switcher = Mockito.mock(KeyboardSwitcher::class.java)
        Mockito.`when`(switcher.unifiedInputBoardManager).thenReturn(uim)
        ReflectionHelpers.setField(coordinator, "keyboardSwitcher", switcher)
        return uim
    }

    /** An active editor, and the handler a refill of the strip is posted on. */
    private fun suggestionUpdates(): UIUpdateHandler {
        val handler = Mockito.mock(UIUpdateHandler::class.java)
        Mockito.`when`(ime.isInputActive).thenReturn(true)
        Mockito.`when`(ime.getUiUpdateHandler()).thenReturn(handler)
        return handler
    }

    @Test
    fun theShippedDefaultLeavesTheBarAndTheMenuAlone() {
        val sv = load(showBar = null)

        assertFalse(sv.isPkbSuggestionBarHidden)
        assertTrue(sv.isUimEnabled)
        assertTrue(SettingsManager.isUimEnabled(context))
    }

    @Test
    fun offOnAPhysicalKeyboardWithNoOnScreenKeyboardHidesTheBarAndTheMenu() {
        val sv = load(showBar = false)

        assertTrue(sv.isPkbSuggestionBarHidden)
        assertFalse(sv.isUimEnabled)
        // The UIM manager's own gate reads the preferences, not SettingsValues.
        assertFalse(SettingsManager.isUimEnabled(context))
    }

    /**
     * Landscape, a forced on-screen keyboard and an open input board reach this through
     * refreshOnScreenKeyboardShowing() without a settings reload; the same SettingsValues must follow.
     */
    @Test
    fun anOnScreenKeyboardBringsTheBarBackWithoutAReload() {
        val sv = load(showBar = false)

        DeviceProfile.setOnScreenKeyboardShowing(true)
        assertFalse(sv.isPkbSuggestionBarHidden)
        // The menu setting itself is off, so the menu stays off; only the bar's own hiding lifts.
        assertFalse(sv.isUimEnabled)

        DeviceProfile.setOnScreenKeyboardShowing(false)
        assertTrue(sv.isPkbSuggestionBarHidden)
        assertFalse(sv.isUimEnabled)
    }

    /**
     * An input board over the physical keys counts as the on-screen keyboard elsewhere but not
     * here; otherwise the menu would come up above the board and the board shortcut's second press
     * would take the menu's route and fail to close it.
     */
    @Test
    fun anInputBoardOpenOverThePhysicalKeysKeepsTheBarHidden() {
        val sv = load(showBar = false)

        DeviceProfile.setOnScreenKeyboardShowing(true, false)

        assertTrue(DeviceProfile.isOnScreenKeyboardVisible())
        assertTrue(sv.isPkbSuggestionBarHidden)
        assertFalse(sv.isUimEnabled)
        assertFalse(SettingsManager.isUimEnabled(context))
        assertFalse(coordinator.shouldShowSuggestionStrip(sv, true, null))
    }

    @Test
    fun forcedTouchscreenModeIsNotHidden() {
        val sv = load(showBar = false)

        DeviceProfile.setForceVkbMode(true)

        assertFalse(sv.isPkbSuggestionBarHidden)
    }

    @Test
    fun aTouchOnlyDeviceIsNeverHidden() {
        touchOnlyDevice()
        val sv = load(showBar = false)

        assertFalse(sv.isPkbSuggestionBarHidden)
    }

    @Test
    fun theMenuStaysOffWhenItsOwnSettingIsOffWhateverTheBarSetting() {
        prefs().edit().putBoolean("pref_uim_enabled", false).commit()
        val sv = load(showBar = true)

        assertFalse(sv.isPkbSuggestionBarHidden)
        assertFalse(sv.isUimEnabled)
        assertFalse(SettingsManager.isUimEnabled(context))
    }

    @Test
    fun withTheBarShownATextFieldGetsTheLatinStrip() {
        // The control for the next test: the fixture alone does not refuse the strip.
        val sv = load(showBar = true)

        assertTrue(coordinator.shouldShowSuggestionStrip(sv, false, null))
        assertTrue(coordinator.shouldShowLatinSuggestionStrip(sv, false, null))
        assertTrue(coordinator.isUimEnabled())
    }

    @Test
    fun withTheBarHiddenNeitherTheLatinStripNorTheMenuShows() {
        val sv = load(showBar = false)

        assertFalse(coordinator.shouldShowSuggestionStrip(sv, false, null))
        assertFalse(coordinator.shouldShowLatinSuggestionStrip(sv, false, null))
        assertFalse(coordinator.isUimEnabled())
        // Without the isUimEnabled() half, a refused strip would make this true and put the menu up.
        assertFalse(coordinator.shouldShowUim())
    }

    @Test
    fun withTheBarHiddenAChineseOrJapaneseKeyboardKeepsItsCandidateStrip() {
        // A CJK keyboard still needs its candidate strip, which ignores "Show suggestions".
        for (locale in listOf("zh_CN", "ja_JP")) {
            subtype(locale)
            val sv = load(showBar = false, editorLocale = Locale.forLanguageTag(locale.replace('_', '-')))
            assertTrue(locale, coordinator.shouldShowSuggestionStrip(sv, false, null))
            assertTrue(locale, coordinator.shouldShowCjkSuggestionStrip(sv, false, null))
            assertFalse(locale, coordinator.isUimEnabled())
        }
    }

    @Test
    fun startingInputWithTheBarShownPreShowsTheEmptyStrip() {
        load(showBar = true)

        preShowSuggestionStripForPkb()

        verify(auxBar).showSuggestionStrip(SuggestedWords.EMPTY)
        verify(auxBar, never()).hideSuggestionBar()
    }

    @Test
    fun startingInputWithTheBarHiddenTakesTheEmptyBarDownAndPreShowsNothing() {
        load(showBar = false)

        preShowSuggestionStripForPkb()

        verify(auxBar).hideSuggestionBar()
        verify(auxBar, never()).showSuggestionStrip(Mockito.any<SuggestedWords>())
    }

    /**
     * The key raises the menu over a hidden bar: both isUimEnabled readers and shouldShowUim() say
     * on, while the bar setting still reads hidden and keeps the Latin strip away.
     */
    @Test
    fun theKeysMenuCountsAsOnWithTheBarHidden() {
        val sv = load(showBar = false)

        SettingsValues.setInputMenuRequestedByKey(true)

        assertTrue(sv.isPkbSuggestionBarHidden)
        assertTrue(sv.isUimEnabled)
        assertTrue(SettingsManager.isUimEnabled(context))
        assertTrue(coordinator.isUimEnabled())
        assertTrue(coordinator.shouldShowUim())
        assertFalse(coordinator.shouldShowSuggestionStrip(sv, false, null))
        // Never written to the preferences: clearing it gives the settings back.
        assertFalse(prefs().getBoolean("show_predictions", true))
        assertFalse(prefs().getBoolean("pref_uim_enabled", true))

        SettingsValues.setInputMenuRequestedByKey(false)
        assertFalse(sv.isUimEnabled)
        assertFalse(SettingsManager.isUimEnabled(context))
        assertFalse(coordinator.shouldShowUim())
    }

    @Test
    fun theKeysMenuCountsAsOnWithTheMenuSettingOff() {
        prefs().edit().putBoolean("pref_uim_enabled", false).commit()
        val sv = load(showBar = true)

        SettingsValues.setInputMenuRequestedByKey(true)

        assertTrue(sv.isUimEnabled)
        assertTrue(SettingsManager.isUimEnabled(context))
        // The menu, not the strip, is what the bar shows until it is put away.
        assertTrue(coordinator.shouldShowUim())
        assertFalse(prefs().getBoolean("pref_uim_enabled", true))
    }

    /**
     * The window shown by the key press that raised the menu starts the input view afterwards; the
     * pre-show must not take the menu down (bar hidden) or cover it with an empty strip (bar shown).
     */
    @Test
    fun startingTheViewWithTheKeysMenuUpLeavesTheBarToIt() {
        for (showBar in listOf(false, true)) {
            Mockito.clearInvocations(auxBar)
            load(showBar)
            SettingsValues.setInputMenuRequestedByKey(true)

            preShowSuggestionStripForPkb()

            verify(auxBar, never()).hideSuggestionBar()
            verify(auxBar, never()).showSuggestionStrip(Mockito.any<SuggestedWords>())
        }
    }

    @Test
    fun theKeyRaisesTheHiddenMenu() {
        load(showBar = false)
        val uim = boardManager(showing = false)
        Mockito.`when`(uim.showMenuForKey()).thenReturn(true)

        coordinator.toggleInputMenu()

        verify(uim).showMenuForKey()
        verify(uim, never()).hide()
        assertTrue(SettingsValues.isInputMenuRequestedByKey())
    }

    /** No bar view to show the menu in yet: the override does not linger with nothing up. */
    @Test
    fun aMenuThatCouldNotComeUpLeavesNoOverrideBehind() {
        load(showBar = false)
        val uim = boardManager(showing = false)
        Mockito.`when`(uim.showMenuForKey()).thenReturn(false)

        coordinator.toggleInputMenu()

        assertFalse(SettingsValues.isInputMenuRequestedByKey())
    }

    /** With the bar hidden, hiding the menu leaves nothing: no strip, nothing to fill it with. */
    @Test
    fun theKeyHidesTheMenuBackToTheHiddenBar() {
        load(showBar = false)
        val uim = boardManager(showing = true)
        val handler = suggestionUpdates()
        SettingsValues.setInputMenuRequestedByKey(true)

        coordinator.toggleInputMenu()

        verify(uim).hide()
        verify(uim, never()).showMenuForKey()
        assertFalse(SettingsValues.isInputMenuRequestedByKey())
        verify(auxBar).hideSuggestionBar()
        verify(auxBar, never()).showSuggestionStrip(Mockito.any<SuggestedWords>())
        verify(handler, never()).postUpdateShiftState(Mockito.anyBoolean(), Mockito.anyBoolean())
    }

    /**
     * With the bar shown, hiding the menu brings back the strip with the words kept current behind
     * it: any board under the menu closes, and nothing is emptied or requested again.
     */
    @Test
    fun theKeyHidesTheMenuBackToTheSuggestionStrip() {
        load(showBar = true)
        val uim = boardManager(showing = true)
        val handler = suggestionUpdates()
        Mockito.`when`(auxBar.isShowing).thenReturn(true)

        coordinator.toggleInputMenu()

        verify(uim).hideAllComponents()
        verify(uim).lowerMenuOntoStrip()
        verify(uim, never()).hide()
        verify(auxBar, never()).showSuggestionStrip(SuggestedWords.EMPTY)
        verify(handler, never()).postUpdateShiftState(Mockito.anyBoolean(), Mockito.anyBoolean())
        assertFalse(SettingsValues.isInputMenuRequestedByKey())
    }

    @Test
    fun startingInputWithTheBarHiddenStillPreShowsAChineseStrip() {
        load(showBar = false)
        subtype("zh_CN")

        preShowSuggestionStripForPkb()

        verify(auxBar).hideSuggestionBar()
        verify(auxBar).showSuggestionStrip(SuggestedWords.EMPTY)
    }
}
