package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.pageViews
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.read
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.recycler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A swipe on the Recents tab moves on to the first category exactly like a swipe between two
 * categories (owner report 2026-11-06: it never did, because Recents is drawn in an overlay over the
 * category pager and the swipe never reached the pager). The overlay hands the finger to the pager,
 * slides with the Recents page it covers, and the tab strip, page dots and current category follow
 * the pager as for any other page change. The swipe back from the first category is unchanged, and
 * a typed query's results are never paged away.
 *
 * Dataset: positions 0 Recents | 1-2 Smileys (41) | 3 Animals (3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojiRecentsSwipeTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seed(
            linkedMapOf(
                EmojiCategory.SMILEYS_EMOTION to (0 until 41).map { EmojiTestEnv.emoji("s$it", 0, "smile $it") },
                EmojiCategory.ANIMALS_NATURE to (0 until 3).map { EmojiTestEnv.emoji("a$it", 3, "cat $it") },
            )
        )
        EmojiTestEnv.prefs().edit().clear().commit()
        EmojiToneMemory.clear(EmojiTestEnv.prefs())
        EmojiTestEnv.prefs().edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0", "s3")).commit()
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
        return EmojiTestEnv.inflatePalettes(activity).also { v ->
            // The pages' own touch handling needs the IME's pointer trackers; stand in for it.
            v.overlay().pager().pageViews().forEach { it.setOnTouchListener { _, _ -> true } }
            v.pager().pageViews().forEach { it.setOnTouchListener { _, _ -> true } }
        }
    }

    private fun EmojiPalettesView.pager(): ViewPager2 = findViewById(R.id.emoji_keyboard_pager)
    private fun EmojiPalettesView.overlay(): EmojiSearchOverlayView = findViewById(R.id.emoji_search_overlay)
    private fun EmojiSearchOverlayView.pager(): ViewPager2 = read(this, "viewPager") as ViewPager2
    private fun EmojiPalettesView.manager(): EmojiCategoryManager = read(this, "categoryManager") as EmojiCategoryManager
    private fun EmojiPalettesView.tabs(): TabLayout = findViewById(R.id.emoji_category_tabhost)
    private fun EmojiPalettesView.dots(): EmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view)

    private fun settle() {
        repeat(150) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    /** One finger on [target], right to left (negative [distance]) or left to right, in [steps] moves. */
    private class Swipe(val target: View, val distance: Float, val steps: Int = 12) {
        private val down = SystemClock.uptimeMillis()
        private var t = down
        private val x0 = if (distance < 0) target.width * 0.85f else target.width * 0.15f
        private val y = target.height / 2f

        private fun send(action: Int, x: Float) {
            MotionEvent.obtain(down, t, action, x, y, 0).also { target.dispatchTouchEvent(it); it.recycle() }
            t += 16
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }

        fun start() = send(MotionEvent.ACTION_DOWN, x0)
        fun moveTo(fraction: Float) = send(MotionEvent.ACTION_MOVE, x0 + distance * fraction)
        fun moveAll() { for (i in 1..steps) moveTo(i / steps.toFloat()) }
        fun end(action: Int = MotionEvent.ACTION_UP) = send(action, x0 + distance)
    }

    @Test
    fun aSwipeOnRecentsMovesOnToTheFirstCategory() {
        val v = openOn(0)
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(0, v.pager().currentItem)

        val swipe = Swipe(v.overlay(), -v.overlay().width * 0.7f)
        swipe.start()
        swipe.moveTo(0.5f)
        assertTrue("the overlay hands the finger to the category pager", v.overlay().isHandingOff)
        assertEquals(ViewPager2.SCROLL_STATE_DRAGGING, v.pager().scrollState)
        swipe.moveTo(0.6f)
        assertTrue("the overlay slides with the Recents page", v.overlay().translationX < 0f)
        swipe.moveAll()
        swipe.end()
        settle()

        assertEquals("first category's first page", 1, v.pager().currentItem)
        assertEquals(EmojiCategory.SMILEYS_EMOTION.id, v.manager().currentCategoryId)
        assertEquals(1, v.tabs().selectedTabPosition)
        assertEquals(0, v.manager().currentPageInCategory)
        assertFalse("the first category shows, not Recents", v.overlay().isOverlayVisible)
        assertEquals(0f, v.overlay().translationX)
        assertEquals("Smileys' two pages in the dots", 2, read(v.dots(), "pageCount"))
    }

    @Test
    fun aShortSwipeOnRecentsSettlesBackOnRecents() {
        val v = openOn(0)
        val swipe = Swipe(v.overlay(), -v.overlay().width * 0.15f, steps = 4)
        swipe.start()
        // Slowly, so it is no fling.
        for (i in 1..4) { swipe.moveTo(i / 4f); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)) }
        swipe.end()
        settle()
        assertEquals(0, v.pager().currentItem)
        assertEquals(0, v.manager().currentCategoryId)
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(0f, v.overlay().translationX)
    }

    /** A slow drag past half the width (no fling) still lands on the first category. */
    @Test
    fun aSlowLongDragOnRecentsLandsOnTheFirstCategory() {
        val v = openOn(0)
        val swipe = Swipe(v.overlay(), -v.overlay().width * 0.75f, steps = 8)
        swipe.start()
        for (i in 1..8) { swipe.moveTo(i / 8f); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
        swipe.end()
        settle()
        assertEquals(1, v.pager().currentItem)
        assertEquals(EmojiCategory.SMILEYS_EMOTION.id, v.manager().currentCategoryId)
    }

    /** The other way from the first category: back to Recents, as before. */
    @Test
    fun theSwipeBackFromTheFirstCategoryReturnsToRecents() {
        val v = openOn(0)
        Swipe(v.overlay(), -v.overlay().width * 0.7f).apply { start(); moveAll(); end() }
        settle()
        assertEquals(1, v.pager().currentItem)

        Swipe(v.pager().recycler(), v.pager().width * 0.7f).apply { start(); moveAll(); end() }
        settle()
        assertEquals(0, v.pager().currentItem)
        assertEquals(0, v.manager().currentCategoryId)
        assertEquals(0, v.tabs().selectedTabPosition)
        assertTrue("Recents shows in the overlay again", v.overlay().isOverlayVisible)
        assertEquals(0f, v.overlay().translationX)
    }

    /** A swipe toward the (absent) page before Recents and a vertical slide are not handed off. */
    @Test
    fun onlyAForwardSidewaysSwipeIsHandedOff() {
        val v = openOn(0)
        Swipe(v.overlay(), v.overlay().width * 0.7f).apply { start(); moveAll(); assertFalse(v.overlay().isHandingOff); end() }
        settle()
        assertEquals(0, v.pager().currentItem)
        assertTrue(v.overlay().isOverlayVisible)
    }

    /** The skin-tone popup's hold (EmojiPageKeyboardView.holdPagerForTones) keeps the finger on the popup. */
    @Test
    fun aHeldTonePopupIsNotHandedOff() {
        val v = openOn(0)
        val page = v.overlay().pager().pageViews().single()
        val swipe = Swipe(v.overlay(), -v.overlay().width * 0.7f)
        swipe.start()
        page.parent.requestDisallowInterceptTouchEvent(true)
        swipe.moveAll()
        assertFalse(v.overlay().isHandingOff)
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, v.pager().recycler().scrollState)
        swipe.end(MotionEvent.ACTION_CANCEL)
        settle()
        assertEquals(0, v.pager().currentItem)
    }

    /** A typed query's results are left where they are. */
    @Test
    fun typedResultsAreNotPagedAway() {
        val v = openOn(0)
        v.appendToQuery("c")
        Swipe(v.overlay(), -v.overlay().width * 0.7f).apply { start(); moveAll(); assertFalse(v.overlay().isHandingOff); end() }
        settle()
        assertEquals(0, v.pager().currentItem)
        assertEquals(0, v.manager().currentCategoryId)
        assertTrue(v.isSearchFieldShowing)
    }
}
