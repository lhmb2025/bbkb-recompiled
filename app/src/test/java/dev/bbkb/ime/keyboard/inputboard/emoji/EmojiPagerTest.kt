package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.internal.DrawingPreviewPlacerView
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.pageViews
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.read
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.recycler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The ViewPager2 emoji pagers: page binding and view reuse, bounded page views, more-keys placers through
 * recycling, the long-press disallow request, recents refresh, and left-to-right paging in RTL languages.
 *
 * Dataset: positions 0 Recents | 1-2 Smileys (41) | 3-7 People (200) | 8 Animals (3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojiPagerTest {

    private companion object {
        /** People emoji given skin tones for the long-press tests; [PLAIN] has none. */
        const val TONED = "p12"
        const val PLAIN = "p13"
    }

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seed(
            linkedMapOf(
                EmojiCategory.SMILEYS_EMOTION to (0 until 41).map { EmojiTestEnv.emoji("s$it", 0, "smile $it") },
                EmojiCategory.PEOPLE_BODY to (0 until 200).map { EmojiTestEnv.emoji("p$it", 1, "person $it") },
                EmojiCategory.ANIMALS_NATURE to (0 until 3).map { EmojiTestEnv.emoji("a$it", 3, "cat $it") },
            )
        )
        EmojiTestEnv.prefs().edit().clear().commit()
        EmojiToneMemory.clear(EmojiTestEnv.prefs())
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.setContentView(View(activity))
        idle()
        EmojiTestEnv.uninstall()
    }

    private fun openOn(categoryId: Int): EmojiPalettesView {
        EmojiTestEnv.prefs().edit().putInt(LAST_CATEGORY_KEY, categoryId).commit()
        return EmojiTestEnv.inflatePalettes(activity)
    }

    private fun EmojiPalettesView.pager(): ViewPager2 = findViewById(R.id.emoji_keyboard_pager)

    private fun EmojiPalettesView.select(item: Int) {
        pager().setCurrentItem(item, false)
        idle()
    }

    private fun EmojiPalettesView.manager(): EmojiCategoryManager = read(this, "categoryManager") as EmojiCategoryManager

    /** Laid-out page views by adapter position. */
    private fun EmojiPalettesView.bound(): Map<Int, EmojiPageKeyboardView> {
        val r = pager().recycler()
        return (0 until r.childCount).associate { i ->
            val child = r.getChildAt(i)
            r.getChildAdapterPosition(child) to (child as ViewGroup).getChildAt(0) as EmojiPageKeyboardView
        }
    }

    private fun outputs(page: EmojiPageKeyboardView): List<String?> =
        page.keyboard!!.keys.filter { it.code != -21 }.map { it.keySpecOutputText }

    private fun placersInContent(): Int {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return (0 until content.childCount).count { content.getChildAt(it) is DrawingPreviewPlacerView }
    }

    @Test
    fun eachPositionIsBoundToItsOwnPage() {
        val v = openOn(2)
        for (item in listOf(3, 4, 7, 8, 1, 0, 2, 5)) {
            v.select(item)
            val bound = v.bound()
            assertTrue("current page laid out", item in bound.keys)
            for ((position, page) in bound) {
                assertSame("page at $position", v.manager().getKeyboardAtPosition(position), page.keyboard)
            }
        }
        v.select(5)
        assertEquals("p80", outputs(v.bound().getValue(5)).first())
        v.select(8)
        assertEquals(listOf("a0", "a1", "a2"), outputs(v.bound().getValue(8)))
    }

    /**
     * At most three laid-out page views (one either side) plus three spares; beyond that a recycled view is
     * dropped. Once warm, swiping page by page inflates nothing.
     */
    @Test
    fun pageViewsAreReusedAndBoundedLikeTheV1Pager() {
        val v = openOn(2)
        val r = v.pager().recycler()
        assertEquals(EmojiPagerAdapter.OFFSCREEN_PAGES, v.pager().offscreenPageLimit)
        assertEquals(View.OVER_SCROLL_IF_CONTENT_SCROLLS, r.overScrollMode)
        assertNull(r.itemAnimator)
        assertTrue(!(r.layoutManager as LinearLayoutManager).isItemPrefetchEnabled)
        val recycler = EmojiTestEnv.field(RecyclerView::class.java, "mRecycler").get(r)
        assertEquals(0, EmojiTestEnv.field(RecyclerView.Recycler::class.java, "mRequestedCacheMax").getInt(recycler))

        assertEquals(3, v.pager().pageViews().size)
        for (item in listOf(8, 1, 5, 0, 7, 2, 8, 3, 1)) {
            v.select(item)
            assertTrue("laid out at $item: ${r.childCount}", r.childCount <= 3)
            assertTrue("spares: ${r.recycledViewPool.getRecycledViewCount(0)}",
                r.recycledViewPool.getRecycledViewCount(0) <= EmojiPagerAdapter.SPARE_PAGE_VIEWS)
        }

        val seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val swipes = listOf(2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2)
        for (item in swipes) { v.select(item); v.pager().pageViews().forEach { seen.add(it) } }
        val warm = seen.size
        for (item in swipes) { v.select(item); v.pager().pageViews().forEach { seen.add(it) } }
        assertEquals("inflated while swiping once warm", warm, seen.size)
    }

    /**
     * Each laid-out page has its placer in the window content; a recycled page's goes (one turn later at
     * worst).
     */
    @Test
    fun placersFollowTheirPagesThroughRecycling() {
        val v = openOn(2)
        assertEquals(3, placersInContent())
        v.select(8) // a tab-length jump: RecyclerView recycles the old pages inside its layout
        assertEquals(v.pager().pageViews().size, placersInContent())
        v.select(1)
        assertEquals(3, placersInContent())
        for (page in v.pager().pageViews()) {
            val placer = read(page, "moreKeysLayoutParams") as View
            assertSame(activity.findViewById<View>(android.R.id.content), placer.parent)
        }
        v.detachPagerAdapter()
        idle()
        assertEquals(0, placersInContent())
        v.attachPagerAdapter()
        idle()
        assertEquals(3, placersInContent())
    }

    /**
     * The long-press popup's disallow request, made on the page's parent, reaches the RecyclerView that
     * scrolls.
     */
    @Test
    fun aLongPressCanStopTheInnerRecyclerViewFromIntercepting() {
        val v = openOn(2)
        val page = v.bound().getValue(3)
        val r = v.pager().recycler()
        page.parent.requestDisallowInterceptTouchEvent(true)
        val flags = ViewGroup::class.java.getDeclaredField("mGroupFlags").apply { isAccessible = true }
        val disallow = 0x80000 // ViewGroup.FLAG_DISALLOW_INTERCEPT
        assertTrue((flags.getInt(r) and disallow) != 0)
        page.parent.requestDisallowInterceptTouchEvent(false)
        assertEquals(0, flags.getInt(r) and disallow)
    }

    /**
     * Once the skin-tone popup is up, no move of the same finger may drag the page. The popup opens from the
     * long-press timer, so the per-event disallow request comes too late for the first move, and RecyclerView
     * intercepts any move past its horizontal slop (no angle test). The request on the long-press prevents that.
     */
    @Test
    fun noMoveCanDragThePageWhileTheTonePopupIsUp() {
        for ((dx, dy) in listOf(-108f to -229f, 108f to -229f, -200f to 0f, 200f to 0f)) {
            val g = longPressOn(TONED)
            g.move(dx / 2, dy / 2)
            g.move(dx, dy)
            assertEquals("dx=$dx dy=$dy: the pager must not drag", RecyclerView.SCROLL_STATE_IDLE, g.r.scrollState)
            g.end(android.view.MotionEvent.ACTION_CANCEL)
            activity.setContentView(View(activity))
            idle()
        }
    }

    /**
     * The request ends with its finger, lifted or cancelled; the next swipe drags the page. A long-press on an
     * emoji without tones makes no request, so its own sideways slide still drags the page.
     */
    @Test
    fun theTonePopupNeverLocksThePagerOutOfTheNextSwipe() {
        for (end in listOf(android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL)) {
            val g = longPressOn(TONED)
            g.move(-54f, -115f)
            g.move(-108f, -229f)
            g.end(end)
            assertEquals("end=$end", RecyclerView.SCROLL_STATE_IDLE, g.r.scrollState)
            g.down()
            g.move(-100f, 0f)
            g.move(-300f, 0f)
            assertEquals("end=$end: the next swipe must drag the page", RecyclerView.SCROLL_STATE_DRAGGING, g.r.scrollState)
            g.end(android.view.MotionEvent.ACTION_CANCEL)
            activity.setContentView(View(activity))
            idle()
        }
        val g = longPressOn(PLAIN)
        g.move(-100f, 0f)
        g.move(-300f, 0f)
        assertEquals("a popup without tones must not hold the pager", RecyclerView.SCROLL_STATE_DRAGGING, g.r.scrollState)
        g.end(android.view.MotionEvent.ACTION_CANCEL)
    }

    /**
     * One finger on People's first page (position 3): DOWN on [emoji]'s key, then the page's long-press request.
     * The page's own touch handling is stood in for, so only that request keeps the pager out.
     */
    private fun longPressOn(emoji: String): Finger {
        val shared = EmojibaseDataProvider.getSharedLoaded(EmojiTestEnv.app)
        EmojiTestEnv.field(EmojibaseDataProvider::class.java, "skinsByEmoji")
            .set(shared, mapOf(TONED to listOf("$TONED-1", "$TONED-2")))
        val v = openOn(2)
        val r = v.pager().recycler()
        val page = v.bound().getValue(3)
        page.setOnTouchListener { _, _ -> true }
        val key = page.keyboard!!.keys.first { it.keySpecOutputText == emoji }
        assertEquals("$emoji has tones", if (emoji == TONED) 3 else 1, key.moreKeys.size)
        val frame = page.parent as View
        val f = Finger(r, frame.left + page.left + key.x + key.width / 2f, frame.top + page.top + key.y + key.height / 2f)
        f.down()
        page.holdPagerForTones(key)
        return f
    }

    private class Finger(val r: RecyclerView, val x: Float, val y: Float) {
        var t = android.os.SystemClock.uptimeMillis()
        var downTime = t
        private var lastX = x
        private var lastY = y
        private fun send(action: Int, ex: Float, ey: Float) {
            t += 50
            if (action == android.view.MotionEvent.ACTION_DOWN) downTime = t
            lastX = ex; lastY = ey
            android.view.MotionEvent.obtain(downTime, t, action, ex, ey, 0).also { r.dispatchTouchEvent(it); it.recycle() }
        }
        fun down() = send(android.view.MotionEvent.ACTION_DOWN, x, y)
        fun move(dx: Float, dy: Float) = send(android.view.MotionEvent.ACTION_MOVE, x + dx, y + dy)
        fun end(action: Int) = send(action, lastX, lastY)
    }

    /**
     * A pick elsewhere shows on a laid-out Recents page at once; a pick on Recents waits until the board
     * leaves it.
     */
    @Test
    fun recentsRefreshOnPickOutsideRecentsAndOnLeavingRecents() {
        EmojiTestEnv.prefs().edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0")).commit()
        val v = openOn(1) // Smileys at 1: Recents (0) is laid out beside it
        v.onEmojiKeyReleased(v.bound().getValue(1).keyboard!!.keys.first { it.keySpecOutputText == "s3" })
        assertEquals(listOf("s3", "a0"), outputs(v.bound().getValue(0)))

        v.select(0)
        v.onEmojiKeyReleased(v.bound().getValue(0).keyboard!!.keys.first { it.keySpecOutputText == "a0" })
        assertEquals("queued while on Recents", listOf("s3", "a0"), outputs(v.bound().getValue(0)))
        v.select(1)
        assertEquals(listOf("a0", "s3"), outputs(v.bound().getValue(0)))
    }

    /** A drag starting releases the current page's pressed keys. */
    @Test
    fun aDragStartReleasesTheCurrentPagesKeys() {
        val v = openOn(2)
        val key = v.bound().getValue(3).keyboard!!.keys.first()
        key.onPressed()
        assertTrue(key.isPressed)
        v.onPageScrollStateChanged(ViewPager2.SCROLL_STATE_DRAGGING)
        assertTrue(!key.isPressed)
    }

    /** The board is layoutDirection="locale"; paging must stay left to right in RTL. */
    @Test
    fun pagingStaysLeftToRightInAnRtlLayout() {
        val v = openOn(2)
        v.layoutDirection = View.LAYOUT_DIRECTION_RTL
        v.requestLayout()
        idle()
        assertEquals(View.LAYOUT_DIRECTION_LTR, v.pager().layoutDirection)
        val r = v.pager().recycler()
        val left = (0 until r.childCount).associate { r.getChildAdapterPosition(r.getChildAt(it)) to r.getChildAt(it).left }
        assertTrue(left.toString(), left.getValue(2) < left.getValue(3) && left.getValue(3) < left.getValue(4))
        assertEquals(0, left.getValue(3))
    }

    @Test
    fun theOverlayPagerIsConfiguredTheSameWay() {
        EmojiTestEnv.prefs().edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0")).commit()
        val v = openOn(0)
        val overlay = findOverlayPager(v)
        assertEquals(EmojiPagerAdapter.OFFSCREEN_PAGES, overlay.offscreenPageLimit)
        assertEquals(View.LAYOUT_DIRECTION_LTR, overlay.layoutDirection)
        val recycler = EmojiTestEnv.field(RecyclerView::class.java, "mRecycler").get(overlay.recycler())
        assertEquals(0, EmojiTestEnv.field(RecyclerView.Recycler::class.java, "mRequestedCacheMax").getInt(recycler))
        assertNull(overlay.recycler().itemAnimator)
        assertEquals(EmojiPagerAdapter.SPARE_PAGE_VIEWS, maxSpares(overlay.recycler().recycledViewPool))
        assertEquals(listOf("a0"), outputs(overlay.pageViews().single()))
    }

    private fun maxSpares(pool: RecyclerView.RecycledViewPool): Int {
        val scrap = (EmojiTestEnv.field(RecyclerView.RecycledViewPool::class.java, "mScrap").get(pool) as android.util.SparseArray<*>).get(0)
        return EmojiTestEnv.field(scrap.javaClass, "mMaxScrap").getInt(scrap)
    }

    private fun findOverlayPager(v: EmojiPalettesView): ViewPager2 =
        read(v.findViewById<EmojiSearchOverlayView>(R.id.emoji_search_overlay), "viewPager") as ViewPager2
}
