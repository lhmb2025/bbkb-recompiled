package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.pageViews
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.recycler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
 * Audit P-16: the emoji board kept nothing across opens. Every close dropped every built page, so
 * each open rebuilt the whole current category, and every page instantiation inflated a page view
 * with its own more-keys KeyboardView. Also the Recents staleness that came with the page drop.
 *
 * The open/close cycle is the one `KeyboardSwitcher` runs: close = GONE, `clearQuery`,
 * `detachPagerAdapter`; open = `attachPagerAdapter`, VISIBLE.
 *
 * Counts on the dataset below (current category People, five pages), measured on the JVM before
 * the change: per open 3 page views and 3 more-keys panels inflated and 8 pages built (Recents,
 * Smileys and People rebuilt), about 5.7 MB allocated; per swipe 1 page view and 1 panel, about
 * 1 MB. After: per open nothing inflated or built, about 20 to 30 KB; one spare page view over a
 * dozen swipes, about 0.1 MB a swipe; the popup only on the first long-press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp"
)
class EmojiBoardRecyclingTest {

    private lateinit var activity: Activity
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        seedLarge()
        prefs = EmojiTestEnv.prefs()
        prefs.edit().clear().commit()
        EmojiToneMemory.clear(prefs)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.setContentView(View(activity))
        idle()
        EmojiTestEnv.uninstall()
    }

    /**
     * Positions 0 Recents | 1-2 Smileys (41) | 3-7 People (200, five tones each) | 8 Animals (3).
     */
    private fun seedLarge() {
        EmojiTestEnv.seed(
            linkedMapOf(
                EmojiCategory.SMILEYS_EMOTION to (0 until 41).map { EmojiTestEnv.emoji("s$it", 0, "smile $it") },
                EmojiCategory.PEOPLE_BODY to (0 until 200).map { EmojiTestEnv.emoji("p$it", 1, "person $it") },
                EmojiCategory.ANIMALS_NATURE to (0 until 3).map { EmojiTestEnv.emoji("a$it", 3, "cat $it") },
            )
        )
        EmojiTestEnv.field(EmojibaseDataProvider::class.java, "skinsByEmoji")
            .set(EmojibaseDataProvider.peekShared()!!, (0 until 200).associate { i -> "p$i" to TONES.map { "p$i$it" } })
    }

    // ------------------------------------------------------------------ harness

    private fun EmojiPalettesView.pager(): ViewPager2 = findViewById(R.id.emoji_keyboard_pager)

    private fun EmojiPalettesView.overlay(): EmojiSearchOverlayView = findViewById(R.id.emoji_search_overlay)

    private fun EmojiPalettesView.pageViews(): List<EmojiPageKeyboardView> = pager().pageViews()

    /** The page view bound to pager [position]. */
    private fun EmojiPalettesView.pageAt(position: Int): EmojiPageKeyboardView {
        val adapter = read(pager().adapter!!, "mPageViews") as android.util.SparseArray<*>
        return adapter.get(position) as EmojiPageKeyboardView
    }

    private fun outputs(page: EmojiPageKeyboardView): List<String?> =
        page.keyboard!!.keys.filter { it.code != -21 }.map { it.keySpecOutputText }

    private fun read(target: Any, name: String): Any? = EmojiTestEnv.read(target, name)

    private fun manager(v: EmojiPalettesView): EmojiCategoryManager = read(v, "categoryManager") as EmojiCategoryManager

    private fun cachedPages(v: EmojiPalettesView): Collection<EmojiKeyboard> =
        (read(manager(v), "keyboardCache") as Map<*, *>).values.map { it as EmojiKeyboard }

    private fun panel(v: EmojiPalettesView): EmojiPageKeyboardView.SharedMoreKeysPanel =
        read(v, "moreKeysPanel") as EmojiPageKeyboardView.SharedMoreKeysPanel

    private fun close(v: EmojiPalettesView) {
        v.visibility = View.GONE
        v.clearQuery()
        v.detachPagerAdapter()
        idle()
    }

    private fun open(v: EmojiPalettesView) {
        v.attachPagerAdapter()
        v.visibility = View.VISIBLE
        idle()
    }

    private fun select(v: EmojiPalettesView, item: Int) {
        v.pager().setCurrentItem(item, false)
        idle()
    }

    private fun openOn(categoryId: Int): EmojiPalettesView {
        prefs.edit().putInt(LAST_CATEGORY_KEY, categoryId).commit()
        return EmojiTestEnv.inflatePalettes(activity)
    }

    private val seenViews: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private val seenPages: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    /** Page views and built pages not seen before this call. */
    private fun fresh(v: EmojiPalettesView): Pair<Int, Int> =
        v.pageViews().count { seenViews.add(it) } to cachedPages(v).count { seenPages.add(it) }

    /**
     * Bytes this thread has allocated so far, from HotSpot's ThreadMXBean (by reflection: the
     * Android compile classpath has no java.lang.management). A JVM figure, not ART's.
     */
    private fun allocated(): Long {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        return Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
    }

    // ------------------------------------------------------------------ allocation per open and swipe

    @Test
    fun reopeningInflatesNothingAndBuildsNoPage() {
        val v = openOn(2)
        assertEquals(3, v.pager().currentItem)
        fresh(v)
        repeat(2) { close(v); open(v) } // let the free list fill
        fresh(v)
        val cycles = 5
        var views = 0
        var pages = 0
        val before = allocated()
        repeat(cycles) {
            close(v)
            open(v)
            val (a, b) = fresh(v)
            views += a
            pages += b
        }
        val bytesPerOpen = (allocated() - before) / cycles
        println("P-16 per open: page views inflated=${views.toDouble() / cycles} pages built=${pages.toDouble() / cycles} JVM bytes=$bytesPerOpen")
        assertEquals("page views inflated over $cycles opens", 0, views)
        assertEquals("pages built over $cycles opens", 0, pages)
        assertEquals(3, v.pager().currentItem)
        assertEquals(3, v.pageViews().size)
        assertNull("no long-press, no popup view", panel(v).peek())
        assertTrue(v.pageViews().all { read(it, "moreKeysLayoutView") == null })
    }

    @Test
    fun swipingRebindsPageViewsInsteadOfInflating() {
        val v = openOn(2)
        fresh(v)
        val path = listOf(4, 5, 6, 7, 6, 5, 4, 3, 2, 1, 2, 3)
        var views = 0
        val before = allocated()
        for (item in path) {
            select(v, item)
            views += fresh(v).first
            assertEquals("p${(item - 3) * 40}".takeIf { item in 3..7 }, outputs(v.pageAt(item)).first().takeIf { item in 3..7 })
        }
        println("P-16 per swipe: page views inflated=${views.toDouble() / path.size} JVM bytes=${(allocated() - before) / path.size}")
        // One spare: the pager lays out the incoming page before it recycles the outgoing one.
        assertTrue("page views inflated over ${path.size} swipes: $views", views <= 1)
        assertEquals(3, v.pageViews().size)
        assertNull(panel(v).peek())
    }

    @Test
    fun aTabJumpAndBackStillShowsTheRightPages() {
        val v = openOn(2)
        select(v, 8)
        assertEquals(listOf("a0", "a1", "a2"), outputs(v.pageAt(8)))
        select(v, 1)
        assertEquals((0 until 40).map { "s$it" }, outputs(v.pageAt(1)))
        assertEquals(listOf("s40"), outputs(v.pageAt(2)))
        select(v, 3)
        assertEquals("p0", outputs(v.pageAt(3)).first())
        assertEquals(3, v.pageViews().size)
    }

    // ------------------------------------------------------------------ the leave path while closed

    /** KeyboardSwitcher runs the leave path on every main-keyboard setKeyboard: closed, it is a no-op. */
    @Test
    fun detachingAClosedBoardAgainChangesNothing() {
        val v = openOn(2)
        close(v)
        val kept = cachedPages(v).toList()
        assertTrue(kept.isNotEmpty())
        repeat(3) { v.detachPagerAdapter() }
        idle()
        assertNull(v.pager().adapter)
        assertEquals(kept.size, cachedPages(v).size)
        assertTrue(kept.all { k -> cachedPages(v).any { it === k } })
        assertFalse(prefs.contains(RECENTS_KEY))
    }

    /** A closed board keeps only the pages around the one it reopens on (plus Recents). */
    @Test
    fun closingKeepsOnlyTheCategoriesAroundTheCurrentPage() {
        val v = openOn(2)
        select(v, 8) // Animals: People (7) and Animals are around it
        select(v, 1) // Smileys: Recents (0) and Smileys
        close(v)
        val categories = (read(manager(v), "keyboardCache") as Map<*, *>).keys.map { ((it as Long) ushr 32).toInt() }.toSet()
        assertEquals(setOf(0, 1), categories)
    }

    // ------------------------------------------------------------------ skin tones

    @Test
    fun aToneRememberedWhileClosedShowsOnTheKeysAfterReopening() {
        val v = openOn(2)
        assertEquals("p0", outputs(v.pageAt(3)).first())
        val before = v.pageAt(3).keyboard
        close(v)
        EmojiToneMemory.onEmojiPicked(prefs, "p0" + TONES[2])
        open(v)
        assertEquals("p0" + TONES[2], outputs(v.pageAt(3)).first())
        assertNotSame(before, v.pageAt(3).keyboard)
    }

    @Test
    fun withoutAToneChangeReopeningKeepsTheSamePages() {
        val v = openOn(2)
        val before = v.pageAt(3).keyboard
        close(v)
        EmojiToneMemory.onEmojiPicked(prefs, "p1") // nothing remembered: no change
        open(v)
        assertSame(before, v.pageAt(3).keyboard)
    }

    @Test
    fun aTonedPickOnTheBoardShowsOnTheKeysFromTheNextOpen() {
        val v = openOn(2)
        val key = v.pageAt(3).keyboard!!.keys.first()
        // A toned variant picked from the board (here: from a toned key) is remembered for its base.
        v.onEmojiKeyReleased(dev.bbkb.ime.keyboard.Key(key, dev.bbkb.ime.keyboard.internal.MoreKeySpec("p0" + TONES[4], 0, -4, "p0" + TONES[4]), arrayOf()))
        assertEquals("unchanged while open", "p0", outputs(v.pageAt(3)).first())
        close(v)
        open(v)
        assertEquals("p0" + TONES[4], outputs(v.pageAt(3)).first())
    }

    // ------------------------------------------------------------------ recents staleness

    /**
     * Was: the adapter kept the Recents grid it was built with, the board dropped and rebuilt the
     * grid on reopen, and picks then went to the old grid while the Recents page showed the new one.
     */
    @Test
    fun picksAfterAReopenShowOnTheRecentsPage() {
        prefs.edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0")).commit()
        val v = openOn(1) // Smileys at 1: the Recents page (0) is bound next to it
        close(v)
        open(v)
        v.onEmojiKeyReleased(v.pageAt(1).keyboard!!.keys.first { it.keySpecOutputText == "s3" })
        assertEquals(listOf("s3", "a0"), outputs(v.pageAt(0)))
        select(v, 0)
        assertEquals(listOf("s3", "a0"), outputs(v.pageAt(0)))
        assertEquals(EmojiTestEnv.padded("s3", "a0"), prefs.getString(RECENTS_KEY, null))
    }

    /** Picks on Recents wait until the board leaves it; reopening there must show them. */
    @Test
    fun picksMadeOnRecentsShowInTheOverlayAfterAReopen() {
        prefs.edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0", "a1")).commit()
        val v = openOn(0)
        assertTrue(v.overlay().isOverlayVisible)
        val overlayKey = (read(v.overlay(), "currentKeyboards") as List<*>).map { it as EmojiKeyboard }
            .single().keys.first { it.keySpecOutputText == "a1" }
        v.onEmojiKeyReleased(overlayKey)
        close(v)
        open(v)
        val shown = (read(v.overlay(), "currentKeyboards") as List<*>).map { it as EmojiKeyboard }.single()
        assertEquals(listOf("a1", "a0"), shown.keys.filter { it.code != -21 }.map { it.keySpecOutputText })
        assertEquals(listOf("a1", "a0"), outputs(v.pageAt(0)))
    }

    /** Recents rewritten from outside the board (a backup restore, a reset) show on the next open. */
    @Test
    fun recentsRewrittenWhileClosedShowAfterReopening() {
        prefs.edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0")).commit()
        val v = openOn(1)
        assertEquals(listOf("a0"), outputs(v.pageAt(0)))
        close(v)
        prefs.edit().putString(RECENTS_KEY, "[\"a2\"]").commit()
        open(v)
        assertEquals(listOf("a2"), outputs(v.pageAt(0)))
        prefs.edit().remove(RECENTS_KEY).commit()
        close(v)
        open(v)
        assertEquals(emptyList<String>(), outputs(v.pageAt(0)))
    }

    // ------------------------------------------------------------------ the overlay

    @Test
    fun theOverlayRebindsItsPageViews() {
        prefs.edit().putString(RECENTS_KEY, EmojiTestEnv.padded("a0")).commit()
        val v = openOn(0)
        val overlayPager = read(v.overlay(), "viewPager") as ViewPager2
        val first = overlayPager.pageViews().single()
        repeat(3) { close(v); open(v) }
        assertTrue(v.overlay().isOverlayVisible)
        assertEquals(1, overlayPager.recycler().childCount)
        assertSame(first, overlayPager.pageViews().single())
    }

    companion object {
        private val TONES = listOf("🏻", "🏼", "🏽", "🏾", "🏿")
    }
}
