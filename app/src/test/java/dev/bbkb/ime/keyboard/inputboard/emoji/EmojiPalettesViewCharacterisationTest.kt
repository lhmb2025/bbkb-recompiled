package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.content.SharedPreferences
import android.os.Looper
import android.view.View
import android.widget.TabHost
import androidx.viewpager.widget.ViewPager
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.padded
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.read
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterisation of `EmojiPalettesView` and its pager adapter, driven through the real view tree
 * inflated from `emoji_palettes_view.xml` with the app's resources and keyboard XML. The dataset is
 * [EmojiTestEnv.seedStandard]: positions 0 Recents | 1-2 Smileys | 3 Animals | 4 Food, and People
 * has no emoji, so it has no tab and no pages.
 *
 * Pinned AS IT BEHAVES, including the entries marked CHARACTERISED DEFECT.
 *
 * ## The second open (audit §6 defect 11)
 * `KeyboardSwitcher.hideEmojiKeyboard()` calls `detachPagerAdapter()`, which sets the pager's
 * adapter to null. The ONLY place the adapter is attached again is `updateDeleteButton(...)`, which
 * `showEmojiKeyboardInternal()` calls on every open. [secondOpenIsEmptyUntilUpdateDeleteButtonReattaches]
 * replays that exact call sequence and pins both halves: without the call the pager is empty, with it
 * the same adapter is back at the page the user left.
 *
 * ## Remembered category
 * A remembered category id must name a category that has a tab; anything else (an empty category,
 * a negative or out-of-range id) opens on the default category, which replaces it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp",
    shadows = [PassThroughTabHostShadow::class, PassThroughTabSpecShadow::class]
)
class EmojiPalettesViewCharacterisationTest {

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

    // ------------------------------------------------------------------ harness

    private fun open(): EmojiPalettesView = EmojiTestEnv.inflatePalettes(activity)

    private fun EmojiPalettesView.pager(): ViewPager = findViewById(R.id.emoji_keyboard_pager)

    private fun EmojiPalettesView.tabs(): TabHost = findViewById(R.id.emoji_category_tabhost)

    private fun EmojiPalettesView.overlay(): EmojiSearchOverlayView = findViewById(R.id.emoji_search_overlay)

    /** (page count, current page, offset) as last handed to the dot indicator. */
    private fun EmojiPalettesView.indicator(): Triple<Int, Int, Float> {
        val dots = findViewById<EmojiCategoryPageIndicatorView>(R.id.emoji_category_page_id_view)
        return Triple(read(dots, "pageCount") as Int, read(dots, "currentPage") as Int, read(dots, "pageOffset") as Float)
    }

    private fun EmojiPalettesView.select(item: Int) {
        pager().setCurrentItem(item, false)
        idle()
    }

    private fun EmojiPalettesView.selectTab(index: Int) {
        tabs().currentTab = index
        idle()
    }

    private fun EmojiPalettesView.pageViews(): List<EmojiPageKeyboardView> {
        val p = pager()
        return (0 until p.childCount).map { p.getChildAt(it) }.filterIsInstance<EmojiPageKeyboardView>()
    }

    private fun EmojiPalettesView.firstOutputs(): List<String?> =
        pageViews().map { it.keyboard?.keys?.firstOrNull()?.keySpecOutputText }

    private fun EmojiPalettesView.keyFor(output: String): Key =
        pageViews().flatMap { it.keyboard?.keys ?: emptyList() }.first { it.keySpecOutputText == output }

    private fun EmojiSearchOverlayView.keyboards(): List<EmojiKeyboard> =
        (read(this, "currentKeyboards") as List<*>).map { it as EmojiKeyboard }

    private fun emojiOutputs(k: EmojiKeyboard): List<String?> =
        k.keys.filter { it.code != -21 }.map { it.keySpecOutputText }

    private fun lastCategory(): Int = prefs.getInt(LAST_CATEGORY_KEY, Int.MIN_VALUE)

    private fun remember(categoryId: Int) {
        prefs.edit().putInt(LAST_CATEGORY_KEY, categoryId).commit()
    }

    private fun storeRecents(json: String) {
        prefs.edit().putString(RECENTS_KEY, json).commit()
    }

    private fun storedRecents(): String? = prefs.getString(RECENTS_KEY, null)

    // ------------------------------------------------------------------ category index

    @Test
    fun emptyCategoriesGetNoTabAndNoPages() {
        val v = open()
        assertEquals(5, v.pager().adapter!!.count)
        assertEquals(4, v.tabs().tabWidget.tabCount)
    }

