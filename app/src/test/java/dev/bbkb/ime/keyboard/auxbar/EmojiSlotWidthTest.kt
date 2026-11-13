package dev.bbkb.ime.keyboard.auxbar

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import dev.bbkb.ime.R
import dev.bbkb.ime.core.suggestion.SuggestedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The three word slots share the strip's width and give room to the emoji slot on the same update that shows it.
 * The width is taken in `UnifiedSuggestionView.onMeasure`: a `notifyDataSetChanged()` from a layout listener is a
 * layout request from inside layout, which `ViewRootImpl` drops, leaving the old width until the next update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiSlotWidthTest {

    private lateinit var bar: AuxBarView

    private fun info(word: String, kind: Int = 0) =
        SuggestedWords.SuggestedWordInfo(word, 100, kind, null, -1, -1, null)

    /** Three words, plus an emoji result (kind 11) when [emoji] is given. */
    private fun words(vararg w: String, emoji: String? = null): SuggestedWords {
        val list = ArrayList(w.map { info(it) })
        if (emoji != null) list.add(info(emoji, 11))
        return SuggestedWords(list, null, false, false, 0)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ctx = ContextThemeWrapper(activity, R.style.KeyboardTheme_LXX)
        dev.bbkb.ime.core.locale.RichInputMethodManager.init(ctx)
        bar = AuxBarView(ctx)
        val host = FrameLayout(activity)
        host.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120))
        activity.setContentView(host)
        idle()
    }

    private fun show(words: SuggestedWords) {
        bar.showSuggestions(words, false)
        idle()
    }

    /** Every word slot is a third of the recycler, and the last one ends inside it. */
    private fun assertSlotsFit(step: String, emojiShowing: Boolean) {
        val view = bar.suggestionView
        val rv = view.findViewById<RecyclerView>(R.id.suggestion_recycler)
        val slot = view.findViewById<View>(R.id.emoji_suggestion_slot)
        assertEquals("$step: emoji slot visibility", emojiShowing, slot.visibility == View.VISIBLE)
        assertEquals("$step: three word slots", 3, rv.childCount)
        val third = rv.width / 3
        for (i in 0 until rv.childCount) {
            assertEquals("$step: slot $i is a third of the strip (${rv.width})", third, rv.getChildAt(i).width)
        }
        val lastRight = rv.getChildAt(rv.childCount - 1).right
        assertTrue("$step: the last word ($lastRight) ends inside the strip (${rv.width})", lastRight <= rv.width)
    }

    @Test
    fun theWordSlotsShrinkOnTheUpdateThatShowsTheEmoji() {
        show(words("pizzas", "pizzeria", "pizza"))
        assertSlotsFit("no emoji", emojiShowing = false)

        show(words("pizza", "pizzas", "pizzeria", emoji = "🍕"))
        assertSlotsFit("emoji appears", emojiShowing = true)
    }

    @Test
    fun theWordSlotsGrowBackOnTheUpdateThatTakesTheEmojiAway() {
        show(words("pizza", "pizzas", "pizzeria", emoji = "🍕"))
        assertSlotsFit("emoji shown", emojiShowing = true)

        show(words("the", "is", "was"))
        assertSlotsFit("emoji gone", emojiShowing = false)
    }
}
