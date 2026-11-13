package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import dev.bbkb.ime.R
import dev.bbkb.ime.core.ime.ImeResourcesContext
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The category tab row as a Material `TabLayout`: inflates in the IME's context, stays in step with the pager
 * both ways without a feedback loop, never takes focus, and takes icon colours from [KeyboardColorManager].
 * Dataset: [EmojiTestEnv.seedStandard] (0 Recents | 1-2 Smileys | 3 Animals | 4 Food).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojiCategoryTabsTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seedStandard()
        EmojiTestEnv.prefs().edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        activity.setContentView(View(activity))
        idle()
        if (KeyboardColorManager.isInitialized) {
            KeyboardColorManager.setTheme(KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.LIGHT, false)
        }
        EmojiTestEnv.uninstall()
    }

    /** The context KeyboardSwitcher inflates the input view (and so this board's ViewStub) with. */
    private fun imeContext() =
        ContextThemeWrapper(ImeResourcesContext(EmojiTestEnv.app), R.style.KeyboardTheme_LXX)

    private fun inflateInImeContext(): EmojiPalettesView {
        val view = LayoutInflater.from(imeContext()).inflate(R.layout.emoji_palettes_view, null) as EmojiPalettesView
        EmojiTestEnv.field(EmojiPalettesView::class.java, "keyboardSwitcher")
            .set(view, Mockito.mock(KeyboardSwitcher::class.java))
        activity.setContentView(view)
        idle()
        return view
    }

    private fun EmojiPalettesView.tabs(): TabLayout = findViewById(R.id.emoji_category_tabhost)
    private fun EmojiPalettesView.pager(): ViewPager2 = findViewById(R.id.emoji_keyboard_pager)

    private fun EmojiPalettesView.select(item: Int) {
        pager().setCurrentItem(item, false)
        idle()
    }

    private fun EmojiPalettesView.tap(tab: Int) {
        tabs().getTabAt(tab)!!.view.performClick()
        idle()
    }

    /** Counts the tab selections the row dispatches, from any cause. */
    private fun EmojiPalettesView.countSelections(): IntArray {
        val count = IntArray(1)
        tabs().addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { count[0]++ }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        return count
    }

    /** The IME's context has no AppCompat theme: the layout's android:theme is what makes this work. */
    @Test
    fun theTabRowInflatesInTheImeContext() {
        // Control: a TabLayout built straight in that context is refused, so the inflation below
        // proves the layout supplies the theme rather than the test environment.
        try {
            TabLayout(imeContext())
            fail("expected TabLayout to require an AppCompat theme in the IME context")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("Theme.AppCompat"))
        }

        val v = inflateInImeContext()
        val tabs = v.tabs()
        assertEquals(4, tabs.tabCount)
        assertEquals(listOf(0, 1, 3, 4), (0 until tabs.tabCount).map { tabs.getTabAt(it)!!.tag })
        assertEquals(1, tabs.selectedTabPosition)
        assertEquals(TabLayout.MODE_FIXED, tabs.tabMode)
        assertEquals(TabLayout.GRAVITY_FILL, tabs.tabGravity)
    }

    /** Equal full-height tabs, no padding, icon at its own size. */
    @Test
    fun tabsShareTheRowEquallyWithTheIconAtItsIntrinsicSize() {
        val v = inflateInImeContext()
        val tabs = v.tabs()
        assertTrue(tabs.width > 0)
        val iconPx = (24 * activity.resources.displayMetrics.density).toInt()
        for (i in 0 until tabs.tabCount) {
            val tabView = tabs.getTabAt(i)!!.view
            assertEquals(tabs.height, tabView.height)
            assertTrue("tab $i width ${tabView.width}", Math.abs(tabView.width - tabs.width / tabs.tabCount) <= 1)
            assertEquals(0, tabView.paddingLeft + tabView.paddingRight + tabView.paddingTop + tabView.paddingBottom)
            val icon = tabs.getTabAt(i)!!.customView as ImageView
            assertEquals(ImageView.ScaleType.CENTER, icon.scaleType)
            assertEquals(tabView.width, icon.width)
            assertNotNull(icon.drawable)
            assertEquals(iconPx, icon.drawable.intrinsicWidth)
            assertEquals(null, icon.contentDescription)
        }
    }

    @Test
    fun neitherTheRowNorAnyTabCanTakeFocus() {
        val v = inflateInImeContext()
        val tabs = v.tabs()
        assertFalse(tabs.isFocusable)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, tabs.descendantFocusability)
        for (i in 0 until tabs.tabCount) {
            val tabView = tabs.getTabAt(i)!!.view
            assertFalse(tabView.isFocusable)
            assertFalse(tabView.requestFocus())
            assertFalse(tabView.isSoundEffectsEnabled)
        }
        assertFalse(tabs.requestFocus())
        assertFalse(tabs.hasFocus())
        // Still tappable.
        v.tap(2)
        assertEquals(2, tabs.selectedTabPosition)
    }

    @Test
    fun tappingATabJumpsThePagerToThatCategorysFirstPage() {
        val v = inflateInImeContext()
        v.select(2) // Smileys, second page
        v.tap(2) // Animals
        assertEquals(3, v.pager().currentItem)
        v.tap(1) // back to Smileys: its first page, not the page that was left
        assertEquals(1, v.pager().currentItem)
        assertEquals(1, EmojiTestEnv.prefs().getInt(LAST_CATEGORY_KEY, -1))
    }

    /**
     * Paging into another category selects its tab exactly once, without moving the pager back to that
     * category's first page (the backwards case lands on Smileys' last page).
     */
    @Test
    fun pagingAcrossACategoryBoundarySelectsTheTabWithoutAFeedbackLoop() {
        val v = inflateInImeContext()
        val selections = v.countSelections()
        var pageSelections = 0
        v.pager().registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { pageSelections++ }
        })

        v.select(3) // Smileys -> Animals
        assertEquals(2, v.tabs().selectedTabPosition)
        assertEquals(3, v.pager().currentItem)
        assertEquals(1, selections[0])
        assertEquals(1, pageSelections)

        v.select(2) // Animals -> Smileys, its second page
        assertEquals(1, v.tabs().selectedTabPosition)
        assertEquals(2, v.pager().currentItem)
        assertEquals(2, selections[0])
        assertEquals(2, pageSelections)

        v.select(1) // within Smileys: no tab change
        assertEquals(2, selections[0])
        assertEquals(1, v.tabs().selectedTabPosition)
    }

    @Test
    fun iconTintFollowsSelectionAndTheColourManager() {
        KeyboardColorManager.init(EmojiTestEnv.app, KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.LIGHT, false)
        val v = inflateInImeContext()
        val tabs = v.tabs()
        val selected = intArrayOf(android.R.attr.state_selected)

        fun check() {
            val tint = tabs.tabIconTint!!
            assertEquals(KeyboardColorManager.iconColor, tint.getColorForState(selected, 0))
            assertEquals(KeyboardColorManager.getIconColor(KeyboardColorManager.ALPHA_SECONDARY), tint.defaultColor)
            for (i in 0 until tabs.tabCount) {
                val icon = tabs.getTabAt(i)!!.customView as ImageView
                assertEquals(i == tabs.selectedTabPosition, icon.isSelected)
            }
        }
        check()
        v.tap(3)
        check()

        val light = KeyboardColorManager.iconColor
        KeyboardColorManager.setTheme(KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.DARK, false)
        assertNotEquals("the dark palette has another icon colour", light, KeyboardColorManager.iconColor)
        check() // re-tinted through the colour observer
    }
}