    @Test
    fun withNothingRememberedTheBoardOpensOnTheFirstEmojiCategory() {
        val v = open()
        assertEquals(1, v.pager().currentItem)
        assertEquals(1, v.tabs().currentTab)
        assertEquals(1, lastCategory())
        assertEquals(Triple(2, 0, 0f), v.indicator())
    }

    @Test
    fun aRememberedCategoryOpensAtItsFirstPage() {
        remember(3)
        val v = open()
        assertEquals(3, v.pager().currentItem)
        assertEquals(2, v.tabs().currentTab)
        assertEquals(3, lastCategory())
    }

    /** The last category (Food, id 4) restores even though an earlier category (People) is empty. */
    @Test
    fun rememberedLastCategoryIsRestoredWhenAnEarlierCategoryIsEmpty() {
        remember(4)
        val v = open()
        assertEquals(4, v.pager().currentItem)
        assertEquals(3, v.tabs().currentTab)
        assertEquals(4, lastCategory())
    }

    /** A remembered empty category (People) opens on the default category and is not kept. */
    @Test
    fun rememberedEmptyCategoryOpensOnTheDefaultCategoryAndIsReplaced() {
        remember(2)
        val v = open()
        assertEquals(1, v.pager().currentItem)
        assertEquals(1, v.tabs().currentTab)
        assertEquals(1, lastCategory())
    }

    /** A remembered negative id opens on the default category and is not kept. */
    @Test
    fun rememberedNegativeIdOpensOnTheDefaultCategoryAndIsReplaced() {
        remember(-5)
        val v = open()
        assertEquals(1, v.pager().currentItem)
        assertEquals(1, v.tabs().currentTab)
        assertEquals(1, lastCategory())
    }

    /** An id past the id space (10) opens on the default category and is not kept. */
    @Test
    fun rememberedOutOfRangeIdOpensOnTheDefaultCategoryAndIsReplaced() {
        remember(10)
        val v = open()
        assertEquals(1, v.pager().currentItem)
        assertEquals(1, lastCategory())
    }

    /** Remembered Recents with no real recents (only blank padding) opens on the default category. */
    @Test
    fun rememberedRecentsWithNoRecentsOpensOnTheDefaultCategory() {
        remember(0)
        val v = open()
        assertEquals(1, v.pager().currentItem)
        assertEquals(1, v.tabs().currentTab)
        assertEquals(1, lastCategory())
        assertNull(storedRecents())
        assertFalse(v.overlay().isOverlayVisible)
    }

    @Test
    fun rememberedRecentsWithRealRecentsOpensOnRecents() {
        remember(0)
        storeRecents(padded("😀"))
        val v = open()
        assertEquals(0, v.pager().currentItem)
        assertEquals(0, v.tabs().currentTab)
        assertEquals(0, lastCategory())
    }

    /** Lands on Recents by tapping its tab: with no recents stored the board no longer opens there. */
    private fun openOnEmptyRecents(): EmojiPalettesView = open().also { it.selectTab(0) }

    @Test
    fun selectingATabMovesThePagerToThatCategorysFirstPage() {
        val v = open()
        v.selectTab(3)
        assertEquals(4, v.pager().currentItem)
        assertEquals(4, lastCategory())
        assertEquals(Triple(1, 0, 0f), v.indicator())
    }

    @Test
    fun pagingAcrossCategoryBoundariesTracksTabPageAndIndicator() {
        val v = open()
        v.select(2)
        assertEquals(1, v.tabs().currentTab)
        assertEquals(1, lastCategory())
        assertEquals(Triple(2, 1, 0f), v.indicator())

        v.select(3)
        assertEquals(2, v.tabs().currentTab)
        assertEquals(3, lastCategory())
        assertEquals(Triple(1, 0, 0f), v.indicator())

        v.select(4)
        assertEquals(3, v.tabs().currentTab)
        assertEquals(4, lastCategory())

        v.select(0)
        assertEquals(0, v.tabs().currentTab)
        assertEquals(0, lastCategory())
        assertEquals(Triple(1, 0, 0f), v.indicator())

        v.select(1)
        assertEquals(1, v.tabs().currentTab)
        assertEquals(Triple(2, 0, 0f), v.indicator())
    }

    @Test
    fun scrollOffsetsAreReportedRelativeToTheCurrentCategory() {
        val v = open() // category 1 (Smileys, 2 pages), page 0
        v.onPageScrolled(2, 0.25f, 0)
        assertEquals(Triple(2, 1, 0.25f), v.indicator())
        v.onPageScrolled(3, 0.5f, 0)
        assertEquals(Triple(2, 0, 0.5f), v.indicator())
        v.onPageScrolled(0, 0.75f, 0)
        assertEquals(Triple(2, 0, -0.25f), v.indicator())
    }

