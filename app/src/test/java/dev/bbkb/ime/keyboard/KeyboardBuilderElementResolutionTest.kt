package dev.bbkb.ime.keyboard

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterisation table for `KeyboardBuilder`'s element resolution — the two-stage map from
 * (keyboard mode, requested element id, "physical keyboard?") to the `KeyboardId` it builds and
 * the `<Element>` entry of the layout set it parses.
 *
 * Wave 3 (P8 §2) plans to collapse `KeyboardId` and `KeyboardBuilder`. A resolution regression is
 * invisible until one specific layout comes up in one specific field on one specific device, so
 * this table is the safety net. Everything is pinned AS IT BEHAVES TODAY, including the entries
 * marked `CHARACTERISED BUG`.
 *
 * ## Vocabulary, because the production names mislead
 *
 * `getKeyboardForShift(int i, boolean z)` — `z` is NOT "shifted". `z` selects the PHYSICAL
 * keyboard family: it adds 100 to the element id, and 100 is the VKB->PKB offset in
 * `res/values/attrs.xml`'s `elementName` enum (`alphabet`=0 / `alphabetPkb`=100). Every caller
 * passes `!refreshOnScreenKeyboardShowing()`, i.e. "the on-screen keyboard is hidden, use the
 * hardware layout". This test calls the parameter `pkb`.
 *
 * ## How resolution is observed here
 *
 * The observable output is the `KeyboardId` the builder constructs, plus whether it found an
 * `<Element>` at all. Both are read off `KeyboardLayoutSetException`, which the production code
 * throws with the constructed `KeyboardId` attached:
 *
 *  - no `<Element>` found  -> cause is `IllegalStateException("Missing keyboard layout
 *    configuration")`, thrown before any parsing;
 *  - an `<Element>` found  -> the builder goes on to parse XML, which fails in a unit-test JVM
 *    (no `RichInputMethodManager`) and is re-wrapped with the same `KeyboardId`.
 *
 * That is enough to pin the whole decision, because the fallback chain's priority is observable:
 * the third fallback (and only the third) REWRITES the element id it records. See
 * `fallbackChain_*` below. What it cannot show is which of two simultaneously-declared elements
 * was parsed when neither rewrites the id; see SEAMS NEEDED at the bottom of this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardBuilderElementResolutionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KeyboardBuilder.clearKeyboardCache()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
    }

    // ------------------------------------------------------------------ harness

    /** Sentinel meaning "the builder found no `<Element>` and threw before parsing". */
    private val NO_ELEMENT_FOUND = Int.MIN_VALUE

    private fun subtype(): InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("en_US")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
        .setIsAsciiCapable(true)
        .build()

    /**
     * @param declared the element ids the layout set declares an `<Element>` for.
     * @return the element id recorded on the constructed [dev.bbkb.ime.keyboard.internal.KeyboardId],
     *         or [NO_ELEMENT_FOUND] when no `<Element>` matched.
     */
    private fun resolve(
        mode: Int,
        element: Int,
        pkb: Boolean,
        declared: Set<Int> = ALL_QWERTY_ELEMENTS,
        languageQuickSwitchSeed: Boolean = false
    ): Int {
        val params = KeyboardBuilder.Params()
        params.mMode = mode
        params.mEditorInfo = EditorInfo()
        params.mSubtype = subtype()
        params.mKeyboardWidth = 1080
        params.mKeyboardHeight = 400
        params.mLanguageQuickSwitchKeyEnabled = languageQuickSwitchSeed
        for (key in declared) {
            params.mMoreKeySpecIdToParamsMap.put(key, KeyboardBuilder.ElementParams())
        }
        val builder = KeyboardBuilder(context, params)
        return try {
            // Only reachable if a future rewrite makes XML parsing work headless; handled so the
            // table does not silently depend on the parse failing.
            builder.getKeyboardInternal(element, pkb).mId.mElementId
        } catch (e: KeyboardBuilder.KeyboardLayoutSetException) {
            val cause = e.cause
            if (cause is IllegalStateException &&
                cause.message == "Missing keyboard layout configuration"
            ) {
                NO_ELEMENT_FOUND
            } else {
                e.mKeyboardId.mElementId
            }
        }
    }

    /** Reads back the Params the builder mutated, to observe its side effect on the quick-switch flag. */
    private fun resolveAndReadParams(
        mode: Int,
        element: Int,
        pkb: Boolean,
        languageQuickSwitchSeed: Boolean
    ): KeyboardBuilder.Params {
        val params = KeyboardBuilder.Params()
        params.mMode = mode
        params.mEditorInfo = EditorInfo()
        params.mSubtype = subtype()
        params.mLanguageQuickSwitchKeyEnabled = languageQuickSwitchSeed
        for (key in ALL_QWERTY_ELEMENTS) {
            params.mMoreKeySpecIdToParamsMap.put(key, KeyboardBuilder.ElementParams())
        }
        val builder = KeyboardBuilder(context, params)
        try {
            builder.getKeyboardInternal(element, pkb)
        } catch (ignored: KeyboardBuilder.KeyboardLayoutSetException) {
            // expected: XML parsing cannot run headless
        }
        return params
    }

    private fun checkAll(rows: List<Pair<String, () -> Unit>>) {
        val failures = mutableListOf<String>()
        for ((label, body) in rows) {
            try {
                body()
            } catch (e: AssertionError) {
                failures += "$label: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} of ${rows.size} rows failed:\n" + failures.joinToString("\n")
            )
        }
    }

    companion object {
        /**
         * Exactly the `<Element>` set of `res/xml/keyboard_layout_set_qwerty.xml`, which is the
         * richest layout set in the app. Kept as a literal so a change to that file shows up here
         * as a failing row rather than as silently different coverage.
         */
        private val ALL_QWERTY_ELEMENTS = setOf(
            0,   // alphabet            -> @xml/kbd_qwerty
            100, // alphabetPkb         -> @xml/kbd_alphabet_pkb
            5,   // symbols             -> @xml/kbd_symbols
            8,   // symbolsCustom       -> @xml/kbd_symbols_custom
            105, // symbolsPkb          -> @xml/kbd_symbols_pkb
            108, // symbolsPkbCustom    -> @xml/kbd_symbols_pkb_custom
            6,   // symbolsShifted      -> @xml/kbd_symbols_shift
            106, // symbolsShiftedPkb   -> @xml/kbd_symbols_shift_pkb
            9,   // phone               -> @xml/kbd_phone
            10,  // phoneSymbols        -> @xml/kbd_phone_symbols
            109, // phonePkb            -> @xml/kbd_phone_pkb
            11,  // number              -> @xml/kbd_number
            111, // numberPkb           -> @xml/kbd_number_pkb
            136, // passwordPkb         -> @xml/kbd_password_pkb
            135, // uriPkb              -> @xml/kbd_uri_pkb
            138  // unifiedInputMenuPkb -> @xml/kbd_unified_input_menu_pkb
        )
    }

    // ------------------------------------------------------------------ the arithmetic table

    /**
     * Every (mode, requested element, pkb) triple whose answer differs, resolved against a layout
     * set that declares EVERY element id — so this table isolates the arithmetic in
     * `getKeyboardInternal` from the fallback chain below.
     */
    private data class Row(val mode: Int, val element: Int, val pkb: Boolean, val resolved: Int)

    private val everyElement: Set<Int> =
        ((0..12) + (35..43) + (100..111) + (135..141)).toSet() +
            // the ids the arithmetic can produce that no layout set declares
            setOf(112, 135, 136, 137, 142, 143, 200, 238)

    private val arithmeticTable = listOf(
        // -------- mode 0 (text) / 3 (im) / 9 (lowerRightCornerIsEnterKey): identity, +100 for pkb
        Row(0, 0, false, 0), Row(0, 0, true, 100),
        Row(0, 1, false, 1), Row(0, 1, true, 101),
        Row(0, 4, false, 4), Row(0, 4, true, 104),
        Row(0, 5, false, 5), Row(0, 5, true, 105),
        Row(0, 8, false, 8), Row(0, 8, true, 108),
        Row(0, 11, false, 11), Row(0, 11, true, 111),
        Row(0, 12, false, 12), Row(0, 12, true, 112),
        Row(0, 39, false, 39), Row(0, 39, true, 139),
        Row(0, 41, false, 41), Row(0, 41, true, 141),
        Row(0, 42, false, 42), Row(0, 42, true, 142),
        Row(0, 43, false, 43), Row(0, 43, true, 143),
        Row(3, 0, false, 0), Row(3, 0, true, 100),
        Row(9, 0, false, 0), Row(9, 0, true, 100),
        // CHARACTERISED BUG: nothing stops the +100 from being applied to an id that is ALREADY a
        // PKB id. `BlackBerryIME` and `UnifiedInputBoardManager` both call
        // getKeyboardForShift(138, true) and land on 238, an id that no enum, no layout set and no
        // elementIdToName() knows. It only works because the unified-input-menu layout set happens
        // to declare neither 238 nor 100, so the third fallback rewrites it back to 138 — see
        // fallbackChain_thirdFallbackRewritesTheRecordedElementId. Declare an `alphabetPkb` in
        // that layout set and the menu bar silently becomes the alphabet.
        Row(0, 138, true, 238),
        Row(0, 100, true, 200),

        // -------- mode 1 (url) and 2 (email): pkb maps the ALPHABET family onto `uri` (35), then
        // +100 -> uriPkb (135). Symbol pages and element 38 are exempt.
        Row(1, 0, false, 0), Row(1, 0, true, 135),
        Row(1, 4, false, 4), Row(1, 4, true, 135),
        Row(1, 5, false, 5), Row(1, 5, true, 105),
        Row(1, 6, false, 6), Row(1, 6, true, 106),
        Row(1, 7, false, 7), Row(1, 7, true, 107),
        Row(1, 8, false, 8), Row(1, 8, true, 108),
        Row(1, 38, false, 38), Row(1, 38, true, 138),
        Row(1, 11, false, 11), Row(1, 11, true, 135),
        Row(2, 0, false, 0), Row(2, 0, true, 135),
        Row(2, 5, false, 5), Row(2, 5, true, 105),

        // -------- mode 10 (postal): same shape, but the pkb target is `postal` (37) -> 137
        Row(10, 0, false, 0), Row(10, 0, true, 137),
        Row(10, 4, false, 4), Row(10, 4, true, 137),
        Row(10, 5, false, 5), Row(10, 5, true, 105),
        Row(10, 8, false, 8), Row(10, 8, true, 108),
        Row(10, 38, false, 38), Row(10, 38, true, 138),

        // -------- mode 11 (password): pkb target is `password` (36) -> 136
        Row(11, 0, false, 0), Row(11, 0, true, 136),
        Row(11, 1, false, 1), Row(11, 1, true, 136),
        Row(11, 5, false, 5), Row(11, 5, true, 105),
        Row(11, 6, false, 6), Row(11, 6, true, 106),
        Row(11, 38, false, 38), Row(11, 38, true, 138),

        // -------- mode 4 (phone): the requested element is DISCARDED. Everything becomes `phone`
        // (9), except element 5 which becomes `phoneSymbols` (10).
        Row(4, 0, false, 9), Row(4, 0, true, 109),
        Row(4, 1, false, 9), Row(4, 1, true, 109),
        Row(4, 8, false, 9), Row(4, 8, true, 109),
        Row(4, 38, false, 9), Row(4, 38, true, 109),
        Row(4, 5, false, 10),
        // CHARACTERISED BUG: the element-5 branch assigns `z = false` before the +100, so a phone
        // field on a physical keyboard gets the ON-SCREEN phone-symbols layout. `phoneSymbolsPkb`
        // (110) has an `elementName` enum in attrs.xml but no (mode, element, pkb) triple reaches
        // it except a direct (mode 0, element 10, pkb=true) request that no caller makes — and no
        // layout set in the app declares it either.
        Row(4, 5, true, 10),

        // -------- modes 5..8 (number, date, time, datetime): the requested element is DISCARDED
        // and everything becomes `number` (11) / `numberPkb` (111). A symbols or emoji request in
        // a number field silently yields the number pad.
        Row(5, 0, false, 11), Row(5, 0, true, 111),
        Row(5, 5, false, 11), Row(5, 5, true, 111),
        Row(5, 8, false, 11), Row(5, 8, true, 111),
        Row(5, 38, false, 11), Row(5, 38, true, 111),
        Row(6, 0, false, 11), Row(6, 0, true, 111),
        Row(7, 0, false, 11), Row(7, 0, true, 111),
        Row(8, 0, false, 11), Row(8, 0, true, 111),
        // CHARACTERISED: element 42 (`numberPad`) and 41 (`numberSubPanel`) are NOT reachable in a
        // number field — mode 5..8 rewrites them to 11. Both boards therefore build their own
        // KeyboardBuilder with a null EditorInfo (mode 0); see NumberPadController and
        // NumericSubpanelController.
        Row(5, 42, false, 11),
        Row(5, 41, false, 11),

        // -------- an undeclared mode falls through to the identity branch
        Row(12, 0, false, 0), Row(12, 0, true, 100),
        Row(-1, 7, false, 7)
    )

    @Test
    fun elementArithmetic_pinsModeTimesElementTimesPkb() {
        checkAll(arithmeticTable.map { row ->
            "mode=${row.mode} element=${row.element} pkb=${row.pkb}" to {
                assertEquals(
                    row.resolved,
                    resolve(row.mode, row.element, row.pkb, declared = everyElement)
                )
            }
        })
    }

    /**
     * The same arithmetic, run against the REAL `keyboard_layout_set_qwerty.xml` element set —
     * i.e. arithmetic plus fallback, which is what a user actually gets. The rows that differ from
     * [arithmeticTable] are the ones where the computed id is not declared and the fallback chain
     * has to rescue it.
     */
    @Test
    fun qwertyLayoutSet_pinsWhatEachModeActuallyResolvesTo() {
        checkAll(
            listOf(
                // declared outright
                Triple(0, 0, false) to 0,
                Triple(0, 0, true) to 100,
                Triple(0, 5, false) to 5,
                Triple(0, 5, true) to 105,
                Triple(0, 8, false) to 8,
                Triple(0, 8, true) to 108,
                Triple(0, 11, false) to 11,
                Triple(0, 11, true) to 111,
                // 1/2 (url/email) on a physical keyboard -> uriPkb, which qwerty declares
                Triple(1, 0, true) to 135,
                Triple(2, 0, true) to 135,
                // password on a physical keyboard -> passwordPkb, declared
                Triple(11, 0, true) to 136,
                // postal on a physical keyboard -> 137, which qwerty does NOT declare: the first
                // fallback substitutes alphabetPkb's <Element> but LEAVES the recorded id at 137.
                Triple(10, 0, true) to 137,
                // phone
                Triple(4, 0, false) to 9,
                Triple(4, 0, true) to 109,
                Triple(4, 5, false) to 10,
                Triple(4, 5, true) to 10,
                // number/date/time/datetime
                Triple(5, 0, false) to 11,
                Triple(6, 0, true) to 111,
                // element 7 (symbols2) is not declared by qwerty; on VKB the LAST fallback
                // substitutes `alphabet`'s <Element> while still recording element 7, so the
                // keyboard is built from kbd_qwerty but every `keyboardLayoutSetElement` case in
                // that XML is matched against "symbols2".
                Triple(0, 7, false) to 7,
                Triple(0, 7, true) to 107,
                // the unified input menu
                Triple(0, 138, true) to 238,
                Triple(0, 43, false) to 43
            ).map { (input, expected) ->
                val (mode, element, pkb) = input
                "qwerty mode=$mode element=$element pkb=$pkb" to {
                    assertEquals(expected, resolve(mode, element, pkb))
                }
            }
        )
    }

    // ------------------------------------------------------------------ the fallback chain

    @Test
    fun fallbackChain_directHitWhenTheComputedIdIsDeclared() {
        assertEquals(105, resolve(0, 5, pkb = true, declared = setOf(105, 100, 5, 0)))
        assertEquals(5, resolve(0, 5, pkb = false, declared = setOf(105, 100, 5, 0)))
    }

    @Test
    fun fallbackChain_missingEverythingThrowsBeforeParsing() {
        assertEquals(NO_ELEMENT_FOUND, resolve(0, 5, pkb = true, declared = emptySet()))
        assertEquals(NO_ELEMENT_FOUND, resolve(0, 5, pkb = false, declared = emptySet()))
    }

    /**
     * CHARACTERISED BUG: fallback 1 (element 100, `alphabetPkb`) and fallback 3 (element 0,
     * `alphabet`) substitute a DIFFERENT layout while leaving the requested element id on the
     * `KeyboardId`. Two consequences, both live:
     *
     *  - `KeyboardXMLParser` matches `<case latin:keyboardLayoutSetElement="...">` against the
     *    recorded id, so the substituted XML is parsed under the name of a layout it is not;
     *  - `sKeyboardCache` is keyed on that `KeyboardId`, so the cache entry claims to be the
     *    requested element.
     *
     * Only fallback 2 corrects the id, and it is the one that runs LAST of the three for a
     * physical-keyboard request.
     */
    @Test
    fun fallbackChain_firstFallbackKeepsTheRequestedIdButUsesAlphabetPkb() {
        // pkb request for symbols (105) with only alphabetPkb declared.
        assertEquals(105, resolve(0, 5, pkb = true, declared = setOf(100)))
        // ...and the id is likewise NOT rewritten for the unified-input-menu 238 case.
        assertEquals(238, resolve(0, 138, pkb = true, declared = setOf(100)))
    }

    @Test
    fun fallbackChain_lastFallbackKeepsTheRequestedIdButUsesElementZero() {
        // Non-pkb request: fallbacks 1 and 2 are gated on `pkb`, so element 0 is the only rescue.
        assertEquals(7, resolve(0, 7, pkb = false, declared = setOf(0)))
        assertEquals(42, resolve(0, 42, pkb = false, declared = setOf(0)))
    }

    /**
     * Fallback 2 — and only fallback 2 — rewrites the recorded element id, from the pkb id back to
     * the on-screen one. This is what makes `getKeyboardForShift(138, true)` work: the
     * unified-input-menu layout set declares 138 and 43 and nothing else, so 238 misses, 100
     * misses, and 138 is recovered with the id corrected.
     */
    @Test
    fun fallbackChain_secondFallbackRewritesTheRecordedElementId() {
        assertEquals(138, resolve(0, 138, pkb = true, declared = setOf(138, 43)))
        assertEquals(5, resolve(0, 5, pkb = true, declared = setOf(5)))
    }

    /**
     * Priority, proved pairwise through the one observable the chain exposes (whether the id gets
     * rewritten): 100 beats the non-pkb id, and the non-pkb id beats 0. By transitivity the order
     * is `computed id` > 100 > `non-pkb id` > 0.
     */
    @Test
    fun fallbackChain_priorityIsComputedThen100ThenNonPkbThenZero() {
        // Both 100 and 5 declared: if 5 had won, the id would have been rewritten to 5.
        assertEquals(105, resolve(0, 5, pkb = true, declared = setOf(100, 5)))
        // Both 5 and 0 declared: 5 wins, and rewrites the id.
        assertEquals(5, resolve(0, 5, pkb = true, declared = setOf(5, 0)))
        // The computed id beats all of them.
        assertEquals(105, resolve(0, 5, pkb = true, declared = setOf(105, 100, 5, 0)))
    }

    /**
     * CHARACTERISED: the `i3 != 100` guard on fallback 1 exists only to stop it re-testing the id
     * that just missed. When the request IS element 0 on a physical keyboard and `alphabetPkb` is
     * absent, the chain skips straight to fallback 2 and drops the whole keyboard back to the
     * on-screen `alphabet` — silently, with the id rewritten to 0.
     */
    @Test
    fun fallbackChain_pkbAlphabetRequestFallsBackToTheOnScreenAlphabet() {
        assertEquals(0, resolve(0, 0, pkb = true, declared = setOf(0)))
    }

    // ------------------------------------------------------------------ side effects

    /**
     * `getKeyboardInternal` OVERWRITES `Params.mLanguageQuickSwitchKeyEnabled` on every call, and
     * keys the decision off the REQUESTED element id, not the resolved one. Symbol pages (5..8)
     * and element 38 force it off; everything else asks `SettingsManager`, which without an
     * initialised settings snapshot answers false — so in this environment every row is false and
     * the caller's seeded `true` is discarded.
     */
    @Test
    fun languageQuickSwitchFlag_isOverwrittenOnEveryResolution() {
        checkAll(
            listOf(0, 1, 5, 6, 7, 8, 38, 42, 100, 138).map { element ->
                "quickSwitch after element=$element" to {
                    val params = resolveAndReadParams(0, element, pkb = false, languageQuickSwitchSeed = true)
                    assertFalse(params.mLanguageQuickSwitchKeyEnabled)
                }
            }
        )
    }

    /**
     * `getKeyboard(i)` is `getKeyboardForShift(i, false)`, and `getKeyboardForShift` is
     * `getKeyboardInternal` — pinned because Wave 3 is expected to delete at least two of the
     * three.
     */
    @Test
    fun getKeyboard_isGetKeyboardForShiftWithPkbFalse() {
        val viaGetKeyboard = resolveVia(0, 5) { builder, element -> builder.getKeyboard(element) }
        val viaShiftFalse = resolveVia(0, 5) { builder, element ->
            builder.getKeyboardForShift(element, false)
        }
        assertEquals(5, viaGetKeyboard)
        assertEquals(viaGetKeyboard, viaShiftFalse)
    }

    private fun resolveVia(mode: Int, element: Int, call: (KeyboardBuilder, Int) -> Keyboard): Int {
        val params = KeyboardBuilder.Params()
        params.mMode = mode
        params.mEditorInfo = EditorInfo()
        params.mSubtype = subtype()
        for (key in ALL_QWERTY_ELEMENTS) {
            params.mMoreKeySpecIdToParamsMap.put(key, KeyboardBuilder.ElementParams())
        }
        return try {
            call(KeyboardBuilder(context, params), element).mId.mElementId
        } catch (e: KeyboardBuilder.KeyboardLayoutSetException) {
            e.mKeyboardId.mElementId
        }
    }

    @Test
    fun hasPkbLayout_reportsWhetherElement100IsDeclared() {
        assertTrue(builderWith(setOf(100, 0)).hasPkbLayout())
        assertFalse(builderWith(setOf(0, 5, 138)).hasPkbLayout())
        // NOTE: only element 100 counts. A layout set with every OTHER pkb element (the
        // unified-input-menu set, for instance) reports "no physical keyboard layout".
        assertFalse(builderWith(setOf(105, 109, 111, 135, 136, 138)).hasPkbLayout())
    }

    @Test
    fun getSupportedScriptId_defaultsToLatin() {
        assertEquals(14, builderWith(setOf(0)).getSupportedScriptId())
    }

    private fun builderWith(declared: Set<Int>): KeyboardBuilder {
        val params = KeyboardBuilder.Params()
        params.mMode = 0
        params.mEditorInfo = EditorInfo()
        params.mSubtype = subtype()
        for (key in declared) {
            params.mMoreKeySpecIdToParamsMap.put(key, KeyboardBuilder.ElementParams())
        }
        return KeyboardBuilder(context, params)
    }

    // ------------------------------------------------------------------ mode derivation

    /**
     * `Builder.getKeyboardMode(EditorInfo)` is the other half of the resolution: it turns an
     * editor's inputType into the mode the table above switches on. It is `private static`, so it
     * is reached reflectively — no production visibility was widened for this test.
     */
    private val getKeyboardMode = KeyboardBuilder.Builder::class.java
        .getDeclaredMethod("getKeyboardMode", EditorInfo::class.java)
        .apply { isAccessible = true }

    private val lowerRightCornerIsEnterField = KeyboardBuilder::class.java
        .getDeclaredField("sLowerRightCornerIsEnter")
        .apply { isAccessible = true }

    private fun mode(inputType: Int): Int {
        val editorInfo = EditorInfo().also { it.inputType = inputType }
        return getKeyboardMode.invoke(null, editorInfo) as Int
    }

    private val TEXT = InputType.TYPE_CLASS_TEXT
    private val NUMBER = InputType.TYPE_CLASS_NUMBER
    private val PHONE = InputType.TYPE_CLASS_PHONE
    private val DATETIME = InputType.TYPE_CLASS_DATETIME

    @Test
    fun getKeyboardMode_pinsInputTypeToMode() {
        lowerRightCornerIsEnterField.setBoolean(null, false)
        try {
            checkAll(
                listOf(
                    // TYPE_CLASS_TEXT: the variation decides
                    "plain text" to (TEXT to 0),
                    "email" to ((TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) to 2),
                    "web email" to ((TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) to 2),
                    "uri" to ((TEXT or InputType.TYPE_TEXT_VARIATION_URI) to 1),
                    // variation 64 == TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "im"
                    "short message" to ((TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE) to 3),
                    "web password" to ((TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to 11),
                    "postal" to ((TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS) to 10),
                    "password" to ((TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 11),
                    "visible password" to ((TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to 11),
                    // variation 176 == TYPE_TEXT_VARIATION_FILTER is short-circuited to plain text
                    // ahead of the password test, and it is the ONLY variation given that
                    // treatment; every other unhandled variation reaches the password fallthrough.
                    "filter variation" to ((TEXT or InputType.TYPE_TEXT_VARIATION_FILTER) to 0),
                    "person name" to ((TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME) to 0),
                    // flags above the variation mask are ignored
                    "multiline text" to ((TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) to 0),
                    "cap-sentences uri" to
                        ((TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) to 1),
                    // other classes ignore the variation entirely
                    "number" to (NUMBER to 5),
                    "number password" to ((NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to 5),
                    "phone" to (PHONE to 4),
                    // CHARACTERISED: unlike KeyboardId.isPhoneLayout(), this masks first, so a
                    // flagged phone field still gets mode 4 (phone layout) while isPhoneLayout()
                    // returns false for it.
                    "phone + flag" to ((PHONE or InputType.TYPE_TEXT_FLAG_MULTI_LINE) to 4),
                    "datetime" to (DATETIME to 8),
                    "date" to ((DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE) to 6),
                    "time" to ((DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME) to 7),
                    // TYPE_NULL and any unknown class fall through to text
                    "null input type" to (InputType.TYPE_NULL to 0),
                    "unknown class 5" to (5 to 0)
                ).map { (label, pair) ->
                    val (inputType, expected) = pair
                    "$label(0x${Integer.toHexString(inputType)})" to {
                        assertEquals(expected, mode(inputType))
                    }
                }
            )
        } finally {
            lowerRightCornerIsEnterField.setBoolean(null, false)
        }
    }

    /**
     * The one mode that is not a pure function of the inputType: `R.bool.lower_right_corner_is_enter`
     * (false in `values/bools.xml`, overridable per device) turns the "im" mode into mode 9,
     * `lowerRightCornerIsEnterKey`. The flag is a MUTABLE STATIC on `KeyboardBuilder`, written by
     * every `Builder` constructor — see SEAMS NEEDED.
     */
    @Test
    fun getKeyboardMode_shortMessageBecomesMode9WhenLowerRightCornerIsEnter() {
        val shortMessage = TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
        lowerRightCornerIsEnterField.setBoolean(null, false)
        try {
            assertEquals(3, mode(shortMessage))
            lowerRightCornerIsEnterField.setBoolean(null, true)
            assertEquals(9, mode(shortMessage))
            // ...and nothing else moves.
            assertEquals(0, mode(TEXT))
            assertEquals(1, mode(TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        } finally {
            lowerRightCornerIsEnterField.setBoolean(null, false)
        }
    }

    /**
     * The device default: `values/bools.xml` ships `lower_right_corner_is_enter = false`, and the
     * `Builder` constructor copies it into the static. Pinned because mode 9 has no other trigger
     * and a flipped default would silently re-route every short-message field.
     */
    @Test
    fun builderConstructor_seedsLowerRightCornerIsEnterFromResources() {
        lowerRightCornerIsEnterField.setBoolean(null, true)
        KeyboardBuilder.Builder(context, EditorInfo())
        assertFalse(lowerRightCornerIsEnterField.getBoolean(null))
    }

    // ------------------------------------------------------------------
    // SEAMS NEEDED (Wave 3)
    //
    // 1. Element resolution is not separable from keyboard construction. `getKeyboardInternal`
    //    computes the element id, picks an `<Element>`, constructs a `KeyboardId` AND parses XML
    //    in one method, so this test can only see the resolution through the exception the parse
    //    throws. Wave 3 should extract a pure
    //        resolveElement(mode: Int, requested: Int, pkb: Boolean, declared: IntSet): Resolution
    //    returning both the recorded element id and the `<Element>` key that was chosen. That one
    //    seam would let every row above assert the layout as well as the id, and would make the
    //    id-vs-layout divergence pinned in `fallbackChain_firstFallbackKeepsTheRequestedId...`
    //    expressible instead of merely inferable.
    //
    // 2. `sLowerRightCornerIsEnter` is a mutable private static written by the `Builder`
    //    constructor and read by the static `getKeyboardMode`. It is reachable here only by
    //    reflection, and it makes mode derivation order-dependent across builders. Wave 3 should
    //    pass it as a `Params` field.
    //
    // 3. `sKeyboardCache` / `sKeysCache` are private statics with only a coarse
    //    `clearKeyboardCache()`. There is no way to observe a hit versus a miss, so the caching
    //    branch of `buildKeyboard` (including its custom-symbol refresh for elements 8/108) is
    //    untested here. Wave 3 should make the cache an injected collaborator.
    //
    // 4. `KeyboardXMLParser` construction reaches `RichInputMethodManager.getInstance()` through
    //    `SubtypeDisplayNameHelper`, which throws in a unit-test JVM. Until that is injected,
    //    nothing downstream of `<Element>` selection can be exercised headless.
    // ------------------------------------------------------------------
}
