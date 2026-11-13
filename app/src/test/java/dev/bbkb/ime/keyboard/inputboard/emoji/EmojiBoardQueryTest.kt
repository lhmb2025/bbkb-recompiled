package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.padded
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.read
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The query typed into the open board on a physical keyboard, through the real view tree. Dataset: 0 Recents |
 * 1-2 Smileys ("smile N") | 3 Animals | 4 Food. `KeyEventProcessor` routes printable keys to
 * [EmojiPalettesView.appendToQuery] and Backspace to [EmojiPalettesView.backspaceQuery]; cases start there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp"
)
class EmojiBoardQueryTest {

    private lateinit var activity: Activity
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seedStandard()
        prefs = EmojiTestEnv.prefs()
        prefs.edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.setContentView(View(activity))
        idle()
        EmojiTestEnv.uninstall()
    }

    private fun open(): EmojiPalettesView = EmojiTestEnv.inflatePalettes(activity)

    private fun EmojiPalettesView.pager(): ViewPager2 = findViewById(R.id.emoji_keyboard_pager)
    private fun EmojiPalettesView.tabs(): TabLayout = findViewById(R.id.emoji_category_tabhost)
    private fun EmojiPalettesView.overlay(): EmojiSearchOverlayView = findViewById(R.id.emoji_search_overlay)
    private fun EmojiPalettesView.tabRow(): View = findViewById(R.id.emoji_tab_row)
    private fun EmojiPalettesView.field(): View = findViewById(R.id.emoji_search_field)
    private fun EmojiPalettesView.fieldText(): TextView = findViewById(R.id.emoji_search_query)
    private fun EmojiPalettesView.clearButton(): ImageButton = findViewById(R.id.emoji_search_clear)
    private fun EmojiPalettesView.dots(): View = findViewById(R.id.emoji_category_page_id_view)

    /** The dots row always stays (background and height); this is how many dots it draws. */
    private fun EmojiPalettesView.dotCount(): Int {
        assertEquals(View.VISIBLE, dots().visibility)
        return read(dots(), "pageCount") as Int
    }

    private fun EmojiSearchOverlayView.keyboards(): List<EmojiKeyboard> =
        (read(this, "currentKeyboards") as List<*>).map { it as EmojiKeyboard }

    private fun emojiOutputs(k: EmojiKeyboard): List<String?> =
        k.keys.filter { it.code != -21 }.map { it.keySpecOutputText }

    private fun EmojiPalettesView.type(text: String) = text.forEach { appendToQuery(it.toString()) }

    private fun EmojiPalettesView.assertNormal() {
        assertFalse(isSearchFieldShowing)
        assertEquals(View.VISIBLE, tabRow().visibility)
        assertEquals(View.GONE, field().visibility)
        assertEquals(View.VISIBLE, dots().visibility)
        assertEquals("", queryText)
    }

    /** Smileys (category 1) has two pages, so its tab draws two dots. */
    private fun EmojiPalettesView.assertTabDotsRestored() = assertEquals(2, dotCount())

    private fun EmojiPalettesView.assertSearching(query: String) {
        assertTrue(isSearchFieldShowing)
        assertEquals(View.GONE, tabRow().visibility)
        assertEquals(View.VISIBLE, field().visibility)
        assertEquals(query, queryText)
        assertEquals(query, fieldText().text.toString())
    }

    private fun EmojiPalettesView.resultsShow(prefix: String): Boolean =
        overlay().isOverlayVisible && overlay().keyboards().firstOrNull()?.let { emojiOutputs(it).firstOrNull()?.startsWith(prefix) } == true

    @Test
    fun theBoardOpensNormalWithNoQuery() {
        open().assertNormal()
    }

    @Test
    fun theFirstLetterShowsTheFieldAndLeavesThePageAlone() {
        val v = open() // category 1 (Smileys)
        assertTrue(v.appendToQuery("s"))
        v.assertSearching("s")
        assertEquals("the category is untouched", 1, v.pager().currentItem)
        assertEquals("no results yet, so no dots over the kept row", 1, v.dotCount())
        EmojiTestEnv.settle()
        assertFalse("one letter searches nothing", v.overlay().isOverlayVisible)
        assertEquals(1, v.dotCount())
    }

    @Test
    fun fromTheSecondLetterTheResultsShowOnWhateverTabWasCurrent() {
        val v = open()
        v.tabs().getTabAt(3)!!.view.performClick() // Food, not Recents
        idle()
        v.type("sm")
        v.assertSearching("sm")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s0") })
        val pages = v.overlay().keyboards()
        assertEquals(2, pages.size)
        assertEquals((0 until 40).map { "s$it" }, emojiOutputs(pages[0]))
        assertEquals("the tab behind the field is still Food", 4, v.pager().currentItem)
        assertEquals("two pages of results draw two dots", 2, v.dotCount())
    }

    @Test
    fun aSinglePageOfResultsHidesTheDots() {
        val v = open()
        v.type("cat")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("a0") })
        assertEquals(1, v.overlay().keyboards().size)
        assertEquals(1, v.dotCount())
    }

    @Test
    fun spaceIsPartOfTheQuery() {
        val v = open()
        v.type("smile 4")
        v.assertSearching("smile 4")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s4") })
    }

    @Test
    fun aQueryWithNoMatchShowsAnEmptyPageUnderTheField() {
        val v = open()
        v.type("zz")
        assertTrue(EmojiTestEnv.waitUntil { v.overlay().isOverlayVisible })
        val page = v.overlay().keyboards().single()
        assertTrue(emojiOutputs(page).isEmpty())
        assertEquals(40, page.keys.size)
        v.assertSearching("zz")
        assertEquals(1, v.dotCount())
    }

    @Test
    fun backspaceShortensTheQueryAndTheLastOneRestoresTheTabRow() {
        val v = open()
        v.type("sm")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s0") })

        assertTrue(v.backspaceQuery())
        v.assertSearching("s")
        assertTrue(EmojiTestEnv.waitUntil { !v.overlay().isOverlayVisible })
        assertEquals("back to one letter, the dots go again", 1, v.dotCount())

        assertTrue(v.backspaceQuery())
        v.assertNormal()
        assertEquals(1, v.pager().currentItem)
        v.assertTabDotsRestored()

        assertFalse("with no query the key passes through to the editor", v.backspaceQuery())
    }

    @Test
    fun theClearButtonEndsTheQuery() {
        val v = open()
        v.type("sm")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s0") })
        v.clearButton().performClick()
        idle()
        v.assertNormal()
        v.assertTabDotsRestored()
        assertFalse(v.overlay().isOverlayVisible)
    }

    @Test
    fun closingTheBoardClearsTheQuery() {
        val v = open()
        v.type("sm")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s0") })
        // KeyboardSwitcher.leaveEmojiModeInternal()
        v.clearQuery()
        v.detachPagerAdapter()
        idle()
        v.assertNormal()
        assertFalse(v.overlay().isOverlayVisible)
    }

    @Test
    fun resultsOfASupersededQueryAreDropped() {
        val v = open()
        v.type("sm")
        v.clearQuery()
        EmojiTestEnv.settle()
        assertFalse(v.overlay().isOverlayVisible)
        v.assertNormal()
    }

    @Test
    fun aPickLeavesTheQueryAndTheResultsInPlace() {
        val v = open()
        v.type("sm")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s0") })
        val key = v.overlay().keyboards().first().keys.first { it.code != -21 }
        v.onEmojiKeyReleased(key)
        idle()
        v.assertSearching("sm")
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(padded("s0"), prefs.getString(RECENTS_KEY, null))
        assertFalse("a typed query is not dynamic search, so the composing word is left alone", v.isDynamicSearchActive)
    }

    @Test
    fun typingOverRecentsKeepsThemUntilTheSecondLetter() {
        prefs.edit().putInt(LAST_CATEGORY_KEY, 0).commit()
        prefs.edit().putString(RECENTS_KEY, "[\"😀\"]").commit()
        val v = open()
        assertTrue(v.overlay().isOverlayVisible)
        v.appendToQuery("s")
        assertTrue("the recents page waits for the second letter", v.overlay().isOverlayVisible)
        assertEquals(listOf("😀"), emojiOutputs(v.overlay().keyboards().single()))
        v.appendToQuery("m")
        assertTrue(EmojiTestEnv.waitUntil { v.resultsShow("s0") })
        assertTrue(v.backspaceQuery())
        assertTrue(v.backspaceQuery())
        v.assertNormal()
        assertTrue("back on Recents, the recents show again", v.overlay().isOverlayVisible)
        assertEquals(listOf("😀"), emojiOutputs(v.overlay().keyboards().single()))
    }

    @Test
    fun typingOverAQueryFromTheEditorStartsATypedOne() {
        val v = open()
        v.onEmojiOpened("smile")
        assertTrue(v.isDynamicSearchActive)
        v.appendToQuery("a")
        assertFalse(v.isDynamicSearchActive)
        v.assertSearching("a")
    }

    @Test
    fun aQueryFromTheEditorNeverShowsTheField() {
        val v = open()
        v.onEmojiOpened("smile")
        assertFalse(v.isSearchFieldShowing)
        assertEquals(View.VISIBLE, v.tabRow().visibility)
        assertEquals("smile", v.queryText)
        EmojiTestEnv.settle()
        assertFalse("results stay off a category tab, as before", v.overlay().isOverlayVisible)
    }
}
