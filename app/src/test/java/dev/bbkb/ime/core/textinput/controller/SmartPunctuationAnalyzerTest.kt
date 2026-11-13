package dev.bbkb.ime.core.textinput.controller

import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Table test for [SmartPunctuationAnalyzer.analyze]: each row is an input (preceding text, code
 * point, auto-space, spacing table, internal source, dumb mode) and the exact decision (event
 * chain as (codePoint, keyCode), the three [SmartPunctuationAnalyzer.Result] flags, dumb mode
 * after). The `CHARACTERISED BUG:` row asserts shipping behaviour; do not "fix" it here.
 *
 * ## The decision space
 *
 * `analyze` (in order):
 *  1. not a key-press event                     -> event unmodified, all flags false
 *  2. context ends in ' ' AND auto-space off    -> event unmodified, all flags false
 *  3. `decideActions` yields DUMB_MODE          -> dumb mode armed, event unmodified
 *  4. action list is exactly `[INS_FOCUS]`      -> event unmodified, all flags false
 *  5. otherwise                                 -> a rebuilt/prepended chain
 *
 * `decideActions`:
 *  - chained event present AND dumb mode off:
 *      - next code point is a symbol -> [INS_FOCUS, DUMB_MODE]
 *      - otherwise                   -> EMPTY (falls into analyze rule 5's else branch)
 *  - otherwise:
 *      - French preceded-by-space char, context not ending in whitespace -> INS_SPACE
 *      - else followed-by-space char after non-newline whitespace        -> BACKSPACE
 *      - INS_FOCUS (always)
 *      - context+char looks like an email -> nothing more
 *      - else looks like a URL            -> DUMB_MODE
 *      - else followed-by-space char      -> trailing INS_SPACE
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SmartPunctuationAnalyzerTest {

    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var subtypes: SubtypeManager

    /** Real spacing/punctuation tables, loaded from the app's own resources. */
    private lateinit var spacing: SpacingAndPunctuation

    private lateinit var analyzer: SmartPunctuationAnalyzer

    @Before
    fun setUp() {
        // Built before the static mock: the constructor reads the locale through SubtypeManager.
        spacing = SpacingAndPunctuation(RuntimeEnvironment.getApplication().resources)

        subtypes = mock(SubtypeManager::class.java)
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)
        setLocale(Locale.US)

        analyzer = SmartPunctuationAnalyzer()
    }

    @After
    fun tearDown() {
        subtypeStatic.close()
    }

    /** The French preceded-by-space set (`!?«`) is gated on the current subtype locale. */
    private fun setLocale(locale: Locale) {
        `when`(subtypes.currentSubtypeLocale).thenReturn(locale)
    }

    /**
     * A [SettingsValues] carrying only `spacingAndPunctuation`, allocated via Unsafe because the
     * real constructor reaches SettingsManager, DeviceProfile and RichInputMethodManager.
     */
    private fun settings(hasSpaces: Boolean = true): SettingsValues {
        val sp = if (hasSpaces) spacing else withoutSpaces(spacing)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val sv = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, SettingsValues::class.java) as SettingsValues
        SettingsValues::class.java.getDeclaredField("spacingAndPunctuation")
            .apply { isAccessible = true }.set(sv, sp)
        return sv
    }

    /** A copy of the real tables with `currentLanguageHasSpaces` flipped off (Japanese/Chinese). */
    private fun withoutSpaces(src: SpacingAndPunctuation): SpacingAndPunctuation {
        val copy = SpacingAndPunctuation(src, src.wordSeparators)
        SpacingAndPunctuation::class.java.getDeclaredField("currentLanguageHasSpaces")
            .apply { isAccessible = true }.setBoolean(copy, false)
        return copy
    }

    private fun keyPress(ch: Char, next: InputEvent? = null): InputEvent =
        InputEvent.createHardwareKeyPress(ch.code, 0, next, false, 1000L)

    /** The chain `analyze` returned, as (codePoint, keyCode) pairs, head first. */
    private fun chain(head: InputEvent?): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var e = head
        while (e != null) {
            out.add(e.mCodePoint to e.mKeyCode)
            e = e.mNextEvent
        }
        return out
    }

    private val space = ' '.code to 0
    private val backspace = -1 to -5
    private fun typed(ch: Char) = ch.code to 0

    /** Runs one row on a fresh analyzer, so dumb mode never leaks between rows unless pre-armed. */
    private fun run(
        label: String,
        context: String,
        ch: Char,
        autoSpaceActive: Boolean = false,
        fromInternalSource: Boolean = false,
        hasSpaces: Boolean = true,
        next: InputEvent? = null,
        preArmDumbMode: Boolean = false,
        expectChain: List<Pair<Int, Int>>,
        expectBackspaceInjected: Boolean,
        expectEventsPrepended: Boolean,
        expectAppendAutoSpace: Boolean,
        expectDumbModeAfter: Boolean,
    ) {
        val a = SmartPunctuationAnalyzer()
        if (preArmDumbMode) {
            // The only public way in: a chained symbol event arms dumb mode.
            a.analyze("x", keyPress('a', keyPress('!')), false, settings(), false)
            assertTrue("$label: pre-arm of dumb mode did not take", a.isDumbMode)
        }
        val event = keyPress(ch, next)
        val r = a.analyze(context, event, autoSpaceActive, settings(hasSpaces), fromInternalSource)

        assertEquals("$label: event chain", expectChain, chain(r.event))
        assertEquals("$label: backspaceInjected", expectBackspaceInjected, r.backspaceInjected)
        assertEquals("$label: eventsPrepended", expectEventsPrepended, r.eventsPrepended)
        assertEquals("$label: appendAutoSpace", expectAppendAutoSpace, r.appendAutoSpace)
        assertEquals("$label: dumbMode after", expectDumbModeAfter, a.isDumbMode)
    }

    /** Shorthand for "the analyzer decided to do nothing": the event comes back untouched. */
    private fun runPassthrough(
        label: String,
        context: String,
        ch: Char,
        autoSpaceActive: Boolean = false,
        fromInternalSource: Boolean = false,
        hasSpaces: Boolean = true,
        next: InputEvent? = null,
        expectDumbModeAfter: Boolean = false,
    ) = run(
        label, context, ch, autoSpaceActive, fromInternalSource, hasSpaces, next,
        expectChain = if (next == null) listOf(typed(ch)) else listOf(typed(ch), typed(next.mCodePoint.toChar())),
        expectBackspaceInjected = false,
        expectEventsPrepended = false,
        expectAppendAutoSpace = false,
        expectDumbModeAfter = expectDumbModeAfter,
    )

    @Test
    fun nonKeyPressEvent_isReturnedUntouched() {
        // A text-input event (paste, suggestion text) is never analyzed at all.
        val a = SmartPunctuationAnalyzer()
        val textEvent = InputEvent.createTextInputEvent("hello", 0)
        val r = a.analyze("some context.", textEvent, true, settings(), false)
        assertTrue("the very same instance must come back", r.event === textEvent)
        assertFalse(r.backspaceInjected)
        assertFalse(r.eventsPrepended)
        assertFalse(r.appendAutoSpace)
        assertFalse(a.isDumbMode)
    }

    @Test
    fun ordinaryCharacters_afterEveryKindOfContext_passThrough() {
        // A plain letter is emitted as-is with no flags, whatever precedes it.
        for ((label, ctx) in listOf(
            "empty" to "",
            "letter" to "hello",
            "digit" to "1234",
            "punctuation" to "hello.",
            "space" to "hello ",
            "newline" to "hello\n",
            "open quote" to "he said \"",
            "open bracket" to "list (",
        )) {
            runPassthrough("plain letter after $label", ctx, 'a')
        }
    }

    @Test
    fun contextEndingInSpace_withAutoSpaceOff_shortCircuitsBeforeAnyAnalysis() {
        // Rule 2: a space the user typed (not an auto-space) is never rewritten.
        runPassthrough("period after user-typed space", "hello ", '.', autoSpaceActive = false)
        runPassthrough("bracket after user-typed space", "hello ", ')', autoSpaceActive = false)
    }

    @Test
    fun sentenceSeparator_afterWord_autoSpaceOff_rebuildsTheEventWithoutTrailingSpace() {
        // Without auto-space the trailing INS_SPACE is dropped, but the event is still rebuilt,
        // so eventsPrepended is true for a one-for-one chain.
        for (ch in listOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '&')) {
            run(
                "followed-by-space '$ch' after a word, auto-space off",
                context = "hello", ch = ch, autoSpaceActive = false,
                expectChain = listOf(typed(ch)),
                expectBackspaceInjected = false,
                expectEventsPrepended = true,
                expectAppendAutoSpace = false,
                expectDumbModeAfter = false,
            )
        }
    }

    @Test
    fun sentenceSeparator_afterWord_autoSpaceOn_emitsSpaceAndAlsoAsksForOne() {
        // Auto-space active, non-internal source: the chain carries a space and appendAutoSpace
        // is true, both.
        run(
            "period after a word with auto-space active",
            context = "hello", ch = '.', autoSpaceActive = true,
            expectChain = listOf(typed('.'), space),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun sentenceSeparator_fromInternalSource_dropsTheTrailingSpace() {
        // An internal-source event drops the trailing INS_SPACE and appendAutoSpace stays false
        // even with auto-space active.
        run(
            "period after a gesture commit",
            context = "hello", ch = '.', autoSpaceActive = true, fromInternalSource = true,
            expectChain = listOf(typed('.')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun sentenceSeparator_afterAutoSpace_injectsBackspaceFirst() {
        // "hello " (auto-space) + '.' becomes backspace, '.', space: "hello. ".
        run(
            "period after an auto-space",
            context = "hello ", ch = '.', autoSpaceActive = true,
            expectChain = listOf(backspace, typed('.'), space),
            expectBackspaceInjected = true,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun sentenceSeparatorAfterTab_injectsBackspace_butAfterNewlineDoesNot() {
        // The test is `isWhitespace(c) && c != '\n'`: a tab is eaten, a newline kept. A tab also
        // slips past rule 2, which only tests for ' '.
        run(
            "period after a tab, auto-space off",
            context = "hello\t", ch = '.', autoSpaceActive = false,
            expectChain = listOf(backspace, typed('.')),
            expectBackspaceInjected = true,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
        run(
            "period after a newline keeps the newline",
            context = "hello\n", ch = '.', autoSpaceActive = false,
            expectChain = listOf(typed('.')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun sentenceSeparatorAfterASingleSpaceContext_backspacesTheLoneAutoSpace() {
        // A one-space context (lone auto-space at field start) is eaten like a longer one; the
        // guard is `length > 0`, not `length - 1 > 0`.
        run(
            "period after a context that is exactly one space",
            context = " ", ch = '.', autoSpaceActive = true,
            expectChain = listOf(backspace, typed('.'), space),
            expectBackspaceInjected = true,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun sentenceSeparator_inALanguageWithoutSpaces_emitsTheTypedCharacterOnce() {
        // Without spaces the INS_SPACE arm creates no event; the chain must still emit the typed
        // character once (the loop tracks whether the chain has started), not "..".
        // appendAutoSpace stays true: PunctuationController.appendAutoSpace re-checks
        // `currentLanguageHasSpaces`, so no space reaches the editor.
        run(
            "period in a no-spaces locale, auto-space ACTIVE",
            context = "hello", ch = '.', autoSpaceActive = true, hasSpaces = false,
            expectChain = listOf(typed('.')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = false,
        )
        run(
            "period in a no-spaces locale, auto-space off",
            context = "hello", ch = '.', autoSpaceActive = false, hasSpaces = false,
            expectChain = listOf(typed('.')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun frenchPrecededBySpace_insertsASpaceBeforeTheCharacter() {
        setLocale(Locale.FRANCE)
        run(
            "'!' after a word in fr-FR",
            context = "Bonjour", ch = '!', autoSpaceActive = false,
            expectChain = listOf(space, typed('!')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun frenchPrecededBySpace_isLocaleGated() {
        // Same keystroke, en-US: no leading space, just the followed-by-space handling.
        setLocale(Locale.US)
        run(
            "'!' after a word in en-US",
            context = "Bonjour", ch = '!', autoSpaceActive = false,
            expectChain = listOf(typed('!')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
        // Canadian French is explicitly excluded from the rule.
        setLocale(Locale.CANADA_FRENCH)
        run(
            "'!' after a word in fr-CA",
            context = "Bonjour", ch = '!', autoSpaceActive = false,
            expectChain = listOf(typed('!')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun frenchPrecededBySpace_atStartOfFieldInsertsNothing_andEatsALoneAutoSpace() {
        setLocale(Locale.FRANCE)
        // Empty context: no leading space, since there is no word in front to separate from.
        run(
            "'!' at the very start of the field, fr-FR",
            context = "", ch = '!', autoSpaceActive = false,
            expectChain = listOf(typed('!')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
        // The French guard is `len > 0 && !isWhitespace(last)`, so a lone space gets no extra
        // space; the followed-by-space clause then eats the auto-space, as for a longer context.
        run(
            "'!' after a context that is exactly one space, fr-FR",
            context = " ", ch = '!', autoSpaceActive = true,
            expectChain = listOf(backspace, typed('!'), space),
            expectBackspaceInjected = true,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = false,
        )
        // A one-character non-whitespace context gets the French space.
        run(
            "'!' after a one-character word, fr-FR",
            context = "A", ch = '!', autoSpaceActive = false,
            expectChain = listOf(space, typed('!')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun urlContext_armsDumbModeAndLeavesTheEventAlone() {
        // "www.google" + '.' trips the TLD test: DUMB_MODE returns the character untouched and
        // turns smart punctuation off for the rest of the session.
        run(
            "dot inside a URL",
            context = "www.google", ch = '.', autoSpaceActive = true,
            expectChain = listOf(typed('.')),
            expectBackspaceInjected = false,
            expectEventsPrepended = false,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = true,
        )
        run(
            "second slash of a scheme",
            context = "http:/", ch = '/', autoSpaceActive = true,
            expectChain = listOf(typed('/')),
            expectBackspaceInjected = false,
            expectEventsPrepended = false,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = true,
        )
    }

    @Test
    fun emailContext_suppressesAutoSpace_butDoesNotArmDumbMode() {
        // CHARACTERISED BUG: the analyzer's doc says email arms dumb mode, but the email branch
        // only skips the URL/auto-space work for the one keystroke that completes the pattern.
        run(
            "dot in the domain of an email address",
            context = "user@example", ch = '.', autoSpaceActive = true,
            expectChain = listOf(typed('.')),
            expectBackspaceInjected = false,
            expectEventsPrepended = false,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun chainedSymbolEvent_armsDumbMode() {
        // A chained symbol event (more-keys / symbol page): dumb mode on, event untouched.
        runPassthrough(
            "letter chained to a symbol",
            context = "hello", ch = 'a', next = keyPress('!'),
            expectDumbModeAfter = true,
        )
    }

    @Test
    fun chainedNonSymbolEvent_yieldsNoActionsAtAll() {
        // A chained non-symbol event yields an empty action list (not even INS_FOCUS), so the
        // event passes through and the '.' gets no sentence-separator handling.
        runPassthrough(
            "period chained to a letter",
            context = "hello", ch = '.', autoSpaceActive = true, next = keyPress('a'),
            expectDumbModeAfter = false,
        )
    }

    @Test
    fun dumbModeOnce_armedStaysArmedUntilCleared() {
        val a = SmartPunctuationAnalyzer()
        assertFalse(a.isDumbMode)
        a.analyze("hello", keyPress('a', keyPress('!')), false, settings(), false)
        assertTrue(a.isDumbMode)
        // Ordinary keystrokes do not clear it.
        a.analyze("hello", keyPress('b'), false, settings(), false)
        assertTrue(a.isDumbMode)
        a.clearDumbMode()
        assertFalse(a.isDumbMode)
    }

    @Test
    fun dumbModeArmed_reEnablesTheNormalPathForChainedEvents() {
        // The chained branch requires `!dumbMode`, so in dumb mode a chained '.' takes the
        // ordinary branch and gets the full rewrite it does not get with dumb mode off
        // ([chainedNonSymbolEvent_yieldsNoActionsAtAll]).
        run(
            "period chained to a letter, with dumb mode already armed",
            context = "hello", ch = '.', autoSpaceActive = true, next = keyPress('a'),
            preArmDumbMode = true,
            expectChain = listOf(typed('.'), space, typed('a')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = true,
        )
    }
}
