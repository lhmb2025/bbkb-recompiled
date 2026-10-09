package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The clipboard list's interaction model: a tap pastes; a long-press on the row and a tap on its
 * overflow button open the same menu (Pin/Unpin, Copy, Share, Show full text, Delete), whose
 * actions reach [ClipboardActionCallback]; pinned rows sit under a "Pinned" header above a
 * "Recent" one; link previews are bound by entry id.
 *
 * The menu is captured through [ClipboardAdapter.menuLauncher] instead of being shown: a popup
 * needs a window, which a bare RecyclerView under Robolectric does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardRowMenuTest {

    private lateinit var context: Context

    private val calls = mutableListOf<String>()
    private val shown = mutableListOf<PopupMenu>()
    private val fetched = mutableListOf<String>()

    private val callback = object : ClipboardActionCallback {
        override fun onPasteClip(entry: ClipEntry) { calls += "paste:${entry.text}" }
        override fun onCopyClip(entry: ClipEntry) { calls += "copy:${entry.text}" }
        override fun onPinClip(entry: ClipEntry, pin: Boolean) { calls += "pin:$pin:${entry.text}" }
        override fun onShareClip(entry: ClipEntry) { calls += "share:${entry.text}" }
        override fun onDeleteClip(entry: ClipEntry) { calls += "delete:${entry.text}" }
    }

    @Before
    fun setUp() {
        context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_BlackberryKeyboard_IME,
        )
    }

    private fun clip(id: Long, text: String, label: String? = null, pinned: Boolean = false) =
        ClipEntry(id, text, label, "text/plain", 1_000L, pinned, if (pinned) 2_000L else 0L)

    private class Board(val list: RecyclerView, val adapter: ClipboardAdapter) {
        fun relayout() {
            list.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            list.layout(0, 0, 1080, 2400)
        }

        fun row(position: Int) = list.findViewHolderForAdapterPosition(position) as ClipboardViewHolder

        fun header(position: Int) = list.findViewHolderForAdapterPosition(position)!!.itemView as TextView
    }

    private fun board(pinned: List<ClipEntry> = emptyList(), recent: List<ClipEntry>): Board {
        val provider = ClipboardWebImageProvider({ url ->
            fetched += url
            OpenGraphMetadata().apply {
                this.url = url
                parse(Jsoup.parse("""<html><head><meta property="og:title" content="Page title"></head></html>"""))
            }
        }, Dispatchers.Unconfined)
        val adapter = ClipboardAdapter(context, provider)
        adapter.setActionCallback(callback)
        adapter.menuLauncher = ClipboardAdapter.MenuLauncher { shown += it }
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        adapter.submitList(ClipboardItem.build(pinned, recent))
        return Board(list, adapter).also { it.relayout() }
    }

    private fun titles(menu: PopupMenu): List<String> =
        (0 until menu.menu.size()).map { menu.menu.getItem(it).title.toString() }

    private fun str(id: Int) = context.getString(id)

    // ── which actions a row offers ───────────────────────────────────────────

    @Test
    fun theActionsFollowTheRowsState() {
        val plain = clip(1, "x")
        assertEquals(
            listOf(ClipboardEntryMenu.ACTION_PIN, ClipboardEntryMenu.ACTION_COPY,
                ClipboardEntryMenu.ACTION_SHARE, ClipboardEntryMenu.ACTION_DELETE),
            ClipboardEntryMenu.actionsFor(plain, false, false),
        )
        assertEquals(
            "a cut-off row offers Show full text, before Delete",
            listOf(ClipboardEntryMenu.ACTION_PIN, ClipboardEntryMenu.ACTION_COPY,
                ClipboardEntryMenu.ACTION_SHARE, ClipboardEntryMenu.ACTION_SHOW_FULL_TEXT,
                ClipboardEntryMenu.ACTION_DELETE),
            ClipboardEntryMenu.actionsFor(plain, false, true),
        )
        assertEquals(
            "a pinned row offers Unpin instead",
            ClipboardEntryMenu.ACTION_UNPIN,
            ClipboardEntryMenu.actionsFor(clip(1, "x", pinned = true), false, false).first(),
        )
        assertEquals(
            "a masked password offers nothing that would keep or reveal it",
            listOf(ClipboardEntryMenu.ACTION_COPY, ClipboardEntryMenu.ACTION_DELETE),
            ClipboardEntryMenu.actionsFor(plain, true, true),
        )
    }

    // ── opening the menu ─────────────────────────────────────────────────────

    @Test
    fun aTapPastesAndOpensNoMenu() {
        val b = board(recent = listOf(clip(1, "hello")))

        b.row(0).foreground.performClick()

        assertEquals(listOf("paste:hello"), calls)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun aLongPressOpensTheRowMenu() {
        val b = board(recent = listOf(clip(1, "hello")))

        assertTrue("the long-press is consumed", b.row(0).foreground.performLongClick())

        assertEquals(1, shown.size)
        assertEquals(
            listOf(str(R.string.clipboard_menu_pin), str(R.string.clipboard_menu_copy),
                str(R.string.clipboard_menu_share), str(R.string.clipboard_menu_delete)),
            titles(shown.single()),
        )
        assertTrue("a long-press does not paste", calls.isEmpty())
    }

    @Test
    fun theOverflowButtonOpensTheSameMenu() {
        val b = board(recent = listOf(clip(1, "hello")))
        b.row(0).foreground.performLongClick()

        b.row(0).overflowButton.performClick()

        assertEquals(2, shown.size)
        assertEquals(titles(shown[0]), titles(shown[1]))
        assertTrue("the overflow button does not paste", calls.isEmpty())
    }

    @Test
    fun everyMenuActionReachesTheCallbackWithItsRow() {
        val b = board(recent = listOf(clip(1, "first"), clip(2, "second")))
        b.row(1).overflowButton.performClick()
        val menu = shown.single().menu

        for (action in listOf(ClipboardEntryMenu.ACTION_PIN, ClipboardEntryMenu.ACTION_COPY,
                ClipboardEntryMenu.ACTION_SHARE, ClipboardEntryMenu.ACTION_DELETE)) {
            assertTrue(menu.performIdentifierAction(action, 0))
        }

        assertEquals(listOf("pin:true:second", "copy:second", "share:second", "delete:second"), calls)
    }

    @Test
    fun aPinnedRowUnpins() {
        val b = board(pinned = listOf(clip(1, "kept", pinned = true)), recent = emptyList())
        b.row(1).overflowButton.performClick()

        assertEquals(str(R.string.clipboard_menu_unpin), titles(shown.single()).first())
        shown.single().menu.performIdentifierAction(ClipboardEntryMenu.ACTION_UNPIN, 0)

        assertEquals(listOf("pin:false:kept"), calls)
    }

    @Test
    fun aPasswordKeeperRowIsMaskedAndItsMenuOffersOnlyCopyAndDelete() {
        val b = board(recent = listOf(clip(1, "hunter2", label = str(R.string.clip_password_keeper_add))))

        assertEquals(str(R.string.clip_password_keeper_mask), b.row(0).dataTextView.text.toString())
        assertEquals(str(R.string.clip_password_keeper_mask), b.row(0).itemView.contentDescription)
        b.row(0).foreground.performLongClick()

        assertEquals(
            listOf(str(R.string.clipboard_menu_copy), str(R.string.clipboard_menu_delete)),
            titles(shown.single()),
        )
    }

    @Test
    fun showFullTextExpandsTheRowUntilTheBoardReopens() {
        val long = (1..60).joinToString(" ") { "word$it" }
        val b = board(recent = listOf(clip(1, long)))
        assertEquals(3, b.row(0).dataTextView.maxLines)

        b.adapter.onMenuAction(clip(1, long), ClipboardEntryMenu.ACTION_SHOW_FULL_TEXT)
        b.relayout()
        assertEquals(Int.MAX_VALUE, b.row(0).dataTextView.maxLines)
        assertTrue("expanding is the list's own business", calls.isEmpty())

        b.adapter.onBoardShown(false)
        b.relayout()
        assertEquals(3, b.row(0).dataTextView.maxLines)
    }

    // ── sections, ids, glyphs ────────────────────────────────────────────────

    @Test
    fun pinnedRowsSitUnderAPinnedHeaderAboveRecentOnes() {
        val b = board(
            pinned = listOf(clip(5, "pinned", pinned = true)),
            recent = listOf(clip(6, "recent")),
        )

        assertEquals(4, b.adapter.itemCount)
        assertEquals(str(R.string.clipboard_section_pinned), b.header(0).text.toString())
        assertEquals("pinned", b.row(1).boundEntry!!.text)
        assertEquals(str(R.string.clipboard_section_recent), b.header(2).text.toString())
        assertEquals("recent", b.row(3).boundEntry!!.text)
        assertEquals(View.VISIBLE, b.row(1).pinGlyph.visibility)
        assertEquals(View.GONE, b.row(3).pinGlyph.visibility)
    }

    @Test
    fun withNothingPinnedThereAreNoHeaders() {
        val b = board(recent = listOf(clip(1, "a"), clip(2, "b")))

        assertEquals(2, b.adapter.itemCount)
        assertTrue((0 until 2).all { b.adapter.getItemViewType(it) == ClipboardItem.TYPE_CLIP })
    }

    @Test
    fun rowsHaveStableIdsThatAreTheirEntryIds() {
        val b = board(pinned = listOf(clip(5, "p", pinned = true)), recent = listOf(clip(6, "r")))

        assertTrue(b.adapter.hasStableIds())
        assertEquals(
            listOf(ClipboardItem.HEADER_ID_PINNED, 5L, ClipboardItem.HEADER_ID_RECENT, 6L),
            (0 until b.adapter.itemCount).map { b.adapter.getItemId(it) },
        )
    }

    // ── link previews ────────────────────────────────────────────────────────

    @Test
    fun noLinkIsFetchedWhilePreviewsAreOff() {
        board(recent = listOf(clip(1, "https://example.com/page")))

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aPreviewIsBoundToTheRowOfItsEntry() {
        val b = board(recent = listOf(clip(1, "plain text"), clip(2, "https://example.com/page")))

        b.adapter.onBoardShown(true)
        b.relayout()

        assertEquals(listOf("https://example.com/page"), fetched)
        assertEquals("Page title\nhttps://example.com/page", b.row(1).dataTextView.text.toString())
        assertEquals("the other row is untouched", "plain text", b.row(0).dataTextView.text.toString())
    }

    @Test
    fun aMaskedRowIsNeverFetchedEvenIfItIsALink() {
        val b = board(recent = listOf(
            clip(1, "https://example.com/secret", label = str(R.string.clip_password_keeper_add)),
            clip(2, "http://example.com/cleartext"),
        ))

        b.adapter.onBoardShown(true)
        b.relayout()

        assertTrue("masked, and cleartext http, are both refused: $fetched", fetched.isEmpty())
    }

    @Test
    fun previewUrlsAreWholeHttpsAddressesOnly() {
        assertEquals("https://example.com/a", ClipboardWebImageProvider.previewUrlFor(" https://example.com/a ", false))
        assertEquals("a bare host is fetched over https", "example.com", ClipboardWebImageProvider.previewUrlFor("example.com", false))
        assertEquals(null, ClipboardWebImageProvider.previewUrlFor("see https://example.com/a", false))
        assertEquals(null, ClipboardWebImageProvider.previewUrlFor("http://example.com/a", false))
        assertEquals(null, ClipboardWebImageProvider.previewUrlFor("https://example.com/a", true))
        assertFalse(ClipboardWebImageProvider.isWebUrl(""))
    }
}
