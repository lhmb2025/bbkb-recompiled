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
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager
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
 * "Show the suggestion bar" (Physical keyboard settings). The one predicate,
 * [SettingsValues.isPkbSuggestionBarHidden], across the device shapes it must and must not apply
 * to; and what it switches off: the effective unified-input-menu setting in both of its readers,
 * and the Latin strip in [InputViewCoordinator], while a Chinese or Japanese candidate strip keeps
 * working. The aux bar's own backstop is covered in `AuxBarManagerTransitionTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PkbSuggestionBarTest {

    private lateinit var context: Context
    private lateinit var auxBar: AuxBarManager
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
        coordinator = InputViewCoordinator(Mockito.mock(BlackBerryIME::class.java))
        coordinator.setAuxBarManager(auxBar)
        // initialize() would also resolve the views through the IME; these two are all the
        // predicates under test read.
        ReflectionHelpers.setField(coordinator, "settingsManager", SettingsManager.getInstance())
        ReflectionHelpers.setField(coordinator, "subtypeManager", SubtypeManager.getInstance())
    }

    @After
    fun tearDown() {
        // As SettingsValuesSnapshotTest: SettingsManager is a process singleton whose preference
        // listener would otherwise outlive this class.
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

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun prefs() = PrefsManager.getPrefs(context)

    private fun pkbDevice() = DeviceProfile.installForTest(
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB, true, false, true, "qwerty", "4row"),
    )

    private fun touchOnlyDevice() = DeviceProfile.installForTest(
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.VKB, false, false, false, "qwerty", "none"),
    )

    /** Settings for a plain text field; [showBar] null leaves the preference untouched. */
    private fun load(showBar: Boolean?): SettingsValues {
        if (showBar != null) {
            prefs().edit().putBoolean(SettingsManager.PREF_PKB_SHOW_SUGGESTION_BAR, showBar).commit()
        }
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US, EditorCapabilities(info, false, context.packageName, Locale.US, false))
        return manager.settingsValues
    }

    /** The keyboard the user has active, by its subtype locale ("zh_CN", "ja_JP", ...). */
    private fun subtype(locale: String) {
        val subtype = InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeLocale(locale).build()
        ReflectionHelpers.setField(SubtypeManager.getInstance(), "currentSubtype", subtype)
    }

    private fun preShowSuggestionStripForPkb() =
        ReflectionHelpers.callInstanceMethod<Any?>(coordinator, "preShowSuggestionStripForPkb")

    // ── the predicate ───────────────────────────────────────────────────────

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
     * Landscape, a per-app or debug forced on-screen keyboard and an open input board all reach
     * this through refreshOnScreenKeyboardShowing(), which changes without a settings reload: the
     * same SettingsValues has to follow it.
     */
    @Test
    fun anOnScreenKeyboardBringsTheBarBackWithoutAReload() {
        val sv = load(showBar = false)

        DeviceProfile.setOnScreenKeyboardShowing(true)
        assertFalse(sv.isPkbSuggestionBarHidden)
        assertTrue(sv.isUimEnabled)
        assertTrue(SettingsManager.isUimEnabled(context))

        DeviceProfile.setOnScreenKeyboardShowing(false)
        assertTrue(sv.isPkbSuggestionBarHidden)
        assertFalse(sv.isUimEnabled)
    }

    /**
     * An input board opened over the physical keys (clipboard, cursor control, number pad)
     * counts as the on-screen keyboard for everything else, but not here. Otherwise the menu
     * would come up above the board, and the board shortcut's second press would take the menu's
     * route instead of the board's own toggle and fail to close it.
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
        assertTrue(sv.isUimEnabled)
    }

    @Test
    fun aTouchOnlyDeviceIsNeverHidden() {
        touchOnlyDevice()
        val sv = load(showBar = false)

        assertFalse(sv.isPkbSuggestionBarHidden)
        assertTrue(sv.isUimEnabled)
        assertTrue(SettingsManager.isUimEnabled(context))
    }

    @Test
    fun theMenuStaysOffWhenItsOwnSettingIsOffWhateverTheBarSetting() {
        prefs().edit().putBoolean("pref_uim_enabled", false).commit()
        val sv = load(showBar = true)

        assertFalse(sv.isPkbSuggestionBarHidden)
        assertFalse(sv.isUimEnabled)
        assertFalse(SettingsManager.isUimEnabled(context))
    }

    // ── InputViewCoordinator ────────────────────────────────────────────────

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
        val sv = load(showBar = false)

        for (locale in listOf("zh_CN", "ja_JP")) {
            subtype(locale)
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

    @Test
    fun startingInputWithTheBarHiddenStillPreShowsAChineseStrip() {
        load(showBar = false)
        subtype("zh_CN")

        preShowSuggestionStripForPkb()

        verify(auxBar).hideSuggestionBar()
        verify(auxBar).showSuggestionStrip(SuggestedWords.EMPTY)
    }
}
