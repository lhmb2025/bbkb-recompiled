package dev.bbkb.ime.keyboard.auxbar

import android.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.suggestion.SuggestedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The strip skips the rebind when a delivery draws exactly what is shown (P-15), and otherwise
 * rebinds through ranged notifications rather than notifyDataSetChanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedSuggestionViewRebindTest {

    private lateinit var view: UnifiedSuggestionView
    private lateinit var adapter: UnifiedSuggestionAdapter
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.KeyboardTheme_LXX)
        dev.bbkb.ime.core.locale.RichInputMethodManager.init(context)
        view = AuxBarView(context).suggestionView
        adapter = ReflectionHelpers.getField(view, "adapter")
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events += "all" }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { events += "changed $positionStart+$itemCount" }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { events += "changed $positionStart+$itemCount" }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "inserted $positionStart+$itemCount" }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { events += "removed $positionStart+$itemCount" }
        })
    }

    private fun info(word: String, kind: Int = 0) =
        SuggestedWords.SuggestedWordInfo(word, 100, kind, null, -1, -1, null)

    private fun words(vararg w: String, willAutoCorrect: Boolean = false, emoji: String? = null): SuggestedWords {
        val list = ArrayList(w.map { info(it) })
        if (emoji != null) list.add(info(emoji, 11))
        return SuggestedWords(list, null, false, willAutoCorrect, 0)
    }

    @Test
    fun anIdenticalDeliveryDoesNotRebindButIsKept() {
        view.setSuggestions(words("the", "then", "they"))
        events.clear()

        val again = words("the", "then", "they")
        view.setSuggestions(again)

        assertEquals(emptyList<String>(), events)
        assertSame("clicks and getCurrentSuggestions must see the newest result", again, view.currentSuggestions)
    }

    @Test
    fun aChangedWordRebindsInPlace() {
        view.setSuggestions(words("the", "then", "they"))
        events.clear()

        view.setSuggestions(words("the", "then", "them"))

        assertEquals(listOf("changed 0+3"), events)
    }

    @Test
    fun aCountChangeInsertsOrRemovesTheTail() {
        view.setSuggestions(words("a"))
        events.clear()
        view.setSuggestions(words("a", "b", "c"))
        assertEquals(listOf("changed 0+1", "inserted 1+2"), events)

        events.clear()
        view.setSuggestions(words("x"))
        assertEquals(listOf("changed 0+1", "removed 1+2"), events)
    }

    @Test
    fun flagsAndEmojiCountAsChanges() {
        assertFalse(UnifiedSuggestionView.rendersSame(words("a", "b"), words("a", "b", willAutoCorrect = true)))
        assertFalse(UnifiedSuggestionView.rendersSame(words("a", emoji = "😀"), words("a", emoji = "😃")))
        assertFalse(UnifiedSuggestionView.rendersSame(words("a"), words("a", "b")))
        assertTrue(UnifiedSuggestionView.rendersSame(words("a", emoji = "😀"), words("a", emoji = "😀")))
    }
}
