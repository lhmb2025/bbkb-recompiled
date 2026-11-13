package dev.bbkb.ime.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import dev.bbkb.ime.keyboard.internal.KeyboardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.util.Locale

/**
 * Pins the symbol-page dot indicator in [KeyboardView]: `shouldDrawPageIndicator`,
 * `drawPageIndicator`, `getActivePageIndex` and the `maxVkbSymbolPage` / `maxPkbSymbolPage`
 * derivation in `setKeyboard`, swept over locale x custom page x custom-first x elementId.
 *
 * Element ids: 0 alphabet, 4 alphabetShiftLockShifted, 5..8 symbols0/1/2/Custom, 9 phone,
 * 104, 105..108 symbols0/1/2/CustomPkb, 109 phonePkb, 110 phoneSymbolsPkb. Per row: drawn or not,
 * dot count, and active dot (`hi`, -1 = none), read off a recording [Canvas].
 *
 * Locale matters: Chinese has three base symbol pages (5,6,7), others two (5,6). The max page is a
 * dot count bumped by one for the custom page; the custom page is always id 8 / 108.
 *
 * Each family has its own prefs (`enable_symbol_customization_vkb` / `vkb_custom_page_first`,
 * `..._pkb` / `pkb_custom_page_first`); on a device with a physical keyboard only the `_pkb` pair
 * is written. The table's `cust=` / `first=` axis sets both families alike.
 *
 * [KeyboardView] is allocated without running its constructor (it needs a themed context, styled
 * attributes, `PrefsManager`, `KeyboardColorManager`); only the fields this path reads are injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SymbolPageIndicatorTest {

    /** KeyboardCodesSet page-switch code; the only code that draws an indicator. */
    private val CODE_PAGE_SWITCH = -15

    private val ACTIVE_COLOR = 0xFFFF0000.toInt()
    private val INACTIVE_COLOR = 0xFF00FF00.toInt()

    private val PREF_VKB_CUSTOM_ENABLED = "enable_symbol_customization_vkb"
    private val PREF_VKB_CUSTOM_FIRST = "vkb_custom_page_first"
    private val PREF_PKB_CUSTOM_ENABLED = "enable_symbol_customization_pkb"
    private val PREF_PKB_CUSTOM_FIRST = "pkb_custom_page_first"

    private val TABLE = arrayOf(
        "en cust=0 first=0 el=0   => draw=0",
        "en cust=0 first=0 el=4   => draw=0",
        "en cust=0 first=0 el=5   => draw=1 dots=2 hi=0",
        "en cust=0 first=0 el=6   => draw=1 dots=2 hi=1",
        "en cust=0 first=0 el=7   => draw=0",
        "en cust=0 first=0 el=8   => draw=0",
        "en cust=0 first=0 el=9   => draw=0",
        "en cust=0 first=0 el=104 => draw=0",
        "en cust=0 first=0 el=105 => draw=1 dots=2 hi=0",
        "en cust=0 first=0 el=106 => draw=1 dots=2 hi=1",
        "en cust=0 first=0 el=107 => draw=0",
        "en cust=0 first=0 el=108 => draw=0",
        "en cust=0 first=0 el=109 => draw=0",
        "en cust=0 first=0 el=110 => draw=0",
        "en cust=0 first=1 el=0   => draw=0",
        "en cust=0 first=1 el=4   => draw=0",
        "en cust=0 first=1 el=5   => draw=1 dots=2 hi=0",
        "en cust=0 first=1 el=6   => draw=1 dots=2 hi=1",
        "en cust=0 first=1 el=7   => draw=0",
        "en cust=0 first=1 el=8   => draw=0",
        "en cust=0 first=1 el=9   => draw=0",
        "en cust=0 first=1 el=104 => draw=0",
        "en cust=0 first=1 el=105 => draw=1 dots=2 hi=0",
        "en cust=0 first=1 el=106 => draw=1 dots=2 hi=1",
        "en cust=0 first=1 el=107 => draw=0",
        "en cust=0 first=1 el=108 => draw=0",
        "en cust=0 first=1 el=109 => draw=0",
        "en cust=0 first=1 el=110 => draw=0",
        "en cust=1 first=0 el=0   => draw=0",
        "en cust=1 first=0 el=4   => draw=0",
        "en cust=1 first=0 el=5   => draw=1 dots=3 hi=0",
        "en cust=1 first=0 el=6   => draw=1 dots=3 hi=1",
        // symbols2 is Chinese-only; on any other locale it is not a page.
        "en cust=1 first=0 el=7   => draw=0",
        "en cust=1 first=0 el=8   => draw=1 dots=3 hi=2",
        "en cust=1 first=0 el=9   => draw=0",
        "en cust=1 first=0 el=104 => draw=0",
        "en cust=1 first=0 el=105 => draw=1 dots=3 hi=0",
        "en cust=1 first=0 el=106 => draw=1 dots=3 hi=1",
        "en cust=1 first=0 el=107 => draw=0",
        "en cust=1 first=0 el=108 => draw=1 dots=3 hi=2",
        "en cust=1 first=0 el=109 => draw=0",
        "en cust=1 first=0 el=110 => draw=0",
        "en cust=1 first=1 el=0   => draw=0",
        "en cust=1 first=1 el=4   => draw=0",
        "en cust=1 first=1 el=5   => draw=1 dots=3 hi=1",
        "en cust=1 first=1 el=6   => draw=1 dots=3 hi=2",
        "en cust=1 first=1 el=7   => draw=0",
        "en cust=1 first=1 el=8   => draw=1 dots=3 hi=0",
        "en cust=1 first=1 el=9   => draw=0",
        "en cust=1 first=1 el=104 => draw=0",
        "en cust=1 first=1 el=105 => draw=1 dots=3 hi=1",
        "en cust=1 first=1 el=106 => draw=1 dots=3 hi=2",
        "en cust=1 first=1 el=107 => draw=0",
        "en cust=1 first=1 el=108 => draw=1 dots=3 hi=0",
        "en cust=1 first=1 el=109 => draw=0",
        "en cust=1 first=1 el=110 => draw=0",
        "zh cust=0 first=0 el=0   => draw=0",
        "zh cust=0 first=0 el=4   => draw=0",
        "zh cust=0 first=0 el=5   => draw=1 dots=3 hi=0",
        "zh cust=0 first=0 el=6   => draw=1 dots=3 hi=1",
        "zh cust=0 first=0 el=7   => draw=1 dots=3 hi=2",
        "zh cust=0 first=0 el=8   => draw=0",
        "zh cust=0 first=0 el=9   => draw=0",
        "zh cust=0 first=0 el=104 => draw=0",
        "zh cust=0 first=0 el=105 => draw=1 dots=3 hi=0",
        "zh cust=0 first=0 el=106 => draw=1 dots=3 hi=1",
        "zh cust=0 first=0 el=107 => draw=1 dots=3 hi=2",
        "zh cust=0 first=0 el=108 => draw=0",
        "zh cust=0 first=0 el=109 => draw=0",
        "zh cust=0 first=0 el=110 => draw=0",
        "zh cust=0 first=1 el=0   => draw=0",
        "zh cust=0 first=1 el=4   => draw=0",
        "zh cust=0 first=1 el=5   => draw=1 dots=3 hi=0",
        "zh cust=0 first=1 el=6   => draw=1 dots=3 hi=1",
        "zh cust=0 first=1 el=7   => draw=1 dots=3 hi=2",
        "zh cust=0 first=1 el=8   => draw=0",
        "zh cust=0 first=1 el=9   => draw=0",
        "zh cust=0 first=1 el=104 => draw=0",
        "zh cust=0 first=1 el=105 => draw=1 dots=3 hi=0",
        "zh cust=0 first=1 el=106 => draw=1 dots=3 hi=1",
        "zh cust=0 first=1 el=107 => draw=1 dots=3 hi=2",
        "zh cust=0 first=1 el=108 => draw=0",
        "zh cust=0 first=1 el=109 => draw=0",
        "zh cust=0 first=1 el=110 => draw=0",
        "zh cust=1 first=0 el=0   => draw=0",
        "zh cust=1 first=0 el=4   => draw=0",
        "zh cust=1 first=0 el=5   => draw=1 dots=4 hi=0",
        "zh cust=1 first=0 el=6   => draw=1 dots=4 hi=1",
        "zh cust=1 first=0 el=7   => draw=1 dots=4 hi=2",
        "zh cust=1 first=0 el=8   => draw=1 dots=4 hi=3",
        "zh cust=1 first=0 el=9   => draw=0",
        "zh cust=1 first=0 el=104 => draw=0",
        "zh cust=1 first=0 el=105 => draw=1 dots=4 hi=0",
        "zh cust=1 first=0 el=106 => draw=1 dots=4 hi=1",
        "zh cust=1 first=0 el=107 => draw=1 dots=4 hi=2",
        "zh cust=1 first=0 el=108 => draw=1 dots=4 hi=3",
        // phonePkb is not a symbol page.
        "zh cust=1 first=0 el=109 => draw=0",
        "zh cust=1 first=0 el=110 => draw=0",
        "zh cust=1 first=1 el=0   => draw=0",
        "zh cust=1 first=1 el=4   => draw=0",
        "zh cust=1 first=1 el=5   => draw=1 dots=4 hi=1",
        "zh cust=1 first=1 el=6   => draw=1 dots=4 hi=2",
        "zh cust=1 first=1 el=7   => draw=1 dots=4 hi=3",
        "zh cust=1 first=1 el=8   => draw=1 dots=4 hi=0",
        "zh cust=1 first=1 el=9   => draw=0",
        "zh cust=1 first=1 el=104 => draw=0",
        "zh cust=1 first=1 el=105 => draw=1 dots=4 hi=1",
        "zh cust=1 first=1 el=106 => draw=1 dots=4 hi=2",
        "zh cust=1 first=1 el=107 => draw=1 dots=4 hi=3",
        "zh cust=1 first=1 el=108 => draw=1 dots=4 hi=0",
        "zh cust=1 first=1 el=109 => draw=0",
        "zh cust=1 first=1 el=110 => draw=0",
    )

    /** Records the colour of every dot drawn, in draw order. */
    private class RecordingCanvas : Canvas() {
        val dotColors = ArrayList<Int>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            dotColors.add(paint.color)
        }
    }

    /** A [KeyboardView] whose constructor never ran; every method runs its real body. */
    private fun bareView(): KeyboardView =
        mock(KeyboardView::class.java, withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))

    private fun field(name: String): Field =
        KeyboardView::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** Both families configured alike (the table's `cust=` / `first=` axis). */
    private fun prefs(customEnabled: Boolean, customFirst: Boolean): SharedPreferences =
        prefs(customEnabled, customFirst, customEnabled, customFirst)

    /** The two families configured independently. */
    private fun prefs(vkbEnabled: Boolean, vkbFirst: Boolean,
                      pkbEnabled: Boolean, pkbFirst: Boolean): SharedPreferences {
        val p = RuntimeEnvironment.getApplication()
            .getSharedPreferences("w2c-symbol-page", Context.MODE_PRIVATE)
        p.edit().clear()
            .putBoolean(PREF_VKB_CUSTOM_ENABLED, vkbEnabled)
            .putBoolean(PREF_VKB_CUSTOM_FIRST, vkbFirst)
            .putBoolean(PREF_PKB_CUSTOM_ENABLED, pkbEnabled)
            .putBoolean(PREF_PKB_CUSTOM_FIRST, pkbFirst)
            .commit()
        return p
    }

    /** A [Keyboard] with only the fields the indicator path reads. */
    private fun keyboard(elementId: Int, chinese: Boolean): Keyboard {
        val id = mock(KeyboardId::class.java)
        KeyboardId::class.java.getDeclaredField("mElementId")
            .apply { isAccessible = true }.setInt(id, elementId)
        KeyboardId::class.java.getDeclaredField("mLocale")
            .apply { isAccessible = true }
            .set(id, if (chinese) Locale.CHINESE else Locale.ENGLISH)
        val kb = mock(Keyboard::class.java)
        Keyboard::class.java.getDeclaredField("mId").apply { isAccessible = true }.set(kb, id)
        Keyboard::class.java.getDeclaredField("mVerticalGap")
            .apply { isAccessible = true }.setInt(kb, 4)
        return kb
    }

    /**
     * A [KeyboardView] wired for the indicator path. `setKeyboard` runs for real so the max-page
     * derivation happens, then throws on a missing field; [assertMaxPagesWereDerived] fails if
     * the derivation ever moves past that point.
     */
    private fun view(elementId: Int, chinese: Boolean,
                     customEnabled: Boolean, customFirst: Boolean): KeyboardView =
        view(elementId, chinese, customEnabled, customFirst, customEnabled, customFirst)

    /** As [view], with the two preference families set independently. */
    private fun view(elementId: Int, chinese: Boolean,
                     vkbEnabled: Boolean, vkbFirst: Boolean,
                     pkbEnabled: Boolean, pkbFirst: Boolean): KeyboardView {
        val v = bareView()
        field("sharedPreferences").set(v, prefs(vkbEnabled, vkbFirst, pkbEnabled, pkbFirst))
        field("pageIndicatorActiveColor").setInt(v, ACTIVE_COLOR)
        field("pageIndicatorInactiveColor").setInt(v, INACTIVE_COLOR)
        try {
            v.setKeyboard(keyboard(elementId, chinese))
        } catch (expected: Throwable) {
            // setKeyboard goes on to touch view state this instance does not have.
        }
        assertEquals("setKeyboard must have stored the keyboard",
            elementId, field("keyboard").get(v)!!.let { (it as Keyboard).mId.mElementId })
        assertMaxPagesWereDerived(v)
        return v
    }

    private fun assertMaxPagesWereDerived(v: KeyboardView) {
        assertTrue("maxVkbSymbolPage was never derived - setKeyboard threw too early",
            field("maxVkbSymbolPage").getInt(v) >= 6)
        assertTrue("maxPkbSymbolPage was never derived - setKeyboard threw too early",
            field("maxPkbSymbolPage").getInt(v) >= 106)
    }

    private fun key(code: Int): Key {
        val k = mock(Key::class.java)
        `when`(k.code).thenReturn(code)
        `when`(k.height).thenReturn(200)
        `when`(k.width).thenReturn(60)
        return k
    }

    private fun shouldDraw(v: KeyboardView, k: Key): Boolean {
        val m = KeyboardView::class.java
            .getDeclaredMethod("shouldDrawPageIndicator", Key::class.java)
            .apply { isAccessible = true }
        return m.invoke(v, k) as Boolean
    }

    private fun draw(v: KeyboardView, k: Key): RecordingCanvas {
        val canvas = RecordingCanvas()
        KeyboardView::class.java
            .getDeclaredMethod("drawPageIndicator", Canvas::class.java, Key::class.java)
            .apply { isAccessible = true }
            .invoke(v, canvas, k)
        return canvas
    }

    /**
     * Reflective call on private `getActivePageIndex`, deriving `isPkb` as `drawPageIndicator`
     * does. The one-argument signature is pinned: an added parameter fails with NoSuchMethodException.
     */
    private fun activePageIndex(v: KeyboardView, elementId: Int): Int =
        KeyboardView::class.java
            .getDeclaredMethod("getActivePageIndex", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(v, elementId >= 105) as Int

    /** Runs one table row and renders its output half. */
    private fun probe(chinese: Boolean, customEnabled: Boolean, customFirst: Boolean,
                      elementId: Int): String =
        probe(chinese, customEnabled, customFirst, customEnabled, customFirst, elementId)

    /** As [probe], with the two preference families set independently. */
    private fun probe(chinese: Boolean,
                      vkbEnabled: Boolean, vkbFirst: Boolean,
                      pkbEnabled: Boolean, pkbFirst: Boolean,
                      elementId: Int): String {
        val v = view(elementId, chinese, vkbEnabled, vkbFirst, pkbEnabled, pkbFirst)
        val k = key(CODE_PAGE_SWITCH)
        if (!shouldDraw(v, k)) return "draw=0"
        val canvas = draw(v, k)
        val hi = canvas.dotColors.indexOf(ACTIVE_COLOR)
        return "draw=1 dots=${canvas.dotColors.size} hi=$hi"
    }

    /** The table. Every mismatch is collected so a change shows every row it moved. */
    @Test
    fun pageIndicatorTable() {
        val mismatches = ArrayList<String>()
        for (row in TABLE) {
            val (specRaw, expected) = row.split(" => ", limit = 2)
            val spec = specRaw.trim()
            val parts = spec.split(Regex(" +"))
            val chinese = parts[0] == "zh"
            val customEnabled = parts[1].endsWith("=1")
            val customFirst = parts[2].endsWith("=1")
            val elementId = parts[3].substringAfter("el=").trim().toInt()
            val actual = probe(chinese, customEnabled, customFirst, elementId)
            if (actual != expected) {
                mismatches.add("$spec\n    expected: $expected\n    actual:   $actual")
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} of ${TABLE.size} page-indicator rows changed:\n"
                    + mismatches.joinToString("\n"))
        }
    }

    /** Guards the table: a table that silently shrank would make [pageIndicatorTable] vacuous. */
    @Test
    fun tableCoversEveryInputCombination() {
        assertEquals(112, TABLE.size)
    }

    /**
     * With both families configured alike, VKB element `5 + n` and PKB element `105 + n` give the
     * same verdict, dot count and active dot. When configs differ, see
     * [pkbAndVkbFormsDivergeWhenOnlyOneFamilyEnablesCustomPages].
     */
    @Test
    fun pkbAndVkbFormsAgreeOnEveryPageInRange() {
        for (chinese in listOf(false, true)) {
            for (custom in listOf(false, true)) {
                for (first in listOf(false, true)) {
                    // symbols0/1/2, symbolsCustom, phone, phoneSymbols and their Pkb twins.
                    for (offset in 0..5) {
                        val vkb = probe(chinese, custom, first, 5 + offset)
                        val pkb = probe(chinese, custom, first, 105 + offset)
                        assertEquals(
                            "zh=$chinese cust=$custom first=$first offset=$offset",
                            vkb, pkb)
                    }
                }
            }
        }
    }

    /**
     * Each family follows its own setting, so the forms differ exactly when their configs differ.
     * Expectations are spelled out so a reshuffled divergence is caught too. `en` throughout: base
     * pages 5,6 / 105,106 and the custom page at 8 / 108.
     */
    @Test
    fun pkbAndVkbFormsDivergeWhenOnlyOneFamilyEnablesCustomPages() {
        // Custom pages on for the physical keyboard only (the usual state on a keypad device).
        assertEquals("draw=1 dots=2 hi=0", probe(false, false, false, true, false, 5))
        assertEquals("draw=1 dots=3 hi=0", probe(false, false, false, true, false, 105))
        assertEquals("draw=1 dots=2 hi=1", probe(false, false, false, true, false, 6))
        assertEquals("draw=1 dots=3 hi=1", probe(false, false, false, true, false, 106))
        assertEquals("draw=0", probe(false, false, false, true, false, 8))
        assertEquals("draw=1 dots=3 hi=2", probe(false, false, false, true, false, 108))

        // The mirror: on-screen only. A touch-only device writes only the `_vkb` pair.
        assertEquals("draw=1 dots=3 hi=0", probe(false, true, false, false, false, 5))
        assertEquals("draw=1 dots=2 hi=0", probe(false, true, false, false, false, 105))
        assertEquals("draw=1 dots=3 hi=2", probe(false, true, false, false, false, 8))
        assertEquals("draw=0", probe(false, true, false, false, false, 108))

        // The `first` flags are independent too: both families have custom pages, but only the
        // on-screen one puts the custom page first, so only its dots are shifted by one.
        assertEquals("draw=1 dots=3 hi=1", probe(false, true, true, true, false, 5))
        assertEquals("draw=1 dots=3 hi=0", probe(false, true, true, true, false, 105))
        assertEquals("draw=1 dots=3 hi=0", probe(false, true, true, true, false, 8))
        assertEquals("draw=1 dots=3 hi=2", probe(false, true, true, true, false, 108))
    }

    /** The dot count is exactly `maxPage - firstElement + 1` for both families. */
    @Test
    fun dotCountIsMaxPageMinusFirstPlusOne() {
        for (chinese in listOf(false, true)) {
            for (custom in listOf(false, true)) {
                val v = view(5, chinese, custom, false)
                val maxVkb = field("maxVkbSymbolPage").getInt(v)
                val maxPkb = field("maxPkbSymbolPage").getInt(v)
                assertEquals(maxVkb - 5 + 1, draw(v, key(CODE_PAGE_SWITCH)).dotColors.size)
                val p = view(105, chinese, custom, false)
                assertEquals(maxPkb - 105 + 1, draw(p, key(CODE_PAGE_SWITCH)).dotColors.size)
            }
        }
    }

    /**
     * `setKeyboard`'s derivation: 6/106 non-Chinese, 7/107 Chinese, one more of each with the custom
     * page on. Each bump keys off its own family's `enable_symbol_customization_*` pref.
     */
    @Test
    fun maxSymbolPageDerivation() {
        // Both families configured alike.
        val expected = mapOf(
            Pair(false, false) to Pair(6, 106),
            Pair(false, true) to Pair(7, 107),
            Pair(true, false) to Pair(7, 107),
            Pair(true, true) to Pair(8, 108))
        for ((k, want) in expected) {
            val v = view(5, k.first, k.second, false)
            assertEquals("zh=${k.first} cust=${k.second} maxVkbSymbolPage",
                want.first, field("maxVkbSymbolPage").getInt(v))
            assertEquals("zh=${k.first} cust=${k.second} maxPkbSymbolPage",
                want.second, field("maxPkbSymbolPage").getInt(v))
        }

        // Configured independently: each bound follows its own family's preference only.
        val pkbOnly = view(5, false, false, false, true, false)
        assertEquals(6, field("maxVkbSymbolPage").getInt(pkbOnly))
        assertEquals(107, field("maxPkbSymbolPage").getInt(pkbOnly))
        val vkbOnly = view(5, false, true, false, false, false)
        assertEquals(7, field("maxVkbSymbolPage").getInt(vkbOnly))
        assertEquals(106, field("maxPkbSymbolPage").getInt(vkbOnly))
    }

    /** No indicator on a key that is not the page-switch key, whatever the element. */
    @Test
    fun onlyThePageSwitchKeyDrawsAnIndicator() {
        val v = view(5, false, true, false)
        assertTrue(shouldDraw(v, key(CODE_PAGE_SWITCH)))
        assertFalse(shouldDraw(v, key(32)))
        assertFalse(shouldDraw(v, key(-1)))
        assertFalse(shouldDraw(v, key(-14)))
        assertFalse(shouldDraw(v, key(-16)))
    }

    /**
     * Each accessor reads its own family's key and ignores the other's, so the PKB dot row matches
     * the PKB page cycle `KeyboardState` builds from the `_pkb` pair:
     *  - `getIsCustomSymbolPageEnabled` ← `enable_symbol_customization_vkb`
     *  - `getIsPkbCustomSymbolPageEnabled` ← `enable_symbol_customization_pkb`
     *  - `getIsCustomSymbolPageFirst` ← `vkb_custom_page_first`
     *  - `getIsPkbCustomSymbolPageFirst` ← `pkb_custom_page_first`
     */
    @Test
    fun pkbAccessorsReadTheirOwnPkbPreferences() {
        fun accessor(v: KeyboardView, name: String): Boolean =
            KeyboardView::class.java.getDeclaredMethod(name)
                .apply { isAccessible = true }.invoke(v) as Boolean

        val v = bareView()
        val p = RuntimeEnvironment.getApplication()
            .getSharedPreferences("w2c-alias", Context.MODE_PRIVATE)
        field("sharedPreferences").set(v, p)

        // Only the VKB keys set: the PKB accessors must report false.
        p.edit().clear()
            .putBoolean(PREF_VKB_CUSTOM_ENABLED, true)
            .putBoolean(PREF_VKB_CUSTOM_FIRST, true)
            .commit()
        assertTrue(accessor(v, "getIsCustomSymbolPageEnabled"))
        assertTrue(accessor(v, "getIsCustomSymbolPageFirst"))
        assertFalse("getIsPkbCustomSymbolPageEnabled must not read the _vkb key",
            accessor(v, "getIsPkbCustomSymbolPageEnabled"))
        assertFalse("getIsPkbCustomSymbolPageFirst must not read the _vkb key",
            accessor(v, "getIsPkbCustomSymbolPageFirst"))

        // Only the PKB keys set: the mirror image.
        p.edit().clear()
            .putBoolean(PREF_PKB_CUSTOM_ENABLED, true)
            .putBoolean(PREF_PKB_CUSTOM_FIRST, true)
            .commit()
        assertTrue("getIsPkbCustomSymbolPageEnabled must read the _pkb key",
            accessor(v, "getIsPkbCustomSymbolPageEnabled"))
        assertTrue("getIsPkbCustomSymbolPageFirst must read the _pkb key",
            accessor(v, "getIsPkbCustomSymbolPageFirst"))
        assertFalse(accessor(v, "getIsCustomSymbolPageEnabled"))
        assertFalse(accessor(v, "getIsCustomSymbolPageFirst"))

        // The two axes within a family are also distinct keys, not one shared flag.
        p.edit().clear()
            .putBoolean(PREF_VKB_CUSTOM_ENABLED, true)
            .putBoolean(PREF_PKB_CUSTOM_ENABLED, true)
            .commit()
        assertTrue(accessor(v, "getIsCustomSymbolPageEnabled"))
        assertTrue(accessor(v, "getIsPkbCustomSymbolPageEnabled"))
        assertFalse(accessor(v, "getIsCustomSymbolPageFirst"))
        assertFalse(accessor(v, "getIsPkbCustomSymbolPageFirst"))
    }

    /**
     * Chinese with custom pages on makes `maxPkbSymbolPage` 108, so `phonePkb` (109) sits at
     * max + 1. It must draw no indicator, like `phone` (9), while 108 (the real last page) still draws.
     */
    @Test
    fun phonePkbDrawsNoPageIndicatorForChineseWithCustomPages() {
        assertFalse("phonePkb is not a symbol page",
            shouldDraw(view(109, true, true, false), key(CODE_PAGE_SWITCH)))
        // phone (9), the VKB counterpart, agrees.
        assertFalse(shouldDraw(view(9, true, true, false), key(CODE_PAGE_SWITCH)))
        // The page one below it is untouched: 108 is the custom page and still draws, active.
        val custom = view(108, chinese = true, customEnabled = true, customFirst = false)
        assertTrue(shouldDraw(custom, key(CODE_PAGE_SWITCH)))
        val canvas = draw(custom, key(CODE_PAGE_SWITCH))
        assertEquals(4, canvas.dotColors.size)
        assertEquals(3, canvas.dotColors.indexOf(ACTIVE_COLOR))
    }

    /** Custom-page-first variant: still no indicator on 109, and the custom page is dot 0. */
    @Test
    fun phonePkbWithCustomPageFirstDrawsNoIndicatorEither() {
        assertFalse(shouldDraw(view(109, true, true, true), key(CODE_PAGE_SWITCH)))
        val custom = view(108, chinese = true, customEnabled = true, customFirst = true)
        assertEquals(0, activePageIndex(custom, 108))
        assertEquals(0, draw(custom, key(CODE_PAGE_SWITCH)).dotColors.indexOf(ACTIVE_COLOR))
    }

    /** The active index is a plain offset from the family's first symbol page (5 or 105). */
    @Test
    fun pkbActivePageIndexIsAPlainOffsetFromTheFirstPkbPage() {
        val v = view(106, chinese = false, customEnabled = false, customFirst = false)
        assertEquals(1, activePageIndex(v, 106))
        // Every PKB element, including the ones past the drawable range.
        for (elementId in 105..110) {
            assertEquals("el=$elementId", elementId - 105,
                activePageIndex(view(elementId, false, false, false), elementId))
        }
        // ...and the VKB side is the same offset from 5.
        for (elementId in 5..10) {
            assertEquals("el=$elementId", elementId - 5,
                activePageIndex(view(elementId, false, false, false), elementId))
        }
    }

    /**
     * Non-Chinese with custom pages on bumps `maxVkbSymbolPage` to 7, but `symbols2` (7 / 107) is
     * Chinese-only and must draw nothing; on Chinese it still draws as the third dot.
     */
    @Test
    fun symbols2DrawsNoIndicatorOnANonChineseLocale() {
        assertEquals("draw=0", probe(false, true, true, 7))
        assertEquals("draw=0", probe(false, true, true, 107))
        assertEquals("draw=0", probe(false, true, false, 7))
        assertEquals("draw=0", probe(false, true, false, 107))
        // Chinese is where symbols2 is real, and it is unaffected.
        assertEquals("draw=1 dots=4 hi=2", probe(true, true, false, 7))
        assertEquals("draw=1 dots=4 hi=2", probe(true, true, false, 107))
    }
}
