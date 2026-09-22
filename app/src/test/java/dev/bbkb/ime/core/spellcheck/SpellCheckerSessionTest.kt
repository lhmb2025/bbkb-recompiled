package dev.bbkb.ime.core.spellcheck

import android.database.ContentObserver
import android.provider.UserDictionary
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import dev.bbkb.ime.core.suggestion.PrevWordsInfo
import dev.bbkb.ime.personaldictionary.tokenizer.SentenceTextInfoParams
import dev.bbkb.ime.personaldictionary.tokenizer.SentenceWordItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.util.Locale

/**
 * Characterises the spell-checker session's per-word path against a stubbed service (no engine):
 * word classification, case fallback, the suggestion cache and its invalidation, cookie/sequence
 * handling, the user-dictionary observer lifecycle, and sentence reconstruction.
 *
 * The session is driven without onCreate (which needs the native engine); the locale and script
 * code onCreate would set are injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpellCheckerSessionTest {

    private lateinit var service: AndroidSpellCheckerService
    private lateinit var session: AndroidSpellCheckerSession
    private val valid = mutableSetOf<String>()
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @Before
    fun setUp() {
        service = Mockito.mock(AndroidSpellCheckerService::class.java)
        Mockito.doReturn(resolver).`when`(service).contentResolver
        Mockito.doAnswer { valid.contains(it.getArgument<String>(0)) }
            .`when`(service).isValidWord(ArgumentMatchers.anyString())
        session = AndroidSpellCheckerSession(service)
        field("mSessionLocale").set(session, Locale.US)
        field("mScriptCode").setInt(session, 14)
    }

    @After
    fun tearDown() {
        session.onClose()
    }

    private fun field(name: String): Field {
        var c: Class<*>? = session.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private val cache: Any get() = field("mSuggestionCache").get(session)!!

    private fun putCached(word: String, prev: CharSequence?, suggestions: Array<String>, flags: Int) {
        cache.javaClass.getDeclaredMethod(
            "putCached", String::class.java, PrevWordsInfo::class.java,
            Array<String>::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
            .invoke(cache, word, PrevWordsInfo(PrevWordsInfo.WordInfo(prev)), suggestions, flags)
    }

    private fun getCached(word: String, prev: CharSequence?): Any? =
        cache.javaClass.getDeclaredMethod("getCached", String::class.java, PrevWordsInfo::class.java)
            .apply { isAccessible = true }
            .invoke(cache, word, PrevWordsInfo(PrevWordsInfo.WordInfo(prev)))

    private fun validWordCalls(): List<String> =
        Mockito.mockingDetails(service).invocations
            .filter { it.method.name == "isValidWord" }
            .map { it.arguments[0] as String }

    private fun ti(text: String, cookie: Int = 0, seq: Int = 0) = TextInfo(text, 0, text.length, cookie, seq)

    private fun single(text: String) = multiple(false, text)[0]

    private fun multiple(sequential: Boolean, vararg texts: String): Array<SuggestionsInfo> =
        session.onGetSuggestionsMultiple(
            texts.mapIndexed { i, t -> ti(t, cookie = 100 + i, seq = 200 + i) }.toTypedArray(), 5, sequential
        )

    private fun describe(info: SuggestionsInfo): String {
        val n = info.suggestionsCount
        val words = if (n <= 0) "" else (0 until n).joinToString(",") { info.getSuggestionAt(it) }
        return "a=${info.suggestionsAttributes} n=$n [$words]"
    }

    // ---- classification --------------------------------------------------------------------

    @Test
    fun tooShortWordIsJudgedByTheDictionaryAlone() {
        valid += "a"
        assertEquals("a=1 n=0 []", describe(single("a")))
        assertEquals("a=0 n=0 []", describe(single("q")))
    }

    @Test
    fun nonLetterStartIsNeverATypo() {
        assertEquals("a=0 n=0 []", describe(single("1abc")))
        valid += "1abc"
        assertEquals("a=1 n=0 []", describe(single("1abc")))
    }

    @Test
    fun urlOrEmailIsNeverATypo() {
        assertEquals("a=0 n=0 []", describe(single("a@b")))
        assertEquals("a=0 n=0 []", describe(single("abc/def")))
    }

    @Test
    fun mostlyNonLettersIsNeverATypo() {
        assertEquals("a=0 n=0 []", describe(single("x1234")))
    }

    @Test
    fun periodCompoundOfValidPartsSuggestsTheSpacedForm() {
        valid += listOf("foo", "bar")
        assertEquals("a=6 n=1 [foo bar]", describe(single("foo.bar")))
    }

    @Test
    fun periodCompoundThatIsItselfValid() {
        valid += listOf("foo", "e.g")
        assertEquals("a=1 n=0 []", describe(single("e.g")))
    }

    @Test
    fun invalidPeriodCompoundLooksLikeATypo() {
        valid += "foo"
        assertEquals("a=2 n=0 []", describe(single("foo.baz")))
    }

    // ---- case fallback + normalisation -----------------------------------------------------

    @Test
    fun lowercaseWordIsCheckedExactlyOnce() {
        single("1hello")
        assertEquals(listOf("1hello", "1hello"), validWordCalls())
    }

    @Test
    fun capitalisedWordFallsBackToLowercase() {
        single("Hello.")
        assertEquals("Hello.", validWordCalls()[0])
        assertEquals("hello.", validWordCalls()[1])
    }

    @Test
    fun allCapsWordFallsBackToLowercaseThenCapitalised() {
        single("HELLO.")
        assertEquals(listOf("HELLO.", "hello.", "Hello."), validWordCalls().subList(0, 3))
    }

    @Test
    fun capsFallbackStopsAtTheFirstHit() {
        valid += "hello."
        single("HELLO.")
        assertEquals(listOf("HELLO.", "hello."), validWordCalls().subList(0, 2))
        assertFalse(validWordCalls().contains("Hello."))
    }

    @Test
    fun curlyApostropheIsNormalisedForTheDictionaryButNotForClassification() {
        single("1don’t")
        assertEquals(listOf("1don't", "1don’t"), validWordCalls())
    }

    // ---- cache -----------------------------------------------------------------------------

    @Test
    fun sequentialWordsKeyTheCacheWithThePreviousWord() {
        valid += "world"
        putCached("world", "hello", arrayOf("W"), 1)
        putCached("world", null, arrayOf("N"), 1)
        assertEquals("a=1 n=1 [W]", describe(multiple(true, "hello", "world")[1]))
        assertEquals("a=1 n=1 [N]", describe(multiple(false, "hello", "world")[1]))
    }

    @Test
    fun firstWordOfASequentialBatchHasNoPreviousWord() {
        valid += "world"
        putCached("world", null, arrayOf("N"), 1)
        assertEquals("a=1 n=1 [N]", describe(multiple(true, "world")[0]))
    }

    @Test
    fun cacheHitIsIgnoredWhenItDisagreesWithTheDictionary() {
        valid += listOf("foo", "bar", "foo.bar")
        putCached("foo.bar", null, arrayOf("X"), 2)
        // In dictionary now, cached as not-in-dictionary: recomputed.
        assertEquals("a=6 n=1 [foo bar]", describe(single("foo.bar")))
    }

    @Test
    fun cacheHitAgreeingWithTheDictionaryIsReturned() {
        putCached("foo.bar", null, arrayOf("X"), 2)
        assertEquals("a=2 n=1 [X]", describe(single("foo.bar")))
    }

    @Test
    fun cacheIsReadUnderTheNormalisedWord() {
        valid += "1don't"
        putCached("1don't", null, arrayOf("C"), 1)
        assertEquals("a=1 n=1 [C]", describe(single("1don’t")))
    }

    @Test
    fun preferenceChangeClearsTheCacheBeforeTheLookup() {
        putCached("foo.bar", null, arrayOf("X"), 2)
        Mockito.doReturn(true).`when`(service).hasPreferenceChanged()
        assertEquals("a=2 n=0 []", describe(single("foo.bar")))
        Mockito.verify(service).clearPreferenceChanged()
        assertNull(getCached("foo.bar", null))
    }

    @Test
    fun singleWordEntryPointUsesTheNoPreviousWordCacheKey() {
        // Null previous words key the cache exactly like an empty PrevWordsInfo.
        valid += "hello"
        putCached("foo.bar", null, arrayOf("X"), 2)
        assertEquals("a=2 n=1 [X]", describe(session.onGetSuggestions(ti("foo.bar"), 5)))
        assertEquals(describe(single("hello")), describe(session.onGetSuggestions(ti("hello"), 5)))
    }

    @Test
    fun singleWordEntryPointReportsATypo() {
        valid += "foo"
        assertEquals("a=2 n=0 []", describe(session.onGetSuggestions(ti("foo.baz"), 5)))
        valid += listOf("bar")
        assertEquals("a=6 n=1 [foo bar]", describe(session.onGetSuggestions(ti("foo.bar"), 5)))
    }

    @Test
    fun resultsCarryEachInputsCookieAndSequence() {
        val out = multiple(false, "a", "1abc", "foo.bar")
        assertEquals(3, out.size)
        for (i in out.indices) {
            assertEquals(100 + i, out[i].cookie)
            assertEquals(200 + i, out[i].sequence)
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    private fun observer(): ContentObserver = field("mUserDictObserver").get(session) as ContentObserver

    @Test
    fun userDictionaryObserverIsRegisteredUntilClose() {
        val uri = UserDictionary.Words.CONTENT_URI
        assertTrue(shadowOf(resolver).getContentObservers(uri).contains(observer()))
        val o = observer()
        session.onClose()
        assertFalse(shadowOf(resolver).getContentObservers(uri).contains(o))
        // A second close is harmless (tearDown closes again).
    }

    @Test
    fun userDictionaryChangeClearsTheCache() {
        putCached("foo", null, arrayOf("X"), 1)
        assertNotNull(getCached("foo", null))
        observer().onChange(false)
        assertNull(getCached("foo", null))
    }

    @Test
    fun localeForTestingOverridesTheFrameworkLocale() {
        session.mLocaleForTesting = "fr_CA"
        assertEquals("fr_CA", session.locale)
    }

    // ---- sentence reconstruction -----------------------------------------------------------

    private fun info(attrs: Int, seq: Int, vararg s: String) =
        SuggestionsInfo(attrs, arrayOf(*s)).apply { setCookieAndSequence(0, seq) }

    private fun params(vararg items: Triple<Int, Int, Int>): SentenceTextInfoParams {
        val original = TextInfo("hello big world", 0, 15, 7, 9)
        return SentenceTextInfoParams(
            original,
            ArrayList(items.map { (seq, start, end) -> SentenceWordItem(TextInfo("w", 0, 1, 0, seq), start, end) })
        )
    }

    @Test
    fun reconstructMatchesBySequenceAndRestampsWithTheSentenceCookie() {
        val hello = info(2, 100, "hullo")
        val world = info(1, 102)
        val out = SentenceTokenizer.reconstructSentenceSuggestions(
            params(Triple(100, 0, 5), Triple(101, 6, 9), Triple(102, 10, 15)),
            arrayOf(world, null, hello)
        )!!
        assertEquals(3, out.suggestionsCount)
        assertSame(hello, out.getSuggestionsInfoAt(0))
        assertSame(world, out.getSuggestionsInfoAt(2))
        assertEquals(listOf(0, 6, 10), (0..2).map { out.getOffsetAt(it) })
        assertEquals(listOf(5, 3, 5), (0..2).map { out.getLengthAt(it) })
        assertEquals(7, hello.cookie)
        assertEquals(9, hello.sequence)
        val empty = out.getSuggestionsInfoAt(1)
        assertEquals(0, empty.suggestionsAttributes)
        assertEquals(-1, empty.suggestionsCount)
    }

    @Test
    fun reconstructUnmatchedItemsShareOneEmptyInfo() {
        val out = SentenceTokenizer.reconstructSentenceSuggestions(
            params(Triple(1, 0, 1), Triple(2, 2, 3), Triple(3, 4, 5)),
            arrayOf(info(1, 2))
        )!!
        assertSame(out.getSuggestionsInfoAt(0), out.getSuggestionsInfoAt(2))
    }

    @Test
    fun reconstructRestampedInfoCanMatchALaterItemWithTheSentenceSequence() {
        val a = info(1, 100)
        val out = SentenceTokenizer.reconstructSentenceSuggestions(
            params(Triple(100, 0, 1), Triple(9, 2, 3)),
            arrayOf(a)
        )!!
        assertSame(a, out.getSuggestionsInfoAt(0))
        assertSame(a, out.getSuggestionsInfoAt(1))
    }

    @Test
    fun reconstructFirstMatchingInfoWins() {
        val first = info(1, 5)
        val second = info(2, 5)
        val out = SentenceTokenizer.reconstructSentenceSuggestions(params(Triple(5, 0, 1)), arrayOf(first, second))!!
        assertSame(first, out.getSuggestionsInfoAt(0))
        assertEquals(5, second.sequence)
    }

    @Test
    fun reconstructReturnsNullForMissingInputs() {
        assertNull(SentenceTokenizer.reconstructSentenceSuggestions(params(Triple(1, 0, 1)), null))
        assertNull(SentenceTokenizer.reconstructSentenceSuggestions(params(Triple(1, 0, 1)), arrayOf()))
        assertNull(SentenceTokenizer.reconstructSentenceSuggestions(null, arrayOf(info(1, 1))))
    }

    @Test
    fun emptyResultIsASharedZeroLengthArray() {
        assertEquals(0, SentenceTokenizer.getEmptyResult().size)
        assertSame(SentenceTokenizer.getEmptyResult(), SentenceTokenizer.getEmptyResult())
    }
}
