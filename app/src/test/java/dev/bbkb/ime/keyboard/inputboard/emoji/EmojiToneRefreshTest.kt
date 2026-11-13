package dev.bbkb.ime.keyboard.inputboard.emoji

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface
import dev.bbkb.ime.keyboard.internal.KeyDetector
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.LAST_CATEGORY_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.idle
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.pageViews
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.read
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.recycler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tone picked from the long-press popup recolours that emoji on the open board at once (category page and
 * Recents overlay), and a tap then inserts it. Only that emoji's keys are rebuilt.
 * Dataset: 0 Recents | 1 Smileys (3) | 2 People (3, the first with tones).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojiToneRefreshTest {

    private companion object {
        const val WAVE = "👋"
        const val WAVE_MEDIUM = "👋🏽"
        const val WAVE_DARK = "👋🏿"
        const val PEOPLE_START = 2
    }

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seed(
            linkedMapOf(
                EmojiCategory.SMILEYS_EMOTION to (0 until 3).map { EmojiTestEnv.emoji("s$it", 0, "smile $it") },
                EmojiCategory.PEOPLE_BODY to listOf(WAVE, "p1", "p2").map { EmojiTestEnv.emoji(it, 1, "person $it") },
            )
        )
        EmojiTestEnv.field(EmojibaseDataProvider::class.java, "skinsByEmoji")
            .set(EmojibaseDataProvider.getSharedLoaded(EmojiTestEnv.app), mapOf(WAVE to listOf(WAVE_MEDIUM, WAVE_DARK)))
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

    private fun EmojiPalettesView.manager(): EmojiCategoryManager = read(this, "categoryManager") as EmojiCategoryManager

    private fun EmojiPalettesView.page(position: Int): EmojiPageKeyboardView {
        val r = pager().recycler()
        val child = (0 until r.childCount).map { r.getChildAt(it) }.first { r.getChildAdapterPosition(it) == position }
        return (child as ViewGroup).getChildAt(0) as EmojiPageKeyboardView
    }

    private fun EmojiPalettesView.overlayPage(): EmojiPageKeyboardView {
        val overlay = findViewById<EmojiSearchOverlayView>(R.id.emoji_search_overlay)
        return (read(overlay, "viewPager") as ViewPager2).pageViews().single()
    }

    /** What the long-press popup does when a variant is picked from it. */
    private fun pickFromPopup(board: EmojiPalettesView, variant: String) {
        EmojiPageKeyboardView.ToneRecordingListener(KeyboardActionListenerInterface.EMPTY, EmojiTestEnv.app, board)
            .onTextInput(variant, 0L)
        idle()
    }

    private fun hit(page: EmojiPageKeyboardView, key: Key): Key? =
        (read(page, "keyboardActionListener") as KeyDetector)
            .detectHitKey(key.x + key.width / 2, key.y + key.height / 2)

    @Test
    fun aToneFromThePopupRecoloursTheCategoryKeyAtOnce() {
        val v = openOn(EmojiCategory.PEOPLE_BODY.id)
        val page = v.page(PEOPLE_START)
        val keyboard = page.keyboard!!
        val before = keyboard.keys
        assertEquals(WAVE, before[0].keySpecOutputText)

        pickFromPopup(v, WAVE_MEDIUM)

        assertSame("the page is kept", keyboard, page.keyboard)
        assertSame(keyboard, v.manager().getKeyboardAtPosition(PEOPLE_START))
        val after = page.keyboard!!.keys
        assertEquals(WAVE_MEDIUM, after[0].keySpecOutputText)
        assertEquals(WAVE_MEDIUM, after[0].label)
        assertEquals("the popup still offers every tone", listOf(WAVE, WAVE_MEDIUM, WAVE_DARK), after[0].moreKeys.map { it.label })
        assertEquals("same slot", before[0].hitBox, after[0].hitBox)
        assertSame("other keys untouched", before[1], after[1])
        assertSame("a tap on the cell hits the rebuilt key", after[0], hit(page, after[0]))

        // A plain tap on it inserts the tone and leaves it remembered.
        assertEquals(-4, after[0].code)
        v.onEmojiKeyReleased(after[0])
        assertEquals(WAVE_MEDIUM, EmojiToneMemory.preferred(EmojiTestEnv.prefs(), WAVE))

        // Picking the plain emoji from the popup forgets the tone, also at once.
        pickFromPopup(v, WAVE)
        assertEquals(WAVE, page.keyboard!!.keys[0].keySpecOutputText)
    }

    /** The pages are current with the memory again, so the next open does not throw them away. */
    @Test
    fun theNextOpenKeepsTheRetonedPages() {
        val v = openOn(EmojiCategory.PEOPLE_BODY.id)
        val keyboard = v.page(PEOPLE_START).keyboard
        pickFromPopup(v, WAVE_DARK)
        v.detachPagerAdapter()
        v.attachPagerAdapter()
        idle()
        assertSame(keyboard, v.manager().getKeyboardAtPosition(PEOPLE_START))
        assertEquals(WAVE_DARK, v.page(PEOPLE_START).keyboard!!.keys[0].keySpecOutputText)
    }

    @Test
    fun aToneFromThePopupRecoloursTheRecentsOverlayAtOnce() {
        EmojiTestEnv.prefs().edit().putString(RECENTS_KEY, EmojiTestEnv.padded(WAVE, "s1")).commit()
        val v = openOn(0)
        val overlayPage = v.overlayPage()
        val keyboard = overlayPage.keyboard!!
        assertEquals(WAVE, keyboard.keys[0].keySpecOutputText)

        pickFromPopup(v, WAVE_MEDIUM)

        assertSame(keyboard, overlayPage.keyboard)
        assertEquals(WAVE_MEDIUM, overlayPage.keyboard!!.keys[0].keySpecOutputText)
        assertEquals("s1", overlayPage.keyboard!!.keys[1].keySpecOutputText)
        assertSame(overlayPage.keyboard!!.keys[0], hit(overlayPage, overlayPage.keyboard!!.keys[0]))
    }

    /** A pick that changes nothing rebuilds nothing. */
    @Test
    fun anUnchangedMemoryRebuildsNothing() {
        val v = openOn(EmojiCategory.PEOPLE_BODY.id)
        pickFromPopup(v, WAVE_MEDIUM)
        val key = v.page(PEOPLE_START).keyboard!!.keys[0]
        pickFromPopup(v, WAVE_MEDIUM)
        assertSame(key, v.page(PEOPLE_START).keyboard!!.keys[0])
        pickFromPopup(v, WAVE_DARK)
        assertNotSame(key, v.page(PEOPLE_START).keyboard!!.keys[0])
    }

    /** A pick from the popup is a pick: it lands in Recents like a tapped key. */
    @Test
    fun aPopupPickIsAddedToRecents() {
        val v = openOn(EmojiCategory.PEOPLE_BODY.id)
        // The Recents grid pads itself with empty keys: only the filled ones count.
        fun recents() = v.manager().recentsKeyboard.keys.map { it.keySpecOutputText }.filter { !it.isNullOrEmpty() }
        assertEquals(emptyList<String>(), recents())

        pickFromPopup(v, WAVE_MEDIUM)

        assertEquals(listOf(WAVE_MEDIUM), recents())
        assertEquals(listOf(WAVE_MEDIUM), EmojiRecents.read(EmojiTestEnv.prefs(), Int.MAX_VALUE))
    }
}
