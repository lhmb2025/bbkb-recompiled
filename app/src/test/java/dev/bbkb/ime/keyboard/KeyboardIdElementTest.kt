package dev.bbkb.ime.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.keyboard.internal.KeyboardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [KeyboardId]: element-id naming, the three element predicates, the editor-flag derived
 * properties, and the identity/`toString` contract. Element ids are raw literals in production
 * (no named constants), so these tables are what catches a renumbering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyboardIdElementTest {
    private fun subtype(
        locale: String = "en_US",
        layoutSet: String = "qwerty"
    ): InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale(locale)
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=$layoutSet")
        .setIsAsciiCapable(true)
        .build()

    private fun editorInfo(
        inputType: Int = 0,
        imeOptions: Int = 0,
        actionLabel: CharSequence? = null,
        fieldId: Int = 0,
        packageName: String? = null,
        privateImeOptions: String? = null
    ): EditorInfo = EditorInfo().also {
        it.inputType = inputType
        it.imeOptions = imeOptions
        it.actionLabel = actionLabel
        it.fieldId = fieldId
        it.packageName = packageName
        it.privateImeOptions = privateImeOptions
    }

    private fun id(
        elementId: Int,
        mode: Int = 0,
        editorInfo: EditorInfo = editorInfo(),
        width: Int = 1080,
        height: Int = 400,
        subtype: InputMethodSubtype = subtype(),
        clobberSettingsKey: Boolean = false,
        voiceKeyEnabled: Boolean = false,
        languageSwitchKeyEnabled: Boolean = false,
        languageQuickSwitchKeyEnabled: Boolean = false,
        inputBoardBarEnabled: Boolean = false
    ): KeyboardId {
        val p = KeyboardBuilder.Params()
        p.mMode = mode
        p.mEditorInfo = editorInfo
        p.mSubtype = subtype
        p.mKeyboardWidth = width
        p.mKeyboardHeight = height
        p.mClobberSettingsKey = clobberSettingsKey
        p.mVoiceKeyEnabled = voiceKeyEnabled
        p.mLanguageSwitchKeyEnabled = languageSwitchKeyEnabled
        p.mLanguageQuickSwitchKeyEnabled = languageQuickSwitchKeyEnabled
        p.mInputBoardBarEnabled = inputBoardBarEnabled
        return KeyboardId(elementId, p)
    }

    /** Collects every mismatching row so one run reports the whole diff, not just the first. */
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

    /**
     * name = expected [KeyboardId.elementIdToName]; `null` means the id has no reachable name.
     * alphabet/symbols/pkb/typing = the four element predicates the class exposes.
     */
    private data class ElementRow(
        val elementId: Int,
        val name: String?,
        val alphabet: Boolean,
        val symbols: Boolean,
        val pkb: Boolean,
        val typing: Boolean
    )

    private val elementTable = listOf(
        // VKB alphabet family: with 35..37, the only ids isAlphabetKeyboard() accepts
        ElementRow(0, "alphabet", true, false, false, true),
        ElementRow(1, "alphabetManualShifted", true, false, false, true),
        ElementRow(2, "alphabetAutomaticShifted", true, false, false, true),
        ElementRow(3, "alphabetShiftLocked", true, false, false, true),
        ElementRow(4, "alphabetShiftLockShifted", true, false, false, true),
        // VKB symbols family
        ElementRow(5, "symbols0", false, true, false, true),
        ElementRow(6, "symbols1", false, true, false, true),
        ElementRow(7, "symbols2", false, true, false, true),
        ElementRow(8, "symbolsCustom", false, true, false, true),
        // phone / number / emoji: named, but in no family
        ElementRow(9, "phone", false, false, false, true),
        ElementRow(10, "phoneSymbols", false, false, false, true),
        ElementRow(11, "number", false, false, false, true),
        ElementRow(12, "emojiRecents", false, false, false, true),
        // the 13..34 hole
        ElementRow(13, null, false, false, false, true),
        ElementRow(20, null, false, false, false, true),
        ElementRow(34, null, false, false, false, true),
        // 35..37 are alphabet-classified despite being uri/password/postal layouts
        ElementRow(35, "uri", true, false, false, true),
        ElementRow(36, "password", true, false, false, true),
        ElementRow(37, "postal", true, false, false, true),
        // Known bug: 38 (VKB input menu) is special-cased in several places, yet has no name and no
        // attrs.xml enum, so no layout set can name it and toString reads "[null ...".
        ElementRow(38, null, false, false, false, false),
        ElementRow(39, "arrows", false, false, false, true),
        ElementRow(40, "passwordKeeper", false, false, false, true),
        ElementRow(41, "numberSubPanel", false, false, false, true),
        ElementRow(42, "numberPad", false, false, false, true),
        ElementRow(43, "unifiedInputMenuPool", false, false, false, true),
        ElementRow(44, null, false, false, false, true),
        ElementRow(99, null, false, false, false, true),
        // PKB alphabet family
        ElementRow(100, "alphabetPkb", false, false, true, true),
        ElementRow(101, "alphabetManualShiftedPkb", false, false, true, true),
        ElementRow(102, "alphabetAutomaticShiftedPkb", false, false, true, true),
        ElementRow(103, "alphabetShiftLockedPkb", false, false, true, true),
        ElementRow(104, "alphabetShiftLockShiftedPkb", false, false, true, true),
        // Known bug: PKB symbol ids are neither isSymbolsKeyboard() (5..8 only) nor isPkbKeyboard()
        // (alphabet-ish ids only); see FlickSuggestionView.shouldShow().
        ElementRow(105, "symbols0Pkb", false, false, false, true),
        ElementRow(106, "symbols1Pkb", false, false, false, true),
        ElementRow(107, "symbols2Pkb", false, false, false, true),
        ElementRow(108, "symbolsPkbCustom", false, false, false, true),
        ElementRow(109, "phonePkb", false, false, false, true),
        ElementRow(110, "phoneSymbolsPkb", false, false, false, true),
        ElementRow(111, "numberPkb", false, false, false, true),
        // the 112..134 hole
        ElementRow(112, null, false, false, false, true),
        ElementRow(134, null, false, false, false, true),
        ElementRow(135, "uriPkb", false, false, true, true),
        ElementRow(136, "passwordPkb", false, false, true, true),
        ElementRow(137, "postalPkb", false, false, true, true),
        // 138 (PKB input menu): named, not a typing keyboard (never cached), in no family.
        ElementRow(138, "unifiedInputMenuPkb", false, false, false, false),
        ElementRow(139, "arrowsPkb", false, false, false, true),
        ElementRow(140, "passwordKeeperPkb", false, false, false, true),
        ElementRow(141, "numberSubPanelPkb", false, false, false, true),
        ElementRow(142, null, false, false, false, true),
        // 238: getKeyboardForShift(138, true) before the fallback chain (KeyboardBuilderElementResolutionTest).
        ElementRow(238, null, false, false, false, true),
        ElementRow(-1, null, false, false, false, true)
    )

    @Test
    fun elementIdToName_pinsEveryDeclaredElementId() {
        checkAll(elementTable.map { row ->
            "elementIdToName(${row.elementId})" to {
                assertEquals(row.name, KeyboardId.elementIdToName(row.elementId))
            }
        })
    }

    @Test
    fun elementPredicates_pinTheAlphabetSymbolsAndPkbFamilies() {
        checkAll(elementTable.flatMap { row ->
            val keyboardId = id(row.elementId)
            listOf(
                "isAlphabetKeyboard(${row.elementId})" to {
                    assertEquals(row.alphabet, keyboardId.isAlphabetKeyboard())
                },
                "isSymbolsKeyboard(${row.elementId})" to {
                    assertEquals(row.symbols, keyboardId.isSymbolsKeyboard())
                },
                "isPkbKeyboard(${row.elementId})" to {
                    assertEquals(row.pkb, keyboardId.isPkbKeyboard())
                },
                "isTypingKeyboard(${row.elementId})" to {
                    assertEquals(row.typing, keyboardId.isTypingKeyboard())
                }
            )
        })
    }

    /**
     * The three element families are mutually exclusive and far from exhaustive (31 of 51 ids are
     * in none). `isNumberLayout()` / `isPhoneLayout()` ask the EditorInfo, not the element id.
     */
    @Test
    fun elementFamilies_areDisjointAndLeaveMostIdsUnclassified() {
        var unclassified = 0
        checkAll(elementTable.map { row ->
            val keyboardId = id(row.elementId)
            "families(${row.elementId})" to {
                val hits = listOf(
                    keyboardId.isAlphabetKeyboard(),
                    keyboardId.isSymbolsKeyboard(),
                    keyboardId.isPkbKeyboard()
                ).count { it }
                assertTrue("at most one family may claim an element", hits <= 1)
                if (hits == 0) unclassified++
            }
        })
        assertEquals(31, unclassified)
    }

    @Test
    fun modeName_pinsEveryDeclaredMode() {
        val expected = listOf(
            0 to "text", 1 to "url", 2 to "email", 3 to "im", 4 to "phone", 5 to "number",
            6 to "date", 7 to "time", 8 to "datetime", 9 to "lowerRightCornerIsEnterKey",
            10 to "postal", 11 to "password",
            12 to null, -1 to null, 100 to null
        )
        checkAll(expected.map { (mode, name) ->
            "modeName($mode)" to { assertEquals(name, KeyboardId.modeName(mode)) }
        })
    }

    private data class EditorRow(
        val label: String,
        val inputType: Int,
        val imeOptions: Int = 0,
        val actionLabel: CharSequence? = null,
        val password: Boolean,
        val number: Boolean,
        val phone: Boolean,
        val dateTime: Boolean,
        val multiLine: Boolean,
        val imeAction: Int,
        val navNext: Boolean,
        val navPrev: Boolean
    )

    private val TEXT = InputType.TYPE_CLASS_TEXT
    private val NUMBER = InputType.TYPE_CLASS_NUMBER
    private val PHONE = InputType.TYPE_CLASS_PHONE
    private val DATETIME = InputType.TYPE_CLASS_DATETIME
    private val MULTILINE_FLAG = InputType.TYPE_TEXT_FLAG_MULTI_LINE // 0x20000
    private val NAVIGATE_NEXT = EditorInfo.IME_FLAG_NAVIGATE_NEXT // 0x8000000
    private val NAVIGATE_PREVIOUS = EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS // 0x4000000

    private val editorTable = listOf(
        EditorRow(
            "plain text", TEXT,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "text password", TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            password = true, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "web password", TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            password = true, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        // Visible password counts as passwordInput() even though isPasswordInputType() rejects it:
        // KeyboardId.passwordInput() ORs the two InputTypeUtils predicates together.
        EditorRow(
            "visible password", TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            password = true, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        // A numeric PIN field is BOTH passwordInput() and isNumberLayout().
        EditorRow(
            "number password", NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            password = true, number = true, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "number", NUMBER,
            password = false, number = true, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        // The signed/decimal flags live above TYPE_MASK_VARIATION, so isNumberInputType() masks
        // them off and still says "number" (InputTypeUtils documents this).
        EditorRow(
            "number signed+decimal",
            NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            password = false, number = true, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "phone", PHONE,
            password = false, number = false, phone = true, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        // Known bug: isPhoneLayout() is a raw `inputType == TYPE_CLASS_PHONE` check without
        // masking, so a phone field with any flag is not a phone layout, while getKeyboardMode()
        // still returns mode 4.
        EditorRow(
            "phone + multiline flag", PHONE or MULTILINE_FLAG,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = true, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "datetime", DATETIME,
            password = false, number = false, phone = false, dateTime = true,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "date", DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
            password = false, number = false, phone = false, dateTime = true,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "time", DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            password = false, number = false, phone = false, dateTime = true,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "multiline text", TEXT or MULTILINE_FLAG,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = true, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "url variation", TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "email variation", TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = false, navPrev = false
        ),
        EditorRow(
            "action search", TEXT, imeOptions = EditorInfo.IME_ACTION_SEARCH,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_SEARCH,
            navNext = false, navPrev = false
        ),
        // IME_ACTION_NEXT implies navigateNext() even with no navigation flag set.
        EditorRow(
            "action next", TEXT, imeOptions = EditorInfo.IME_ACTION_NEXT,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_NEXT,
            navNext = true, navPrev = false
        ),
        EditorRow(
            "action previous", TEXT, imeOptions = EditorInfo.IME_ACTION_PREVIOUS,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_PREVIOUS,
            navNext = false, navPrev = true
        ),
        EditorRow(
            "navigate flags only", TEXT, imeOptions = NAVIGATE_NEXT or NAVIGATE_PREVIOUS,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_UNSPECIFIED,
            navNext = true, navPrev = true
        ),
        // An actionLabel wins: imeAction() reports the IME_ACTION_CUSTOM_LABEL sentinel
        // (IME_MASK_ACTION + 1 == 256), so navigateNext()'s `== IME_ACTION_NEXT` fails.
        EditorRow(
            "custom action label", TEXT, imeOptions = EditorInfo.IME_ACTION_NEXT,
            actionLabel = "Zap",
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_MASK_ACTION + 1,
            navNext = false, navPrev = false
        ),
        // IME_FLAG_NO_ENTER_ACTION on done/next drops the action to IME_ACTION_NONE, and with it
        // navigateNext().
        EditorRow(
            "no-enter-action + next", TEXT or MULTILINE_FLAG,
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = true, imeAction = EditorInfo.IME_ACTION_NONE,
            navNext = false, navPrev = false
        ),
        // Environment-dependent: multi-line + a non-done/next action hits the
        // `!isGoogleServicesDisabled()` disjunct (true under Robolectric), so NO_ENTER_ACTION
        // suppresses SEARCH too. A de-Googled ROM on API > 23 keeps IME_ACTION_SEARCH.
        EditorRow(
            "no-enter-action + search, multiline", TEXT or MULTILINE_FLAG,
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = true, imeAction = EditorInfo.IME_ACTION_NONE,
            navNext = false, navPrev = false
        ),
        // ...but the explicit navigation FLAG survives IME_FLAG_NO_ENTER_ACTION.
        EditorRow(
            "no-enter-action + navigate flags", TEXT,
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or NAVIGATE_NEXT or NAVIGATE_PREVIOUS,
            password = false, number = false, phone = false, dateTime = false,
            multiLine = false, imeAction = EditorInfo.IME_ACTION_NONE,
            navNext = true, navPrev = true
        )
    )

    @Test
    fun editorDerivedProperties_pinPasswordNumberPhoneDateTimeMultiLineAndAction() {
        checkAll(editorTable.flatMap { row ->
            val keyboardId = id(
                elementId = 0,
                editorInfo = editorInfo(row.inputType, row.imeOptions, row.actionLabel)
            )
            listOf(
                "${row.label}/passwordInput" to { assertEquals(row.password, keyboardId.passwordInput()) },
                "${row.label}/isNumberLayout" to { assertEquals(row.number, keyboardId.isNumberLayout()) },
                "${row.label}/isPhoneLayout" to { assertEquals(row.phone, keyboardId.isPhoneLayout()) },
                "${row.label}/isDateTimeLayout" to { assertEquals(row.dateTime, keyboardId.isDateTimeLayout()) },
                "${row.label}/isMultiLine" to { assertEquals(row.multiLine, keyboardId.isMultiLine()) },
                "${row.label}/imeAction" to { assertEquals(row.imeAction, keyboardId.imeAction()) },
                "${row.label}/navigateNext" to { assertEquals(row.navNext, keyboardId.navigateNext()) },
                "${row.label}/navigatePrevious" to { assertEquals(row.navPrev, keyboardId.navigatePrevious()) }
            )
        })
    }

    /**
     * `mCustomActionLabel` is snapshotted as a String at construction and participates in
     * equals/hashCode; a null actionLabel leaves it null.
     */
    @Test
    fun customActionLabel_isSnapshottedFromTheEditorInfo() {
        assertEquals(null, id(0).mCustomActionLabel)
        assertEquals("Zap", id(0, editorInfo = editorInfo(TEXT, 0, "Zap")).mCustomActionLabel)
    }

    @Test
    fun actionName_pinsEveryImeAction() {
        val expected = listOf(
            EditorInfo.IME_ACTION_UNSPECIFIED to "actionUnspecified",
            EditorInfo.IME_ACTION_NONE to "actionNone",
            EditorInfo.IME_ACTION_GO to "actionGo",
            EditorInfo.IME_ACTION_SEARCH to "actionSearch",
            EditorInfo.IME_ACTION_SEND to "actionSend",
            EditorInfo.IME_ACTION_NEXT to "actionNext",
            EditorInfo.IME_ACTION_DONE to "actionDone",
            EditorInfo.IME_ACTION_PREVIOUS to "actionPrevious",
            8 to "actionUnknown(8)",
            255 to "actionUnknown(255)",
            // The custom-label sentinel is intercepted before masking...
            (EditorInfo.IME_MASK_ACTION + 1) to "actionCustomLabel",
            // ...but anything else is masked down to IME_MASK_ACTION first, so the high bits of a
            // full imeOptions word are ignored.
            (EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_SEARCH) to "actionSearch",
            (EditorInfo.IME_MASK_ACTION + 1 + 256) to "actionUnspecified"
        )
        checkAll(expected.map { (action, name) ->
            "actionName($action)" to { assertEquals(name, KeyboardId.actionName(action)) }
        })
    }

    @Test
    fun equalsAndHashCode_ignoreEditorInfoDetailsThatDoNotChangeTheDerivedProperties() {
        // Same element/mode/geometry/subtype and derived properties -> equal although the
        // EditorInfos differ, so they share one entry in KeyboardBuilder's keyboard cache.
        val a = id(0, editorInfo = editorInfo(TEXT))
        val b = id(0, editorInfo = editorInfo(TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // ...and mode is what separates them in practice.
        assertNotEquals(a, id(0, mode = 1, editorInfo = editorInfo(TEXT)))
    }

    @Test
    fun equalsAndHashCode_separateEveryFieldTheContractNames() {
        val base = id(0)
        val differing = listOf(
            "elementId" to id(1),
            "mode" to id(0, mode = 5),
            "width" to id(0, width = 720),
            "height" to id(0, height = 401),
            "passwordInput" to id(0, editorInfo = editorInfo(TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)),
            "clobberSettingsKey" to id(0, clobberSettingsKey = true),
            "voiceKeyEnabled" to id(0, voiceKeyEnabled = true),
            "languageSwitchKeyEnabled" to id(0, languageSwitchKeyEnabled = true),
            "languageQuickSwitchKeyEnabled" to id(0, languageQuickSwitchKeyEnabled = true),
            "inputBoardBarEnabled" to id(0, inputBoardBarEnabled = true),
            "isMultiLine" to id(0, editorInfo = editorInfo(TEXT or MULTILINE_FLAG)),
            "imeAction" to id(0, editorInfo = editorInfo(TEXT, EditorInfo.IME_ACTION_SEARCH)),
            "customActionLabel" to id(0, editorInfo = editorInfo(TEXT, 0, "Zap")),
            "navigateNext" to id(0, editorInfo = editorInfo(TEXT, NAVIGATE_NEXT)),
            "navigatePrevious" to id(0, editorInfo = editorInfo(TEXT, NAVIGATE_PREVIOUS)),
            "subtype" to id(0, subtype = subtype(locale = "fr_FR"))
        )
        checkAll(differing.map { (label, other) ->
            "differs by $label" to {
                assertNotEquals(base, other)
                assertNotEquals(base.hashCode().toLong(), other.hashCode().toLong())
            }
        })
    }

    @Test
    fun equals_isReflexiveAndRejectsForeignTypes() {
        val a = id(0)
        assertTrue(a == a)
        assertFalse(a.equals("alphabet"))
        assertFalse(a.equals(null))
    }

    /**
     * `isNumberLayout()` / `isPhoneLayout()` / `isDateTimeLayout()` are not in equals/hashCode;
     * only `mMode` keeps a date field from getting a cached phone keyboard.
     */
    @Test
    fun equals_ignoresNumberPhoneAndDateTimeLayoutFlags() {
        val phone = id(0, editorInfo = editorInfo(PHONE))
        val dateTime = id(0, editorInfo = editorInfo(DATETIME))
        assertTrue(phone.isPhoneLayout())
        assertTrue(dateTime.isDateTimeLayout())
        assertEquals(phone, dateTime)
        assertEquals(phone.hashCode(), dateTime.hashCode())
    }

    @Test
    fun toString_isTheDebugGoldenForANamedElement() {
        assertEquals(
            "[alphabet en_US:qwerty 1080x400 text actionUnspecified" +
                " voiceDictationKeyDisabled inputBoardBarDisabled]",
            id(0).toString()
        )
    }

    /**
     * Flag suffixes are individually optional; the voice and input-board-bar suffixes appear when
     * the feature is disabled. Element 38 prints the literal "null".
     */
    @Test
    fun toString_isTheDebugGoldenForAnUnnamedElementWithEveryFlagSet() {
        assertEquals(
            "[null en_US:qwerty 480x240 password actionSearch clobberSettingsKey passwordInput" +
                " languageSwitchKeyEnabled languageQuickSwitchKeyEnabled isMultiLine]",
            id(
                elementId = 38,
                mode = 11,
                editorInfo = editorInfo(
                    TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or MULTILINE_FLAG,
                    EditorInfo.IME_ACTION_SEARCH
                ),
                width = 480,
                height = 240,
                clobberSettingsKey = true,
                voiceKeyEnabled = true,
                languageSwitchKeyEnabled = true,
                languageQuickSwitchKeyEnabled = true,
                inputBoardBarEnabled = true
            ).toString()
        )
    }

    @Test
    fun equivalentEditorInfo_comparesOnlyInputTypeImeOptionsAndPrivateImeOptions() {
        checkAll(
            listOf<Pair<String, () -> Unit>>(
                "both null" to {
                    assertTrue(KeyboardId.equivalentEditorInfo(null, null))
                },
                "one null" to {
                    assertFalse(KeyboardId.equivalentEditorInfo(null, editorInfo(TEXT)))
                    assertFalse(KeyboardId.equivalentEditorInfo(editorInfo(TEXT), null))
                },
                "identical triple" to {
                    assertTrue(
                        KeyboardId.equivalentEditorInfo(
                            editorInfo(TEXT, 1, privateImeOptions = "x"),
                            editorInfo(TEXT, 1, privateImeOptions = "x")
                        )
                    )
                },
                "different inputType" to {
                    assertFalse(
                        KeyboardId.equivalentEditorInfo(editorInfo(TEXT), editorInfo(NUMBER))
                    )
                },
                "different imeOptions" to {
                    assertFalse(
                        KeyboardId.equivalentEditorInfo(editorInfo(TEXT, 1), editorInfo(TEXT, 2))
                    )
                },
                "different privateImeOptions" to {
                    assertFalse(
                        KeyboardId.equivalentEditorInfo(
                            editorInfo(TEXT, privateImeOptions = "x"),
                            editorInfo(TEXT, privateImeOptions = "y")
                        )
                    )
                },
                // Two different fields with the same shape are "equivalent"; sameEditor() exists
                // to tell them apart.
                "different field, same shape" to {
                    assertTrue(
                        KeyboardId.equivalentEditorInfo(
                            editorInfo(TEXT, fieldId = 1, packageName = "a"),
                            editorInfo(TEXT, fieldId = 2, packageName = "b")
                        )
                    )
                }
            )
        )
    }

    @Test
    fun sameEditor_alsoRequiresFieldIdAndPackageName() {
        checkAll(
            listOf<Pair<String, () -> Unit>>(
                "both null" to { assertTrue(KeyboardId.sameEditor(null, null)) },
                "one null" to {
                    assertFalse(KeyboardId.sameEditor(null, editorInfo(TEXT)))
                    assertFalse(KeyboardId.sameEditor(editorInfo(TEXT), null))
                },
                "same field and package" to {
                    assertTrue(
                        KeyboardId.sameEditor(
                            editorInfo(TEXT, fieldId = 7, packageName = "com.x"),
                            editorInfo(TEXT, fieldId = 7, packageName = "com.x")
                        )
                    )
                },
                "different fieldId" to {
                    assertFalse(
                        KeyboardId.sameEditor(
                            editorInfo(TEXT, fieldId = 7, packageName = "com.x"),
                            editorInfo(TEXT, fieldId = 8, packageName = "com.x")
                        )
                    )
                },
                "different packageName" to {
                    assertFalse(
                        KeyboardId.sameEditor(
                            editorInfo(TEXT, fieldId = 7, packageName = "com.x"),
                            editorInfo(TEXT, fieldId = 7, packageName = "com.y")
                        )
                    )
                },
                "different inputType, same field" to {
                    assertFalse(
                        KeyboardId.sameEditor(
                            editorInfo(TEXT, fieldId = 7, packageName = "com.x"),
                            editorInfo(NUMBER, fieldId = 7, packageName = "com.x")
                        )
                    )
                }
            )
        )
    }
}
