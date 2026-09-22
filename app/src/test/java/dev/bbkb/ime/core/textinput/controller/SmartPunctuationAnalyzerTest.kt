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
 * CHARACTERISATION table test for [SmartPunctuationAnalyzer.analyze] — Wave 2 of the
 * feature-simplification plan.
 *
 * `analyze` is the closest thing in the typing path to a pure function: its whole output is
 * determined by (preceding text, typed code point, auto-space-active, the language's
 * spacing/punctuation table, whether the event came from an internal/gesture source) plus the
 * one bit of instance state, dumb mode. So it gets a table: every row states an input
 * combination and the EXACT decision — the synthesized event chain as (codePoint, keyCode)
 * pairs, the three [SmartPunctuationAnalyzer.Result] flags, and dumb mode afterwards.
 *
 * Wave 3/4 may rewrite `decideActions`' five-clause chain and `analyze`'s reverse-walk loop.
 * Any rewrite has to reproduce every row here, including the one still marked
 * `CHARACTERISED BUG:`. That row asserts the behaviour that ships TODAY; do not "fix" it here.
 *
 * Wave 2.5 fixed three of the four originally-characterised bugs — defect 2 (a no-spaces locale
 * duplicated the typed character) and defects 9a/9b (both spacing guards used the last
 * character's INDEX where they meant the context's LENGTH). The rows for those three now assert
 * the corrected behaviour and say so.
 *
 * ## The decision space, enumerated from the source
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
        // Built BEFORE the static mock: SpacingAndPunctuation's constructor itself reads
        // LocaleUtils.getConfigurationLocale(resources), which goes through SubtypeManager.
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

    /**
     * The French preceded-by-space set (`!?«`) is gated on
     * `LocaleUtils.isCurrentSubtypeNonCanadianFrench()`, which reads the current subtype's
     * locale — so the locale IS one of the analyzer's inputs.
     */
    private fun setLocale(locale: Locale) {
        `when`(subtypes.currentSubtypeLocale).thenReturn(locale)
    }

    // ── settings shells ─────────────────────────────────────────────────────────

    /**
     * A [SettingsValues] carrying only the collaborator `analyze` reads. Its real constructor
     * reaches SettingsManager/DeviceProfile/RichInputMethodManager, none of which this decision
     * depends on, so the shell is allocated without running it — the same trick PipelineHarness
     * uses.
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

    // ── event helpers ───────────────────────────────────────────────────────────

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

    /**
     * Runs one table row and asserts the whole decision. Every row builds a FRESH analyzer, so
     * dumb mode never leaks between rows (except where a row deliberately pre-arms it).
     */
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

    // ── rule 1: not a key-press event ───────────────────────────────────────────

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

    // ── rule 4: ordinary characters are left alone ──────────────────────────────

    @Test
    fun ordinaryCharacters_afterEveryKindOfContext_passThrough() {
        // The preceding-text axis for a character with NO spacing rule attached: whatever sits
        // before the cursor, a plain letter is emitted as-is with no flags.
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
        // Rule 2. This is the guard that keeps "hello ." from being rewritten when the space
        // was the user's own rather than an auto-space: the sentence-separator rules below
        // never run at all.
        runPassthrough("period after user-typed space", "hello ", '.', autoSpaceActive = false)
        runPassthrough("bracket after user-typed space", "hello ", ')', autoSpaceActive = false)
    }

    // ── rule 5: followed-by-space punctuation (the `.,;:!?)]}&` set) ─────────────

    @Test
    fun sentenceSeparator_afterWord_autoSpaceOff_rebuildsTheEventWithoutTrailingSpace() {
        // The trailing INS_SPACE is dropped when auto-space is not active — but the event is
        // still REBUILT rather than returned, so eventsPrepended reports true even though the
        // chain is a one-for-one replacement of the typed character.
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
        // Auto-space active and the source is not internal: the trailing INS_SPACE survives,
        // so the chain carries a space AND appendAutoSpace comes back true. Both, not either.
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
        // A gesture/internal-source event takes the `!z || z2` branch: the trailing INS_SPACE
        // is dropped and appendAutoSpace stays false even though auto-space is active.
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
        // The headline smart-punctuation move: "hello " + '.' with the space being an AUTO
        // space (auto-space active, so rule 2 does not short-circuit) becomes
        // backspace, '.', space — i.e. "hello. ".
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
        // The whitespace test is `isWhitespace(c) && c != '\n'`, so a tab is eaten and a
        // newline is preserved. A tab also slips past rule 2, which only tests for ' '.
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
        // Defect 9a, FIXED: the backspace guard was `str.length() - 1 > 0` — that is the last
        // character's INDEX, so it demanded a context at least TWO characters long, where all
        // the clause needs is a character to inspect (`str.length() > 0`). A context that is
        // exactly one space — the cursor sitting after a lone auto-space at the very start of
        // the field — used to keep that space, leaving " ." where "." was intended.
        // It is now eaten, exactly as it is for any longer context (compare
        // [sentenceSeparator_afterAutoSpace_injectsBackspaceFirst]).
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
        // Defect 2, FIXED — the sharpest bug this file characterised. With
        // `currentLanguageHasSpaces == false` the INS_SPACE arm of the loop `break`s WITHOUT
        // creating an event, so the chain's tail slot was still open; but the old tail test
        // asked `i == length2` ("am I the last action?") rather than "has anything been
        // created yet?", so the following INS_FOCUS kept the ORIGINAL event as its tail and
        // emitted the typed character TWICE — typing '.' in a CJK/Thai locale with auto-space
        // active produced "..". The loop now tracks whether the chain has been started.
        //
        // appendAutoSpace stays true here: PunctuationController.appendAutoSpace re-checks
        // `currentLanguageHasSpaces` itself, so no space reaches the editor.
        //
        // The auto-space-off row right below was always fine, because there `length2--` had
        // already stepped past the INS_SPACE.
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

    // ── the French preceded-by-space set (`!?«`) ────────────────────────────────

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
        // Empty context: no leading space. Unchanged by the defect-9b fix, and deliberately so —
        // the French rule separates '!' '?' '«' from the word in FRONT of them, and at the start
        // of a field there is no such word.
        run(
            "'!' at the very start of the field, fr-FR",
            context = "", ch = '!', autoSpaceActive = false,
            expectChain = listOf(typed('!')),
            expectBackspaceInjected = false,
            expectEventsPrepended = true,
            expectAppendAutoSpace = false,
            expectDumbModeAfter = false,
        )
        // Defect 9b, FIXED. The guard used to read
        //   `str.length() - 1 == 0 || (str.length() - 1 > 0 && !isWhitespace(last))`
        // — the last character's INDEX in both halves. The first half therefore meant "the
        // context is exactly ONE character" and never inspected that character, so a lone space
        // still got a space inserted in front of it: " " + '!' became "  !". (The second half
        // had the mirror error of defect 9a: "at least TWO characters", so "A" + '!' got no
        // French space at all.) Both halves collapse into `len > 0 && !isWhitespace(last)`.
        //
        // With the French clause correctly declining, the followed-by-space clause below it now
        // fires instead and eats the lone auto-space (defect 9a) — so a one-character context
        // behaves exactly like a longer one.
        run(
            "'!' after a context that is exactly one space, fr-FR",
            context = " ", ch = '!', autoSpaceActive = true,
            expectChain = listOf(backspace, typed('!'), space),
            expectBackspaceInjected = true,
            expectEventsPrepended = true,
            expectAppendAutoSpace = true,
            expectDumbModeAfter = false,
        )
        // The other half of the 9b fix, previously uncovered: a one-character NON-whitespace
        // context now gets the French space it always should have had.
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

    // ── URL / email detection and dumb mode ─────────────────────────────────────

    @Test
    fun urlContext_armsDumbModeAndLeavesTheEventAlone() {
        // "www.google" + '.' -> the TLD test fires -> DUMB_MODE, which short-circuits the whole
        // rewrite: the typed character is returned untouched and smart punctuation is off for
        // the rest of the session.
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
        // CHARACTERISED BUG (a documentation/behaviour split): the class javadoc says dumb mode
        // is armed "when a URL or email pattern is detected", and `decideActions` does test for
        // email — but the email branch only SKIPS the URL/auto-space work; it never adds
        // DUMB_MODE. So an email address suppresses the trailing auto-space for exactly the one
        // keystroke that completes the pattern, and smart punctuation is re-enabled on the next.
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
        // A key whose chained next event is a symbol (the more-keys / symbol-page path):
        // [INS_FOCUS, DUMB_MODE] -> dumb mode on, event untouched.
        runPassthrough(
            "letter chained to a symbol",
            context = "hello", ch = 'a', next = keyPress('!'),
            expectDumbModeAfter = true,
        )
    }

    @Test
    fun chainedNonSymbolEvent_yieldsNoActionsAtAll() {
        // The `else` that isn't: when a chained event is present and its code point is NOT a
        // symbol, decideActions returns an EMPTY action array — not even INS_FOCUS. analyze's
        // `length2 == -1` then falls through to the pass-through branch, so nothing happens.
        // Worth pinning because it means a chained event disables sentence-separator handling
        // entirely: the '.' below gets no auto-space treatment.
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
        // `event.mNextEvent != null && !dumbMode` — once dumb mode is on, a chained event takes
        // the ORDINARY branch instead of the symbol branch. So the very state that is supposed
        // to suppress smart punctuation makes a chained '.' get the full sentence-separator
        // rewrite that a chained '.' does NOT get while dumb mode is off (see
        // [chainedNonSymbolEvent_yieldsNoActionsAtAll]).
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