    @Test
    fun pagesShowTheirCategorysEmojiInOrder() {
        val v = open()
        assertTrue(v.firstOutputs().containsAll(listOf("s0", "s40")))
        v.select(3)
        assertTrue(v.firstOutputs().contains("a0"))
    }

    // ------------------------------------------------------------------ second open (defect 11)

    @Test
    fun secondOpenIsEmptyUntilUpdateDeleteButtonReattaches() {
        val v = open()
        v.select(3)
        val adapter = v.pager().adapter
        assertNotNull(adapter)

        // KeyboardSwitcher.hideEmojiKeyboard()
        v.visibility = View.GONE
        v.detachPagerAdapter()
        idle()
        assertNull(v.pager().adapter)
        assertEquals(0, v.pager().childCount)

        // showEmojiKeyboardInternal() without its updateDeleteButton(...) call
        v.visibility = View.VISIBLE
        v.setTabChanged(false)
        v.setSwitchingToEmoji(true)
        idle()
        assertNull(v.pager().adapter)
        assertEquals(0, v.pager().childCount)

        // ...and with it
        v.updateDeleteButton(null, null, null, EmojiTestEnv.templateKeyboard().mIconsSet, "en")
        idle()
        assertSame(adapter, v.pager().adapter)
        assertEquals(3, v.pager().currentItem)
        assertTrue(v.firstOutputs().contains("a0"))
    }

    /** Hiding with no recents change does not write the recents preference. */
    @Test
    fun hidingWithoutARecentsChangeDoesNotWriteThePreference() {
        val v = open()
        assertFalse(prefs.contains(RECENTS_KEY))
        v.detachPagerAdapter()
        assertFalse(prefs.contains(RECENTS_KEY))
    }

    /** Opening on Recents and leaving it with no change leaves the stored value untouched. */
    @Test
    fun openingOnAndLeavingRecentsWithoutAChangeDoesNotWriteThePreference() {
        remember(0)
        storeRecents("[\"😀\",\"😁\"]")
        val v = open()
        assertEquals(0, v.pager().currentItem)
        v.selectTab(1)
        v.detachPagerAdapter()
        assertEquals("[\"😀\",\"😁\"]", storedRecents())
    }

    // ------------------------------------------------------------------ pager adapter page lifecycle

    /** While switching to emoji (every open until requestShiftOff), destroyed pages are still removed. */
    @Test
    fun whileSwitchingToEmojiDestroyedPagesAreRemoved() {
        val v = open()
        assertEquals(3, v.pager().childCount)
        v.setSwitchingToEmoji(true)
        v.select(4)
        assertEquals(2, v.pager().childCount)
        v.select(1)
        assertEquals(3, v.pager().childCount)
        assertTrue(v.firstOutputs().containsAll(listOf("s0", "s40")))
    }

    @Test
    fun whenNotSwitchingToEmojiDestroyedPagesAreRemoved() {
        val v = open()
        v.setSwitchingToEmoji(false)
        v.select(4)
        assertEquals(2, v.pager().childCount)
        v.select(1)
        assertEquals(3, v.pager().childCount)
    }

    // ------------------------------------------------------------------ taps and recents

    @Test
    fun anEmojiTappedInACategoryIsWrittenToRecentsImmediately() {
        val v = open()
        v.onEmojiKeyReleased(v.keyFor("s0"))
        assertEquals(padded("s0"), storedRecents())
    }

    @Test
    fun anEmojiTappedOnRecentsIsQueuedUntilTheBoardLeavesRecents() {
        remember(0)
        storeRecents("[\"😀\",\"😁\"]")
        val v = open()
        assertEquals("[\"😀\",\"😁\"]", storedRecents())
        v.onEmojiKeyReleased(v.keyFor("😁"))
        assertEquals("[\"😀\",\"😁\"]", storedRecents())
        v.selectTab(1)
        assertEquals(padded("😁", "😀"), storedRecents())
    }

    /** A corrupted recents value (an entry the key-spec parser rejects) still opens a working board. */
    @Test
    fun aRecentsEntryTheParserRejectsIsSkippedByThePageAndTheOverlay() {
        remember(0)
        storeRecents("[\"|x\",\"😀\"]")
        val v = open()
        assertEquals(0, v.pager().currentItem)
        assertTrue(v.firstOutputs().contains("😀"))
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(listOf("😀"), emojiOutputs(v.overlay().keyboards().single()))
    }

    // ------------------------------------------------------------------ recents overlay and search

