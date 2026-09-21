package dev.bbkb.ime.keyboard.auxbar

import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Characterisation of [AuxBarManager]'s aux-bar half: the suggestion-strip gates, the autofill bar,
 * the three key bars (UIM, arrows, accents) with their keyboard caches, `hide`, `updateTheme`,
 * `clearKeyboardCache`, and the routing of the shared key view's key events by state.
 *
 * The PKB hold / accent-trigger state machine (`processKeyDown` and friends) is deliberately not
 * covered here — it is a separate feature.
 *
 * Runs against a real [AuxBarView] (inflated), a real manager, and the two static seams the
 * suggestion gates read: [DeviceProfile.installForTest] and `SettingsManager.settingsValues`.
 * `KeyboardSwitcher`'s `UnifiedInputBoardManager` is null here, so the UIM routing is exercised only
 * where that null matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuxBarManagerTransitionTest {

    private lateinit var context: ContextThemeWrapper
    private lateinit var manager: AuxBarManager
    private lateinit var bar: AuxBarView
    private lateinit var keyView: SimplifiedKeyboardView
    private lateinit var events: AuxBarManager.AuxBarEventListener
    private lateinit var arrows: ArrowBarController

    @Before
    fun setUp() {
        context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.KeyboardTheme_LXX)
        // UnifiedSuggestionView's MoreSuggestions builder reads subtype labels at inflation.
        dev.bbkb.ime.core.locale.RichInputMethodManager.init(context)
        manager = AuxBarManager(context)
        bar = AuxBarView(context)
        manager.setAuxBarView(bar)
        keyView = bar.sharedKeyView
        events = Mockito.mock(AuxBarManager.AuxBarEventListener::class.java)
        manager.setEventListener(events)
        arrows = Mockito.mock(ArrowBarController::class.java)
        manager.setArrowBarController(arrows)
        pkbDevice()
        suggestionsAllowed(null)
    }

    @After
    fun tearDown() {
        DeviceProfile.setOnScreenKeyboardShowing(false)
        DeviceProfile.initialize(null)
        suggestionsAllowed(null)
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun pkbDevice() = DeviceProfile.installForTest(
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB, true, true, true, "qwerty", "4row"),
    )

    private fun vkbDevice() = DeviceProfile.installForTest(
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.VKB, false, false, false, "qwerty", "none"),
    )

    /** null = SettingsManager has no values (the gate lets everything through). */
    private fun suggestionsAllowed(allowed: Boolean?) {
        val values: SettingsValues? = if (allowed == null) null else {
            val info = EditorInfo().apply {
                inputType = if (allowed) InputType.TYPE_CLASS_TEXT
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val caps = EditorCapabilities(info, false, context.packageName, Locale.US, false)
            assertEquals(allowed, caps.shouldShowSuggestions)
            Mockito.mock(SettingsValues::class.java).also {
                ReflectionHelpers.setField(SettingsValues::class.java, it, "editorCapabilities", caps)
            }
        }
        ReflectionHelpers.setField(SettingsManager.getInstance(), "settingsValues", values)
    }

    private fun words() = SuggestedWords(ArrayList(), false, false, 0)

    private fun keyboard(vararg labels: String): Keyboard =
        KeyboardBuilder.createFromLabels(context, labels.map { it as CharSequence }, 40)

    private fun currentWords(): SuggestedWords? =
        ReflectionHelpers.getField(bar.suggestionView, "currentSuggestions")

    private fun accentKeyCode(): Int = ReflectionHelpers.getField(manager, "currentAccentBarKeyCode")

    private fun keyListener(): SimplifiedKeyboardView.onKeyEventListener =
        ReflectionHelpers.getField(keyView, "keyEventListener")

    private fun showArrowBarWith(kb: Keyboard?) {
        Mockito.`when`(arrows.buildArrowKeyboard()).thenReturn(kb)
        manager.showArrowBar()
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    @Test
    fun stateChangesAreForwardedToTheEventListener() {
        manager.showSuggestionStrip(words(), false)
        manager.hide()

        val order = Mockito.inOrder(events)
        order.verify(events).onAuxBarStateChanged(AuxBarState.NONE, AuxBarState.LATIN_SUGGESTIONS)
        order.verify(events).onAuxBarStateChanged(AuxBarState.LATIN_SUGGESTIONS, AuxBarState.NONE)
    }

    @Test
    fun withoutAViewEverythingIsANoOpAndTheStateIsNone() {
        val bare = AuxBarManager(context)
        bare.setArrowBarController(arrows)

        bare.showSuggestionStrip(words())
        bare.showSuggestionStrip(words(), false)
        bare.showAutofillBar(emptyList(), 1, 1)
        bare.hideAutofillBar(true)
        bare.showUnifiedInputMenu(keyboard("a"))
        bare.showUnifiedInputMenu()
        bare.hideUnifiedInputMenu()
        bare.showArrowBar()
        bare.hideArrowBar()
        bare.showAccentBar(listOf("é"))
        bare.hideAccentBar()
        bare.hide()
        bare.updateTheme()

        assertEquals(AuxBarState.NONE, bare.currentState)
        assertEquals(false, bare.isShowing)
        assertNull(bare.auxBarView)
        assertNull(bare.displayedKeyView)
        verifyNoInteractions(arrows)
    }

    @Test
    fun theDisplayedKeyViewIsTheBarsSharedKeyView() {
        assertSame(keyView, manager.displayedKeyView)
        assertSame(bar, manager.auxBarView)
    }

    // ── showSuggestionStrip(words, isCJK) ───────────────────────────────────

    @Test
    fun twoArgStripShowsOnAPkbDeviceWithTheGivenMode() {
        manager.showSuggestionStrip(words(), true)
        assertEquals(AuxBarState.CJK_SUGGESTIONS, manager.currentState)

        manager.showSuggestionStrip(words(), false)
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
        assertTrue(manager.isShowing)
    }

    @Test
    fun twoArgStripDoesNothingOnAVkbDevice() {
        vkbDevice()
        manager.showSuggestionStrip(words(), false)
        assertEquals(AuxBarState.NONE, manager.currentState)
    }

    @Test
    fun twoArgStripIgnoresTheOnScreenKeyboardFlag() {
        // Its gate is isPkbDevice(), NOT isOnScreenKeyboardVisible(): a PKB device with the VKB up
        // still takes the strip through this overload.
        DeviceProfile.setOnScreenKeyboardShowing(true)
        manager.showSuggestionStrip(words(), false)
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
    }

    // ── showSuggestionStrip(words) ──────────────────────────────────────────

    @Test
    fun oneArgStripShowsWhenNoOnScreenKeyboardIsVisibleAndPicksModeFromLocale() {
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)

        manager.setCurrentLocale(Locale.CHINA)
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.CJK_SUGGESTIONS, manager.currentState)

        manager.setCurrentLocale(Locale.JAPAN)
        manager.hide()
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.CJK_SUGGESTIONS, manager.currentState)

        manager.setCurrentLocale(Locale.FRANCE)
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
    }

    @Test
    fun oneArgStripDoesNothingWhileTheOnScreenKeyboardIsShowing() {
        DeviceProfile.setOnScreenKeyboardShowing(true)
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.NONE, manager.currentState)
    }

    @Test
    fun oneArgStripDoesNothingOnAVkbDevice() {
        vkbDevice()
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.NONE, manager.currentState)
    }

    // ── the field-capability gate (both overloads) ──────────────────────────

    @Test
    fun aFieldThatAllowsSuggestionsLetsBothOverloadsThrough() {
        suggestionsAllowed(true)
        manager.showSuggestionStrip(words())
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
        manager.hide()
        manager.showSuggestionStrip(words(), true)
        assertEquals(AuxBarState.CJK_SUGGESTIONS, manager.currentState)
    }

    @Test
    fun aFieldThatForbidsSuggestionsHidesAShownLatinOrCjkStrip() {
        for (cjk in listOf(false, true)) {
            for (overload in 1..2) {
                suggestionsAllowed(null)
                manager.showSuggestionStrip(words(), cjk)
                suggestionsAllowed(false)

                if (overload == 1) manager.showSuggestionStrip(words()) else manager.showSuggestionStrip(words(), cjk)

                assertEquals("cjk=$cjk overload=$overload", AuxBarState.NONE, manager.currentState)
                assertEquals(View.GONE, bar.visibility)
            }
        }
    }

    @Test
    fun aFieldThatForbidsSuggestionsDoesNotShowOrClearFromNone() {
        suggestionsAllowed(false)
        manager.showSuggestionStrip(words())
        manager.showSuggestionStrip(words(), false)
        assertEquals(AuxBarState.NONE, manager.currentState)
        verify(events, never()).onAuxBarStateChanged(Mockito.any(), Mockito.any())
    }

    @Test
    fun aFieldThatForbidsSuggestionsLeavesAutofillAndKeyBarsUp() {
        for (overload in 1..2) {
            suggestionsAllowed(null)
            manager.showAutofillBar(emptyList(), 10, 10)
            suggestionsAllowed(false)
            if (overload == 1) manager.showSuggestionStrip(words()) else manager.showSuggestionStrip(words(), false)
            assertEquals(AuxBarState.AUTOFILL, manager.currentState)

            manager.showUnifiedInputMenu(keyboard("a"))
            if (overload == 1) manager.showSuggestionStrip(words()) else manager.showSuggestionStrip(words(), false)
            assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
        }
    }

    @Test
    fun theFieldGateRunsBeforeTheDeviceGate() {
        // A VKB device would return from the device gate without touching the bar; the field gate
        // comes first, so a forbidden field still takes a shown strip down there.
        manager.showSuggestionStrip(words(), false)
        vkbDevice()
        DeviceProfile.setOnScreenKeyboardShowing(true)
        suggestionsAllowed(false)

        manager.showSuggestionStrip(words())

        assertEquals(AuxBarState.NONE, manager.currentState)
    }

    @Test
    fun aShownStripReplacesAKeyBar() {
        manager.showUnifiedInputMenu(keyboard("a"))
        manager.showSuggestionStrip(words(), false)
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
    }

    // ── autofill ────────────────────────────────────────────────────────────

    @Test
    fun autofillBarShowsAndHidesOnlyFromAutofill() {
        manager.showAutofillBar(emptyList(), 10, 10)
        assertEquals(AuxBarState.AUTOFILL, manager.currentState)

        manager.hideAutofillBar(true)
        assertEquals(AuxBarState.NONE, manager.currentState)
        assertEquals(View.GONE, bar.visibility)

        manager.showAutofillBar(emptyList(), 10, 10)
        manager.hideAutofillBar(false)
        assertEquals(AuxBarState.NONE, manager.currentState)

        val w = words()
        manager.showSuggestionStrip(w, false)
        manager.hideAutofillBar(true)
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
        assertSame(w, currentWords())
    }

    @Test
    fun autofillChipsCoveredByTheArrowBarCanStillBeHiddenAfterItCloses() {
        // Dismissing a key bar over autofill restores AUTOFILL, so hideAutofillBar still applies.
        manager.showAutofillBar(emptyList(), 10, 10)
        showArrowBarWith(keyboard("←", "→"))
        manager.hideArrowBar()
        assertEquals(AuxBarState.AUTOFILL, manager.currentState)

        manager.hideAutofillBar(false)

        assertEquals(AuxBarState.NONE, manager.currentState)
        assertEquals(View.GONE, bar.visibility)
    }

    // ── unified input menu ──────────────────────────────────────────────────

    @Test
    fun showingTheUimWithAKeyboardShowsItAndCachesIt() {
        val kb = keyboard("a", "b")
        manager.showUnifiedInputMenu(kb)
        assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
        assertSame(kb, keyView.keyboard)

        manager.hide()
        manager.showUnifiedInputMenu()

        assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
        assertSame(kb, keyView.keyboard)
    }

    @Test
    fun anExplicitUimKeyboardReplacesTheCachedOne() {
        manager.showUnifiedInputMenu(keyboard("a"))
        val second = keyboard("b")
        manager.showUnifiedInputMenu(second)
        manager.hide()
        manager.showUnifiedInputMenu()
        assertSame(second, keyView.keyboard)
    }

    @Test
    fun clearKeyboardCacheDropsTheCachedUimKeyboard() {
        val kb = keyboard("a")
        manager.showUnifiedInputMenu(kb)
        manager.hide()
        manager.clearKeyboardCache()

        manager.showUnifiedInputMenu()

        // No UnifiedInputBoardManager here, so the no-arg form falls back to building layout 138
        // itself; whatever that yields, it is not the dropped keyboard.
        assertNotSame(kb, keyView.keyboard)
    }

    @Test
    fun updateThemeDropsTheCachedUimKeyboardAndRepaints() {
        val kb = keyboard("a")
        manager.showUnifiedInputMenu(kb)
        manager.hide()
        bar.setBackgroundColor(0x12345678)

        manager.updateTheme()
        manager.showUnifiedInputMenu()

        assertNotSame(kb, keyView.keyboard)
        assertEquals(KeyboardColorManager.backgroundColor, (bar.background as android.graphics.drawable.ColorDrawable).color)
    }

    @Test
    fun clearKeyboardCacheDoesNotRepaint() {
        bar.setBackgroundColor(0x12345678)
        manager.clearKeyboardCache()
        assertEquals(0x12345678, (bar.background as android.graphics.drawable.ColorDrawable).color)
    }

    @Test
    fun hideUnifiedInputMenuOnlyHidesTheUim() {
        manager.showUnifiedInputMenu(keyboard("a"))
        manager.hideUnifiedInputMenu()
        assertEquals(AuxBarState.NONE, manager.currentState)

        manager.showSuggestionStrip(words(), false)
        manager.hideUnifiedInputMenu()
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)

        showArrowBarWith(keyboard("x"))
        manager.hideUnifiedInputMenu()
        assertEquals(AuxBarState.ARROW_BAR, manager.currentState)
    }

    @Test
    fun hidingTheUimClearsTheSuggestionsItCovered() {
        manager.showSuggestionStrip(words(), false)
        manager.showUnifiedInputMenu(keyboard("a"))
        manager.hideUnifiedInputMenu()
        assertNull(currentWords())
        assertEquals(View.GONE, bar.visibility)
    }

    // ── arrow bar ───────────────────────────────────────────────────────────

    @Test
    fun theArrowBarIsBuiltOnceAndCached() {
        val kb = keyboard("a")
        showArrowBarWith(kb)
        assertEquals(AuxBarState.ARROW_BAR, manager.currentState)
        assertSame(kb, keyView.keyboard)

        manager.hide()
        manager.showArrowBar()

        assertSame(kb, keyView.keyboard)
        verify(arrows, times(1)).buildArrowKeyboard()
    }

    @Test
    fun clearKeyboardCacheAndUpdateThemeEachForceAnArrowRebuild() {
        showArrowBarWith(keyboard("a"))
        manager.clearKeyboardCache()
        val second = keyboard("b")
        showArrowBarWith(second)
        assertSame(second, keyView.keyboard)

        manager.updateTheme()
        val third = keyboard("c")
        showArrowBarWith(third)
        assertSame(third, keyView.keyboard)
        verify(arrows, times(3)).buildArrowKeyboard()
    }

    @Test
    fun aFailedArrowBuildShowsNothingAndIsRetriedNextTime() {
        manager.showSuggestionStrip(words(), false)
        showArrowBarWith(null)
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)

        val kb = keyboard("a")
        showArrowBarWith(kb)
        assertEquals(AuxBarState.ARROW_BAR, manager.currentState)
        verify(arrows, times(2)).buildArrowKeyboard()
    }

    @Test
    fun withoutAnArrowControllerTheArrowBarDoesNotShow() {
        manager.setArrowBarController(null)
        manager.showArrowBar()
        assertEquals(AuxBarState.NONE, manager.currentState)
    }

    @Test
    fun hideArrowBarRestoresTheSuggestionsItCoveredWithoutABlink() {
        val w = words()
        manager.showSuggestionStrip(w, true)
        showArrowBarWith(keyboard("a"))

        manager.hideArrowBar()

        assertEquals(AuxBarState.CJK_SUGGESTIONS, manager.currentState)
        assertSame(w, currentWords())
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(View.VISIBLE, bar.suggestionView.visibility)
        assertEquals(View.GONE, keyView.visibility)
    }

    @Test
    fun hideArrowBarIsANoOpInAnyOtherState() {
        manager.hideArrowBar()
        assertEquals(AuxBarState.NONE, manager.currentState)

        manager.showUnifiedInputMenu(keyboard("a"))
        manager.hideArrowBar()
        assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
    }

    // ── accent bar ──────────────────────────────────────────────────────────

    @Test
    fun theAccentBarIsBuiltFromTheLabelsEveryTime() {
        manager.showAccentBar(listOf("é", "è", "ê"))
        assertEquals(AuxBarState.ACCENT_BAR, manager.currentState)
        val first = keyView.keyboard
        assertEquals(listOf(0xE9, 0xE8, 0xEA), first.keys.map { it.code })

        manager.showAccentBar(listOf("é", "è", "ê"))
        assertNotSame(first, keyView.keyboard)
    }

    @Test
    fun anEmptyAccentListShowsNothing() {
        manager.showSuggestionStrip(words(), false)
        manager.showAccentBar(emptyList())
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
    }

    @Test
    fun theAccentBarIsNotCachedAcrossClearKeyboardCache() {
        manager.showUnifiedInputMenu(keyboard("u"))
        manager.showAccentBar(listOf("é"))
        manager.hide()
        // The UIM cache is untouched by accent use.
        manager.showUnifiedInputMenu()
        assertEquals("u", keyView.keyboard.keys.first().label)
    }

    @Test
    fun hideAccentBarHidesOnlyTheAccentBarButAlwaysForgetsTheTriggerKey() {
        ReflectionHelpers.setField(manager, "currentAccentBarKeyCode", 42)
        manager.showSuggestionStrip(words(), false)
        manager.hideAccentBar()
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
        assertEquals(-1, accentKeyCode())

        val words = words()
        manager.showSuggestionStrip(words, false)
        manager.showAccentBar(listOf("é"))
        ReflectionHelpers.setField(manager, "currentAccentBarKeyCode", 42)
        manager.hideAccentBar()
        // The bar only covered the strip: the strip is back, with the same words.
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
        assertEquals(-1, accentKeyCode())
        assertSame(words, currentWords())
    }

    @Test
    fun hidingTheAccentBarPutsBackTheUimItCovered() {
        manager.showUnifiedInputMenu(keyboard("a"))
        manager.showAccentBar(listOf("é"))
        assertEquals(AuxBarState.ACCENT_BAR, manager.currentState)
        manager.hideAccentBar()
        assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
    }

    @Test
    fun hidingAnAccentBarThatCoveredNothingShowsTheUimWhenTheEditorUsesIt() {
        Mockito.`when`(events.shouldShowUim()).thenReturn(true)
        manager.showUnifiedInputMenu(keyboard("a")) // caches the menu keyboard
        manager.hide()
        manager.showAccentBar(listOf("é"))
        manager.hideAccentBar()
        assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
    }

    @Test
    fun hidingAnAccentBarThatCoveredNothingLeavesTheSlotForTheStripOtherwise() {
        Mockito.`when`(events.shouldShowUim()).thenReturn(false)
        manager.showAccentBar(listOf("é"))
        manager.hideAccentBar()
        assertEquals(AuxBarState.NONE, manager.currentState)
    }

    @Test
    fun reshowingTheAccentBarKeepsTheOriginalCoveredState() {
        manager.showSuggestionStrip(words(), false)
        manager.showAccentBar(listOf("é"))
        manager.showAccentBar(listOf("è")) // a second key while the bar is up
        manager.hideAccentBar()
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
    }

    // ── hide ────────────────────────────────────────────────────────────────

    @Test
    fun hideTakesAnyStateToNone() {
        showArrowBarWith(keyboard("a"))
        manager.hide()
        assertEquals(AuxBarState.NONE, manager.currentState)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(false, manager.isShowing)
    }

    // ── key routing from the shared key view ────────────────────────────────

    @Test
    fun arrowBarKeysGoToTheArrowController() {
        showArrowBarWith(keyboard("a"))
        val key = keyView.keyboard.keys.first()

        keyListener().onKeyDown(key)
        keyListener().onKeyUp(key, true)
        keyListener().onKeyLongPress(key)

        verify(arrows).onKeyDown(key)
        verify(arrows).onKeyUp(key, true)
        verify(arrows, never()).onKeyLongPress(Mockito.any())
    }

    @Test
    fun keysInOtherStatesDoNotReachTheArrowController() {
        manager.showAccentBar(listOf("é"))
        val key = keyView.keyboard.keys.first()
        manager.showUnifiedInputMenu(keyboard("u"))

        keyListener().onKeyDown(key)
        keyListener().onKeyUp(key, true)
        keyListener().onKeyLongPress(key)

        verify(arrows, never()).onKeyDown(Mockito.any())
        verify(arrows, never()).onKeyUp(Mockito.any(), Mockito.anyBoolean())
    }

    @Test
    fun aReleasedAccentKeyCommitsItsCharacterAndHidesTheBar() {
        manager.showSuggestionStrip(words(), false)
        manager.showAccentBar(listOf("é", "è"))
        ReflectionHelpers.setField(manager, "currentAccentBarKeyCode", 42)
        val key = keyView.keyboard.keys[1]

        keyListener().onKeyUp(key, true)

        verify(events).onAccentSelected("è")
        // The pick puts the strip the bar covered straight back.
        assertEquals(AuxBarState.LATIN_SUGGESTIONS, manager.currentState)
        assertEquals(-1, accentKeyCode())
    }

    @Test
    fun anAccentKeyThatWasNotReleasedOnTheKeyCommitsNothing() {
        manager.showAccentBar(listOf("é"))
        val key = keyView.keyboard.keys.first()

        keyListener().onKeyUp(key, false)
        keyListener().onKeyDown(key)
        keyListener().onKeyLongPress(key)

        verify(events, never()).onAccentSelected(Mockito.anyString())
        assertEquals(AuxBarState.ACCENT_BAR, manager.currentState)
    }

    @Test
    fun aNullKeyUpIsIgnored() {
        manager.showAccentBar(listOf("é"))
        keyListener().onKeyUp(null, true)
        assertEquals(AuxBarState.ACCENT_BAR, manager.currentState)
    }

    @Test
    fun anAccentKeyWithANonPositiveCodeCommitsNothing() {
        manager.showAccentBar(listOf("é"))
        val key = Mockito.mock(Key::class.java)
        Mockito.`when`(key.code).thenReturn(0)

        keyListener().onKeyUp(key, true)

        verify(events, never()).onAccentSelected(Mockito.anyString())
        assertEquals(AuxBarState.ACCENT_BAR, manager.currentState)
    }

    @Test
    fun uimKeysWithNoBoardManagerAreDroppedQuietly() {
        manager.showUnifiedInputMenu(keyboard("u"))
        val key = keyView.keyboard.keys.first()

        keyListener().onKeyDown(key)
        keyListener().onKeyUp(key, true)
        keyListener().onKeyLongPress(key)

        assertEquals(AuxBarState.UNIFIED_INPUT_MENU, manager.currentState)
        verifyNoInteractions(arrows)
    }

    @Test
    fun anArrowKeyHeldWhenTheBarIsDismissedIsReleasedToTheArrowController() {
        manager.showSuggestionStrip(words(), false)
        showArrowBarWith(keyboard("a"))
        val key = keyView.keyboard.keys.first()
        // Visibility-change callbacks (and so the held-key release) are only dispatched when attached.
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(bar)
        assertTrue(bar.isAttachedToWindow)
        ReflectionHelpers.setField(SimplifiedKeyboardView::class.java, keyView, "pressedKey", key)

        manager.hideArrowBar()

        verify(arrows).onKeyUp(key, false)
    }

    @Test
    fun anAccentKeyHeldWhenTheBarIsHiddenIsReleasedWithoutCommitting() {
        manager.showAccentBar(listOf("é"))
        val key = keyView.keyboard.keys.first()
        // Visibility-change callbacks (and so the held-key release) are only dispatched when attached.
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(bar)
        assertTrue(bar.isAttachedToWindow)
        ReflectionHelpers.setField(SimplifiedKeyboardView::class.java, keyView, "pressedKey", key)

        manager.hide()

        verify(events, never()).onAccentSelected(Mockito.anyString())
        assertNotNull(keyView.keyboard)
    }
}
