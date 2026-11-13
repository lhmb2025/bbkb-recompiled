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
 * Pins `KeyboardBuilder`'s element resolution: (mode, requested element, pkb) to the `KeyboardId`
 * it builds and the `<Element>` it picks. `getKeyboardForShift(i, z)`'s `z` is not "shifted": it
 * selects the physical-keyboard family (+100 to the element id), called `pkb` here.
 *
 * Observed through `KeyboardLayoutSetException`, which carries the built `KeyboardId`: no
 * `<Element>` gives "Missing keyboard layout configuration"; a found one fails later in XML parsing.
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
            // Only reached if XML parsing ever works headless.
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
        /** The `<Element>` set of `res/xml/keyboard_layout_set_qwerty.xml`, kept as a literal. */
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

    /** Resolved against a set declaring every id, isolating the arithmetic from the fallback chain. */
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
        // Known bug: +100 is applied to an id that is already PKB. getKeyboardForShift(138, true)
        // gives 238, which nothing declares; it works only because the input menu layout set has
        // neither 238 nor 100, so the fallback restores 138. Adding `alphabetPkb` there breaks it.
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
        // Known bug: the element-5 branch clears pkb before the +100, so a physical-keyboard phone
        // field gets the on-screen phone symbols. `phoneSymbolsPkb` (110) is otherwise unreachable.
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
        // 42 (`numberPad`) and 41 (`numberSubPanel`) are unreachable in a number field, so
        // NumberPadController and NumericSubpanelController build with a null EditorInfo (mode 0).
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

    /** Arithmetic plus fallback against the real qwerty element set, i.e. what a user gets. */
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
                // element 7 (symbols2) is not declared: the last fallback builds kbd_qwerty but
                // still records element 7, so its `keyboardLayoutSetElement` cases see "symbols2".
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
     * Known bug: fallbacks 1 (100) and 3 (0) substitute another layout but keep the requested id,
     * so `<case keyboardLayoutSetElement>` and `sKeyboardCache` both see the wrong element.
     * Only fallback 2 corrects the id.
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
     * Only fallback 2 rewrites the id (pkb id back to on-screen). That is what rescues
     * `getKeyboardForShift(138, true)`: the input menu set declares only 138 and 43.
     */
    @Test
    fun fallbackChain_secondFallbackRewritesTheRecordedElementId() {
        assertEquals(138, resolve(0, 138, pkb = true, declared = setOf(138, 43)))
        assertEquals(5, resolve(0, 5, pkb = true, declared = setOf(5)))
    }

    /** Priority, proved pairwise via the id rewrite: computed id > 100 > non-pkb id > 0. */
    @Test
    fun fallbackChain_priorityIsComputedThen100ThenNonPkbThenZero() {
        // Both 100 and 5 declared: if 5 had won, the id would have been rewritten to 5.
        assertEquals(105, resolve(0, 5, pkb = true, declared = setOf(100, 5)))
        // Both 5 and 0 declared: 5 wins, and rewrites the id.
        assertEquals(5, resolve(0, 5, pkb = true, declared = setOf(5, 0)))
        // The computed id beats all of them.
        assertEquals(105, resolve(0, 5, pkb = true, declared = setOf(105, 100, 5, 0)))
    }

    /** A pkb alphabet request with no `alphabetPkb` silently falls back to the on-screen alphabet (id 0). */
    @Test
    fun fallbackChain_pkbAlphabetRequestFallsBackToTheOnScreenAlphabet() {
        assertEquals(0, resolve(0, 0, pkb = true, declared = setOf(0)))
    }

    /**
     * `getKeyboardInternal` overwrites `mLanguageQuickSwitchKeyEnabled` on every call, keyed off the
     * requested id: 5..8 and 38 force it off, the rest ask `SettingsManager` (false here, no snapshot).
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

    /** `getKeyboard(i)` is `getKeyboardForShift(i, false)`. */
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
        // Only element 100 counts, not other pkb elements.
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

    /** Private static `Builder.getKeyboardMode(EditorInfo)`: inputType to mode, via reflection. */
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
                    // TYPE_TEXT_VARIATION_FILTER (176) is short-circuited to text ahead of the
                    // password test; other unhandled variations reach the password fallthrough.
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
                    // Masks first, so a flagged phone field gets mode 4 even though
                    // KeyboardId.isPhoneLayout() returns false for it.
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
     * `R.bool.lower_right_corner_is_enter` turns "im" (3) into mode 9. It lives in a mutable static
     * on `KeyboardBuilder` written by every `Builder` constructor.
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

    /** The `Builder` constructor copies the default `lower_right_corner_is_enter = false` into the static. */
    @Test
    fun builderConstructor_seedsLowerRightCornerIsEnterFromResources() {
        lowerRightCornerIsEnterField.setBoolean(null, true)
        KeyboardBuilder.Builder(context, EditorInfo())
        assertFalse(lowerRightCornerIsEnterField.getBoolean(null))
    }
}