    @Test
    fun openingOnRecentsShowsAtMostThirtyRecentsInTheOverlay() {
        remember(0)
        storeRecents(EmojiTestEnv.jsonArray((0 until 35).map { "r$it" }))
        val v = open()
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(1, v.overlay().pageCount)
        val page = v.overlay().keyboards().single()
        assertEquals((0 until 30).map { "r$it" }, emojiOutputs(page))
        assertEquals(40, page.keys.size)
    }

    @Test
    fun shortComposingTextShowsRecentsOnlyOnTheRecentsTab() {
        storeRecents("[\"😀\"]")
        val v = open() // category 1
        v.onEmojiOpened("a")
        assertFalse(v.overlay().isOverlayVisible)
        assertFalse(v.isDynamicSearchActive)

        v.selectTab(0)
        assertTrue(v.overlay().isOverlayVisible)

        v.onEmojiOpened(null)
        assertTrue(v.overlay().isOverlayVisible)

        storeRecents("")
        v.onEmojiOpened("")
        assertFalse(v.overlay().isOverlayVisible)
    }

    @Test
    fun searchResultsReplaceTheOverlayAfterTheDebounceAndNoResultsHideIt() {
        val v = openOnEmptyRecents()
        assertFalse(v.overlay().isOverlayVisible)

        v.onEmojiOpened("smile")
        assertTrue(v.isDynamicSearchActive)
        assertFalse(v.overlay().isOverlayVisible)
        assertTrue(EmojiTestEnv.waitUntil { v.overlay().isOverlayVisible })
        val pages = v.overlay().keyboards()
        assertEquals(2, pages.size)
        assertEquals((0 until 40).map { "s$it" }, emojiOutputs(pages[0]))
        assertEquals(listOf("s40"), emojiOutputs(pages[1]))
        assertEquals(40, pages[1].keys.size)

        v.onComposingTextChanged("zzzz")
        assertTrue(EmojiTestEnv.waitUntil { !v.overlay().isOverlayVisible })
    }

    /** Shortening the query below two characters cancels the debounced search; no stale results land. */
    @Test
    fun shorteningTheQueryCancelsTheSearchAlreadyPending() {
        val v = openOnEmptyRecents()
        v.onEmojiOpened("smile")
        v.onEmojiOpened("s")
        assertNull(read(v, "pendingSearchRunnable"))
        EmojiTestEnv.settle()
        assertFalse(v.overlay().isOverlayVisible)
        assertFalse(v.isDynamicSearchActive)
    }

    /** A search already running when the query is shortened has its results dropped. */
    @Test
    fun resultsOfASearchSupersededWhileRunningAreDropped() {
        val v = openOnEmptyRecents()
        v.onEmojiOpened("smile")
        // Run the debounce, then wait for the executor to post its result to the (paused) main looper.
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        val end = System.currentTimeMillis() + 5000
        while (org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).isIdle && System.currentTimeMillis() < end) {
            Thread.sleep(5)
        }
        assertFalse("search result was never posted", org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).isIdle)
        v.onEmojiOpened("s")
        EmojiTestEnv.settle()
        assertFalse(v.overlay().isOverlayVisible)
    }

    @Test
    fun searchResultsArrivingAfterLeavingRecentsAreDropped() {
        val v = openOnEmptyRecents()
        v.onEmojiOpened("smile")
        v.selectTab(1)
        EmojiTestEnv.settle()
        assertFalse(v.overlay().isOverlayVisible)
    }

    @Test
    fun withDynamicSearchOnLeavingRecentsEndsSearchAndReturningShowsRecents() {
        EmojiTestEnv.useSettings(EmojiTestEnv.settings(dynamicSearch = true))
        remember(0)
        storeRecents("[\"😀\"]")
        val v = open()
        v.onEmojiOpened("smile")
        assertTrue(EmojiTestEnv.waitUntil {
            v.overlay().keyboards().firstOrNull()?.keys?.firstOrNull()?.keySpecOutputText == "s0"
        })
        assertTrue(v.isDynamicSearchActive)

        v.selectTab(1)
        assertFalse(v.overlay().isOverlayVisible)
        assertFalse(v.isDynamicSearchActive)

        v.selectTab(0)
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(listOf("😀"), emojiOutputs(v.overlay().keyboards().single()))
        assertFalse(v.isDynamicSearchActive)
    }

    @Test
    fun withDynamicSearchOffLeavingRecentsHidesTheOverlayButKeepsSearchActive() {
        val v = openOnEmptyRecents()
        v.onEmojiOpened("smile")
        assertTrue(EmojiTestEnv.waitUntil { v.overlay().isOverlayVisible })
        v.selectTab(1)
        assertFalse(v.overlay().isOverlayVisible)
        assertTrue(v.isDynamicSearchActive)
    }
}
