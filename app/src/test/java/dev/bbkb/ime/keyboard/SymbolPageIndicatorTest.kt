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
 * Characterisation table for the symbol-page dot indicator in [KeyboardView] —
 * `shouldDrawPageIndicator`, `drawPageIndicator`, `getActivePageIndex`,
 * `adjustPageIndexForCustomPage`, and the `maxVkbSymbolPage` / `maxPkbSymbolPage` derivation in
 * `setKeyboard`.
 *
 * CONTRACT: this pins what the code does. Wave 3 wants to collapse the duplicated PKB and VKB
 * forms into one; every row below is what the collapsed form has to reproduce. The table was
 * written in Wave 2 against the pre-fix code and six of its rows changed in Wave 2.5 when
 * DEFECT 23 was fixed — those rows are called out under "What DEFECT 23 changed" below.
 *
 * ## What the table covers
 * `(locale zh vs en) x (custom symbol page on/off) x (custom page first on/off) x elementId`,
 * where elementId sweeps both keyboard families and their edges:
 * `0 alphabet, 4 alphabetShiftLockShifted, 5..8 symbols0/1/2/Custom, 9 phone,
 *  104, 105..108 symbols0/1/2/CustomPkb, 109 phonePkb, 110 phoneSymbolsPkb`
 * (names from [KeyboardId.elementIdToName]).
 *
 * Per row: whether the indicator is drawn at all, how many dots are drawn, and which dot is
 * painted in the active colour (`hi`, `-1` = none — see the bugs below). Dot count and active dot
 * are read off a recording [Canvas], i.e. they are the actual `drawText` calls, not an internal
 * counter.
 *
 * The locale axis is in the table because it is not incidental: `maxVkbSymbolPage` starts at 7 for
 * Chinese and 6 otherwise — Chinese has three base symbol pages (5,6,7) where every other locale
 * has two (5,6). `adjustPageIndexForCustomPage` still branches on `LocaleUtils.isChinese` under
 * the local name `isRightToLeft` — which has nothing to do with right-to-left script.
 * `shouldDrawPageIndicator` no longer needs that branch at all (see below).
 *
 * ## The two preference families (DEFECT 24, fixed in Wave 2.5)
 * The on-screen and physical keyboards each have their OWN custom-symbol-page setting:
 *  - VKB: `enable_symbol_customization_vkb` / `vkb_custom_page_first`
 *  - PKB: `enable_symbol_customization_pkb` / `pkb_custom_page_first`
 *
 * `SymbolCustomizationScreen` writes both pairs, and on a device WITH a physical keyboard it
 * writes ONLY the `_pkb` pair; `KeyboardSwitcher.isPkbSymbolCustomizationEnabled()` /
 * `isPkbCustomPageFirst()` / `setPkbSymbolsKeyboard()` read the `_pkb` keys, and `KeyboardState`
 * sizes and orders the PKB symbol-page cycle from them.
 *
 * Until Wave 2.5 the view's two `getIsPkb*` accessors read the `_vkb` keys — aliases of their
 * VKB twins. On a KEY2 that made the dot row disagree with the page cycle it annotates: with the
 * custom symbol page on, `KeyboardState` cycled three PKB pages (105, 106, 108) while the dot row
 * drew two dots and `symbolsPkbCustom` (108) drew no indicator at all. The accessors are now
 * aligned on the `_pkb` keys (aligning was chosen over retiring the `_pkb` setting, which is live
 * on-disk state on every KEY2). [pkbAccessorsReadTheirOwnPkbPreferences] asserts the alignment —
 * that each accessor reads its own family's key and is NOT moved by the other family's. That
 * assertion is what stops the alias being reintroduced.
 *
 * Consequently the `cust=` / `first=` axis of the table below means "BOTH families configured
 * this way": the table sweeps both families under one config, so [prefs] writes both key pairs.
 * The cases where the two families are configured DIFFERENTLY — only possible since the fix —
 * are covered by [pkbAndVkbFormsDivergeWhenOnlyOneFamilyEnablesCustomPages] and by the split
 * half of [maxSymbolPageDerivation].
 *
 * ## What DEFECT 23 changed (Wave 2.5)
 * `maxVkbSymbolPage` / `maxPkbSymbolPage` are a DOT COUNT, bumped by one when the custom symbol
 * page is enabled. `shouldDrawPageIndicator` also used the bumped value as an element-id RANGE,
 * but the custom page's element id is always `symbolsCustom` (8) / `symbolsPkbCustom` (108) —
 * which equals "last base page + 1" only for Chinese. The fix strips the bump back off before
 * using the bound as a range and admits the custom page by its own id. Six rows moved, all from
 * `draw=1` to `draw=0`, and all of them cases where the active index fell outside the dot row or
 * the element was not a real page:
 *  1. `zh cust=1 first=0 el=109` and `zh cust=1 first=1 el=109` — `phonePkb` drew a dot row with
 *     no active dot, because `109 == maxPkbSymbolPage + 1` and the PKB half of the out-of-range
 *     clause lacked the `!isRightToLeftLayout` guard its VKB twin carried. `phone` (9) was
 *     already correctly rejected. See [phonePkbDrawsNoPageIndicatorForChineseWithCustomPages].
 *  2. `en cust=1 first=0 el=7` / `el=107` and `en cust=1 first=1 el=7` / `el=107` — `symbols2`
 *     is a Chinese-only page, but the bumped bound of 7 pulled it inside the non-Chinese range.
 *     Under custom-page-first it landed on index 3 of 3 dots, i.e. no active dot. Same root
 *     cause, fixed by the same change. See [symbols2DrawsNoIndicatorOnANonChineseLocale].
 *
 * Also fixed: `getActivePageIndex`'s PKB branch was a dead ternary. It was called with
 * `basePageIndex = elementId - 105`, so `elementId >= basePageIndex` was always true and the
 * false arm unreachable; the parameter was unused on the VKB side too. Both are gone and the
 * second parameter with them. See [pkbActivePageIndexIsAPlainOffsetFromTheFirstPkbPage].
 *
 * ## How the view is built
 * A [KeyboardView] cannot be constructed here: its constructor needs a themed [Context], inflated
 * style attributes, `PrefsManager` and `KeyboardColorManager`. The instance is allocated without
 * running the constructor and the handful of fields the indicator path reads are injected. That is
 * a test-only workaround, not a seam — see SEAMS NEEDED in the Wave 2 report.
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

    // ---------------------------------------------------------------- table

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
        // DEFECT 23: symbols2 is Chinese-only; on any other locale it is not a page at all.
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
        // DEFECT 23: phonePkb is not a symbol page; it used to draw a dot row with no
        // active dot, while its VKB twin phone (9) was already rejected.
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

    // ------------------------------------------------------------- plumbing

    /** Records the colour of every dot drawn, in draw order. */
    private class RecordingCanvas : Canvas() {
        val dotColors = ArrayList<Int>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            dotColors.add(paint.color)
        }
    }

    /**
     * A [KeyboardView] instance whose constructor never ran. Mockito allocates it;
     * `CALLS_REAL_METHODS` means every method — the four preference accessors included — executes
     * the production body against whatever fields the test injects.
     */
    private fun bareView(): KeyboardView =
        mock(KeyboardView::class.java, withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS))

    private fun field(name: String): Field =
        KeyboardView::class.java.getDeclaredField(name).apply { isAccessible = true }

    /**
     * Both families configured alike — the table's `cust=` / `first=` axis. Since DEFECT 24 the
     * view reads a different key per family, so "custom pages on" has to be written to both.
     */
    private fun prefs(customEnabled: Boolean, customFirst: Boolean): SharedPreferences =
        prefs(customEnabled, customFirst, customEnabled, customFirst)

    /** The families configured independently, which is only meaningful since DEFECT 24. */
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
     * A [KeyboardView] wired up for the indicator path only.
     *
     * `setKeyboard` is called for real so that the `maxVkbSymbolPage` / `maxPkbSymbolPage`
     * derivation — including its two live preference reads, one per family — actually runs, which
     * is what makes the split preferences observable here. It then throws on the
     * first field the un-constructed instance does not have; the derivation is complete by that
     * point, and [assertMaxPagesWereDerived] fails loudly if a future change moves it later.
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
     * Reflective call on the private `getActivePageIndex`. `isPkb` is derived here exactly as
     * `drawPageIndicator` derives it. Used by the offset tests only; the table reads the
     * highlighted dot off the canvas instead.
     *
     * DEFECT 23 removed this method's second parameter (a dead `basePageIndex`), so the
     * one-argument signature is itself part of what is pinned — a reintroduced parameter fails
     * here with NoSuchMethodException rather than silently.
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

    // ---------------------------------------------------------------- tests

    /**
     * The table. Every mismatch is collected so a rewrite that shifts one case shows every row it
     * moved, not just the first.
     */
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
     * THE IDENTITY WAVE 3'S PKB/VKB COLLAPSE RESTS ON. **Given the two families are configured
     * the same way**, the VKB element `5 + n` and the PKB element `105 + n` produce the same
     * verdict, the same dot count and the same active dot. Anything that breaks this makes the two
     * forms genuinely different code.
     *
     * DEFECT 23 did not weaken it — it strengthened it. Before that fix the sweep had to stop at
     * the custom page, because phonePkb (109) drew where phone (9) did not; the sweep now runs
     * across the whole element range the table covers, phone/phoneSymbols included, and the two
     * families agree on every one of them.
     *
     * DEFECT 24 did not weaken it either, but it does narrow what it says. The identity is now
     * conditional on the CONFIG being equal rather than automatic: before the fix the PKB form
     * read the VKB preferences, so the two families could not disagree even in principle. Each
     * family now reads its own preference pair, so agreement holds when they are set alike (below)
     * and is legitimately absent when they are not — that half is
     * [pkbAndVkbFormsDivergeWhenOnlyOneFamilyEnablesCustomPages]. Wave 3's collapse is still on:
     * it has to collapse the *logic*, keeping the preference pair per family as an input.
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
     * DEFECT 24, FIXED — the other half of the identity above, and the behaviour a KEY2 actually
     * gets. `SymbolCustomizationScreen` writes only the `_pkb` pair on a physical-keyboard device,
     * so "custom symbol page on, `_vkb` untouched" is the real on-disk state there. Each family
     * must now follow its OWN setting, which means the two forms differ exactly when their configs
     * differ — the dot row agreeing with the page cycle is the point of the fix, not the families
     * agreeing with each other.
     *
     * Expectations are spelled out rather than asserted as "not equal" so that a regression which
     * merely reshuffles the divergence is caught too. The `en` locale is used throughout: it has
     * two base symbol pages (5,6 / 105,106) and the custom page a gap away at 8 / 108.
     */
    @Test
    fun pkbAndVkbFormsDivergeWhenOnlyOneFamilyEnablesCustomPages() {
        // Custom pages on for the physical keyboard only — the KEY2 case. Three PKB dots with the
        // custom page last, two VKB dots and no VKB custom page. Before the fix the PKB column
        // read the VKB preference and produced the VKB column's answers.
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
     * `setKeyboard`'s derivation: 6/106 for a non-Chinese locale, 7/107 for Chinese, and one more
     * of each when the custom symbol page is enabled.
     *
     * DEFECT 24: the two increments key off DIFFERENT preferences — `maxVkbSymbolPage` off
     * `enable_symbol_customization_vkb`, `maxPkbSymbolPage` off `enable_symbol_customization_pkb`
     * — so they can now diverge, and the second half below pins that they do. They used to share
     * the `_vkb` key, which is why a KEY2 (where only the `_pkb` key is ever written) sized its
     * PKB dot row as if custom pages were off.
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
     * DEFECT 24, FIXED — AND THE ASSERTION THAT KEEPS IT FIXED. Each accessor reads its own
     * family's preference key and is not moved by the other family's:
     *  - `getIsCustomSymbolPageEnabled` ← `enable_symbol_customization_vkb`
     *  - `getIsPkbCustomSymbolPageEnabled` ← `enable_symbol_customization_pkb`
     *  - `getIsCustomSymbolPageFirst` ← `vkb_custom_page_first`
     *  - `getIsPkbCustomSymbolPageFirst` ← `pkb_custom_page_first`
     *
     * The two PKB accessors used to be aliases of their VKB twins, reading the `_vkb` keys. On a
     * device WITH a physical keyboard `SymbolCustomizationScreen` writes ONLY the `_pkb` pair, and
     * `KeyboardSwitcher` / `KeyboardState` build the PKB page cycle from that pair — so the dot
     * row was derived from a preference nothing on that device wrote, and disagreed with the cycle
     * it annotates (three reachable pages, two dots; see the class doc).
     *
     * CORRECT BEHAVIOUR: the families are aligned on their own keys. The `_vkb`-only half below is
     * the inversion of the old assertion and is the single most important line in this file — with
     * only the `_vkb` keys set, the PKB accessors must report `false`. Reintroducing the alias
     * fails there.
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

        // Only the VKB keys set — the on-screen family follows them, the PKB family does NOT.
        // This is the assertion that would fail if the accessors were aliased again.
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

        // Only the PKB keys set — the mirror image. This is the config a real KEY2 is in.
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
     * DEFECT 23, FIXED. Chinese locale with custom symbol pages on is the config where
     * `maxPkbSymbolPage` is 108, so `phonePkb` (109) used to satisfy the out-of-range clause's
     * `elementId == maxPkbSymbolPage + 1` — the PKB half of which lacked the `!isRightToLeftLayout`
     * guard its VKB twin carried. The phone keyboard drew a dot row, and because the active index
     * (4) was past the last dot, none of the dots was painted active.
     *
     * CORRECT BEHAVIOUR: `phonePkb` is not a symbol page and draws no indicator, matching `phone`
     * (9), which was already rejected. `symbolsPkbCustom` (108) — the real last page in this
     * config — still draws, so the fix did not simply shorten the range.
     */
    @Test
    fun phonePkbDrawsNoPageIndicatorForChineseWithCustomPages() {
        assertFalse("phonePkb is not a symbol page",
            shouldDraw(view(109, true, true, false), key(CODE_PAGE_SWITCH)))
        // phone (9), the VKB counterpart, agrees - it always did.
        assertFalse(shouldDraw(view(9, true, true, false), key(CODE_PAGE_SWITCH)))
        // The page one below it is untouched: 108 is the custom page and still draws, active.
        val custom = view(108, chinese = true, customEnabled = true, customFirst = false)
        assertTrue(shouldDraw(custom, key(CODE_PAGE_SWITCH)))
        val canvas = draw(custom, key(CODE_PAGE_SWITCH))
        assertEquals(4, canvas.dotColors.size)
        assertEquals(3, canvas.dotColors.indexOf(ACTIVE_COLOR))
    }

    /**
     * DEFECT 23, FIXED — the custom-page-first variant, which used to land on index 5 of 4 dots.
     * CORRECT BEHAVIOUR: no indicator either way, and the custom page is dot 0 as custom-first
     * asks.
     */
    @Test
    fun phonePkbWithCustomPageFirstDrawsNoIndicatorEither() {
        assertFalse(shouldDraw(view(109, true, true, true), key(CODE_PAGE_SWITCH)))
        val custom = view(108, chinese = true, customEnabled = true, customFirst = true)
        assertEquals(0, activePageIndex(custom, 108))
        assertEquals(0, draw(custom, key(CODE_PAGE_SWITCH)).dotColors.indexOf(ACTIVE_COLOR))
    }

    /**
     * DEFECT 23, FIXED. `getActivePageIndex`'s PKB branch used to read
     * `elementId >= basePageIndex ? elementId - 105 : elementId - basePageIndex`, and its only
     * caller passed `basePageIndex = elementId - 105`. For any PKB element that condition is
     * `elementId >= elementId - 105`, always true: the false arm was unreachable, and the
     * parameter was unused on the VKB side too.
     *
     * CORRECT BEHAVIOUR: a plain offset from the family's first symbol page, with no second
     * parameter. Every PKB element gets exactly what the live arm gave it, so the two-argument
     * signature is gone — [activePageIndex] would fail with NoSuchMethodException if it came back.
     */
    @Test
    fun pkbActivePageIndexIsAPlainOffsetFromTheFirstPkbPage() {
        val v = view(106, chinese = false, customEnabled = false, customFirst = false)
        assertEquals(1, activePageIndex(v, 106))
        // Unchanged for every PKB element, including the ones past the drawable range.
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
     * DEFECT 23, FIXED — the latent half of the same root cause. On a non-Chinese locale with
     * custom pages on, the custom-page bump pushed `maxVkbSymbolPage` to 7, which pulled the
     * Chinese-only `symbols2` (7) / `symbols2Pkb` (107) inside the drawable range. Under
     * custom-page-first they mapped to active index 3 of 3 dots, so no dot was active.
     *
     * CORRECT BEHAVIOUR: `symbols2` is not a page outside Chinese and draws nothing, with or
     * without custom-page-first — while on Chinese, where it IS a page, it still draws and is
     * still the third dot.
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
