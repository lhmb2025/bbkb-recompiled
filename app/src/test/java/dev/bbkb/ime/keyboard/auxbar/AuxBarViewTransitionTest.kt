package dev.bbkb.ime.keyboard.auxbar

import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Characterisation of the aux bar's container state machine, [AuxBarView].
 *
 * The bar has two children, the suggestion list and one shared key view, and every public mutator
 * sets the container's visibility, both children's visibility, `currentState` and the listener
 * notification. These tests pin, per entry point, what each of those ends up as and in what order
 * the observable parts happen, because the order is observable in two places:
 *
 *  - [SimplifiedKeyboardView.onVisibilityChanged] releases a held key and fires `onKeyUp(key, false)`
 *    as the key view goes GONE. `AuxBarManager` routes that call by `getCurrentState()`, so a key held
 *    across a transition is delivered to the handler of the state being LEFT, not the state entered.
 *  - The state-change listener runs after both children and the content are already in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuxBarViewTransitionTest {

    private lateinit var context: ContextThemeWrapper
    private lateinit var bar: AuxBarView
    private lateinit var suggestions: UnifiedSuggestionView
    private lateinit var keys: SimplifiedKeyboardView
    private val transitions = mutableListOf<Pair<AuxBarState, AuxBarState>>()

    @Before
    fun setUp() {
        context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.KeyboardTheme_LXX)
        // UnifiedSuggestionView's MoreSuggestions builder reads subtype labels at inflation.
        dev.bbkb.ime.core.locale.RichInputMethodManager.init(context)
        bar = AuxBarView(context)
        suggestions = bar.suggestionView
        keys = bar.sharedKeyView
        bar.setStateChangeListener { old, new -> transitions += old to new }
    }

    private fun words() = SuggestedWords(ArrayList(), false, false, 0)

    private fun keyboard(vararg labels: String): Keyboard =
        KeyboardBuilder.createFromLabels(context, labels.map { it as CharSequence }, 40)

    private fun currentWords(): SuggestedWords? = ReflectionHelpers.getField(suggestions, "currentSuggestions")

    private fun assertVisibility(container: Int, suggestionChild: Int, keyChild: Int) {
        assertEquals("container", container, bar.visibility)
        assertEquals("suggestion view", suggestionChild, suggestions.visibility)
        assertEquals("shared key view", keyChild, keys.visibility)
    }

    /** Visibility-change callbacks (and so the held-key release) are only dispatched when attached. */
    private fun attachToAWindow() {
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get().setContentView(bar)
        assertTrue(bar.isAttachedToWindow)
    }

    /** Puts a key in the pressed slot, as a finger held on the key view would. */
    private fun holdAKey(): Key {
        val key = keys.keyboard.keys.first()
        ReflectionHelpers.setField(SimplifiedKeyboardView::class.java, keys, "pressedKey", key)
        return key
    }

    /** Records the bar's state at the moment the key view delivers a key-up. */
    private fun recordKeyUps(): MutableList<Triple<Key, Boolean, AuxBarState>> {
        val seen = mutableListOf<Triple<Key, Boolean, AuxBarState>>()
        keys.setOnKeyEventListener(object : SimplifiedKeyboardView.onKeyEventListener {
            override fun onKeyDown(key: Key) {}
            override fun onKeyUp(key: Key, released: Boolean) { seen += Triple(key, released, bar.currentState) }
            override fun onKeyLongPress(key: Key) {}
        })
        return seen
    }

    // ── initial ─────────────────────────────────────────────────────────────

    @Test
    fun startsInNoneWithTheSuggestionChildVisibleAndTheKeyChildInvisibleNotGone() {
        assertEquals(AuxBarState.NONE, bar.currentState)
        assertVisibility(View.VISIBLE, View.VISIBLE, View.INVISIBLE)
        assertEquals(false, bar.isShowing)
        assertTrue(bar.isClickable)
    }

    // ── showSuggestions ─────────────────────────────────────────────────────

    @Test
    fun showSuggestionsLatinShowsTheSuggestionChildAndNotifies() {
        val w = words()
        bar.showSuggestions(w, false)

        assertEquals(AuxBarState.LATIN_SUGGESTIONS, bar.currentState)
        assertEquals(SuggestionMode.LATIN, suggestions.mode)
        assertSame(w, currentWords())
        assertVisibility(View.VISIBLE, View.VISIBLE, View.GONE)
        assertEquals(listOf(AuxBarState.NONE to AuxBarState.LATIN_SUGGESTIONS), transitions)
        assertTrue(bar.isShowing)
    }

    @Test
    fun showSuggestionsCjkEntersCjkWithCjkMode() {
        bar.showSuggestions(words(), true)

        assertEquals(AuxBarState.CJK_SUGGESTIONS, bar.currentState)
        assertEquals(SuggestionMode.CJK, suggestions.mode)
        assertEquals(listOf(AuxBarState.NONE to AuxBarState.CJK_SUGGESTIONS), transitions)
    }

    @Test
    fun reShowingTheSameStateReplacesContentButDoesNotNotifyAgain() {
        bar.showSuggestions(words(), false)
        val second = words()
        bar.showSuggestions(second, false)

        assertSame(second, currentWords())
        assertEquals(1, transitions.size)
    }

    @Test
    fun showSuggestionsBringsTheContainerBackAfterHide() {
        bar.hide()
        bar.showSuggestions(words(), false)
        assertVisibility(View.VISIBLE, View.VISIBLE, View.GONE)
    }

    @Test
    fun theListenerSeesChildrenModeAndContentAlreadyInPlace() {
        val w = words()
        var observed: List<Any?> = emptyList()
        bar.setStateChangeListener { _, new ->
            observed = listOf(new, bar.visibility, suggestions.visibility, keys.visibility, suggestions.mode, currentWords())
        }
        bar.showSuggestions(w, true)

        assertEquals(
            listOf(AuxBarState.CJK_SUGGESTIONS, View.VISIBLE, View.VISIBLE, View.GONE, SuggestionMode.CJK, w),
            observed,
        )
    }

    // ── showAutofill ────────────────────────────────────────────────────────

    @Test
    fun showAutofillEntersAutofillWithAutofillMode() {
        bar.showAutofill(emptyList(), 100, 40)

        assertEquals(AuxBarState.AUTOFILL, bar.currentState)
        assertEquals(SuggestionMode.AUTOFILL, suggestions.mode)
        assertVisibility(View.VISIBLE, View.VISIBLE, View.GONE)
        assertEquals(listOf(AuxBarState.NONE to AuxBarState.AUTOFILL), transitions)
    }

    // ── showKeys ────────────────────────────────────────────────────────────

    @Test
    fun showKeysSetsTheKeyboardShowsTheKeyChildAndNotifies() {
        val kb = keyboard("a", "b")
        bar.showKeys(kb, AuxBarState.ARROW_BAR)

        assertEquals(AuxBarState.ARROW_BAR, bar.currentState)
        assertSame(kb, keys.keyboard)
        assertVisibility(View.VISIBLE, View.GONE, View.VISIBLE)
        assertEquals(listOf(AuxBarState.NONE to AuxBarState.ARROW_BAR), transitions)
    }

    @Test
    fun showKeysWithANullKeyboardIsANoOp() {
        bar.showSuggestions(words(), false)
        transitions.clear()

        bar.showKeys(null, AuxBarState.UNIFIED_INPUT_MENU)

        assertEquals(AuxBarState.LATIN_SUGGESTIONS, bar.currentState)
        assertVisibility(View.VISIBLE, View.VISIBLE, View.GONE)
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun showKeysLeavesTheSuggestionModeAndContentAlone() {
        val w = words()
        bar.showSuggestions(w, true)
        bar.showKeys(keyboard("x"), AuxBarState.UNIFIED_INPUT_MENU)

        assertEquals(SuggestionMode.CJK, suggestions.mode)
        assertSame(w, currentWords())
    }

    @Test
    fun switchingBetweenKeyBarsSwapsTheKeyboardAndNotifiesEachChange() {
        val first = keyboard("a")
        val second = keyboard("b")
        bar.showKeys(first, AuxBarState.UNIFIED_INPUT_MENU)
        bar.showKeys(second, AuxBarState.ACCENT_BAR)
        bar.showKeys(second, AuxBarState.ACCENT_BAR)

        assertSame(second, keys.keyboard)
        assertEquals(
            listOf(
                AuxBarState.NONE to AuxBarState.UNIFIED_INPUT_MENU,
                AuxBarState.UNIFIED_INPUT_MENU to AuxBarState.ACCENT_BAR,
            ),
            transitions,
        )
    }

    // ── dismissKeyViewAndRestoreSuggestions ─────────────────────────────────

    @Test
    fun dismissRestoresLatinKeepingTheSuggestionContent() {
        val w = words()
        bar.showSuggestions(w, false)
        bar.showKeys(keyboard("a"), AuxBarState.ACCENT_BAR)

        bar.dismissKeyViewAndRestoreSuggestions()

        assertEquals(AuxBarState.LATIN_SUGGESTIONS, bar.currentState)
        assertSame(w, currentWords())
        assertVisibility(View.VISIBLE, View.VISIBLE, View.GONE)
        assertEquals(AuxBarState.ACCENT_BAR to AuxBarState.LATIN_SUGGESTIONS, transitions.last())
    }

    @Test
    fun dismissRestoresCjkWhenTheSuggestionViewWasInCjkMode() {
        bar.showSuggestions(words(), true)
        bar.showKeys(keyboard("a"), AuxBarState.ARROW_BAR)

        bar.dismissKeyViewAndRestoreSuggestions()

        assertEquals(AuxBarState.CJK_SUGGESTIONS, bar.currentState)
    }

    @Test
    fun dismissAfterAutofillModeRestoresAutofill() {
        // The restored child still shows the autofill chips, so the state reported is AUTOFILL.
        bar.showAutofill(emptyList(), 100, 40)
        bar.showKeys(keyboard("a"), AuxBarState.ARROW_BAR)

        bar.dismissKeyViewAndRestoreSuggestions()

        assertEquals(AuxBarState.AUTOFILL, bar.currentState)
        assertEquals(SuggestionMode.AUTOFILL, suggestions.mode)
        assertEquals(AuxBarState.ARROW_BAR to AuxBarState.AUTOFILL, transitions.last())
    }

    @Test
    fun dismissFromNoneShowsTheContainerAndEntersLatinUnconditionally() {
        // CHARACTERISED: dismiss has no state guard of its own — its callers provide it.
        bar.hide()
        transitions.clear()

        bar.dismissKeyViewAndRestoreSuggestions()

        assertEquals(AuxBarState.LATIN_SUGGESTIONS, bar.currentState)
        assertVisibility(View.VISIBLE, View.VISIBLE, View.GONE)
        assertEquals(listOf(AuxBarState.NONE to AuxBarState.LATIN_SUGGESTIONS), transitions)
    }

    // ── hide ────────────────────────────────────────────────────────────────

    @Test
    fun hideTakesEverythingDownClearsContentAndNotifies() {
        bar.showSuggestions(words(), false)
        bar.hide()

        assertEquals(AuxBarState.NONE, bar.currentState)
        assertNull(currentWords())
        assertVisibility(View.GONE, View.GONE, View.GONE)
        assertEquals(AuxBarState.LATIN_SUGGESTIONS to AuxBarState.NONE, transitions.last())
        assertEquals(false, bar.isShowing)
    }

    @Test
    fun hideFromNoneStillCollapsesTheContainerButDoesNotNotify() {
        bar.hide()

        assertVisibility(View.GONE, View.GONE, View.GONE)
        assertTrue(transitions.isEmpty())
    }

    // ── ordering: a held key is released to the state being LEFT ────────────

    @Test
    fun aKeyHeldAcrossHideIsReleasedWhileTheOldStateIsStillCurrent() {
        bar.showKeys(keyboard("é", "è"), AuxBarState.ACCENT_BAR)
        attachToAWindow()
        val seen = recordKeyUps()
        val key = holdAKey()

        bar.hide()

        assertEquals(listOf(Triple(key, false, AuxBarState.ACCENT_BAR)), seen)
    }

    @Test
    fun aKeyHeldAcrossDismissIsReleasedWhileTheOldStateIsStillCurrent() {
        bar.showSuggestions(words(), false)
        bar.showKeys(keyboard("a"), AuxBarState.ARROW_BAR)
        attachToAWindow()
        val seen = recordKeyUps()
        val key = holdAKey()

        bar.dismissKeyViewAndRestoreSuggestions()

        assertEquals(listOf(Triple(key, false, AuxBarState.ARROW_BAR)), seen)
    }

    @Test
    fun aKeyHeldAcrossShowSuggestionsIsReleasedWhileTheOldStateIsStillCurrent() {
        bar.showKeys(keyboard("a"), AuxBarState.UNIFIED_INPUT_MENU)
        attachToAWindow()
        val seen = recordKeyUps()
        val key = holdAKey()

        bar.showSuggestions(words(), false)

        assertEquals(listOf(Triple(key, false, AuxBarState.UNIFIED_INPUT_MENU)), seen)
    }

    @Test
    fun aKeyHeldAcrossShowAutofillIsReleasedWhileTheOldStateIsStillCurrent() {
        bar.showKeys(keyboard("a"), AuxBarState.ARROW_BAR)
        attachToAWindow()
        val seen = recordKeyUps()
        val key = holdAKey()

        bar.showAutofill(emptyList(), 100, 40)

        assertEquals(listOf(Triple(key, false, AuxBarState.ARROW_BAR)), seen)
    }

    @Test
    fun switchingBetweenKeyBarsDoesNotReleaseAHeldKey() {
        // The key view stays VISIBLE across a key-bar to key-bar switch, so nothing is released.
        bar.showKeys(keyboard("a"), AuxBarState.UNIFIED_INPUT_MENU)
        attachToAWindow()
        val seen = recordKeyUps()
        holdAKey()

        bar.showKeys(keyboard("b"), AuxBarState.ACCENT_BAR)

        assertTrue(seen.isEmpty())
    }

    // ── colours ─────────────────────────────────────────────────────────────

    @Test
    fun updateColorsPaintsTheContainerWithThePaletteBackground() {
        bar.setBackgroundColor(0x12345678)
        bar.updateColors()

        val bg = bar.background as android.graphics.drawable.ColorDrawable
        assertEquals(dev.bbkb.ime.keyboard.KeyboardColorManager.backgroundColor, bg.color)
    }
}
