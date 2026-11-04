package dev.bbkb.ime.core.spellcheck;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.ConditionVariable;
import android.provider.UserDictionary;
import android.service.textservice.SpellCheckerService;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.inputmethod.HangulInputProcessor;
import dev.bbkb.ime.core.inputmethod.InputMethodCallback;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.ProximityGrid;
import dev.bbkb.ime.personaldictionary.tokenizer.SentenceTextInfoParams;
import dev.bbkb.ime.personaldictionary.tokenizer.SentenceWordItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The spell checker's session: per-word checks against the service's dictionary with a suggestion
 * cache, sentence tokenisation and reconstruction, and an apostrophe post-pass.
 */
public final class AndroidSpellCheckerSession extends SpellCheckerService.Session {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private Locale mSessionLocale;

    private int mScriptCode;

    private final AndroidSpellCheckerService mService;

    private final ContentObserver mUserDictObserver;

    String mLocaleForTesting;

    private final ConditionVariable mUserDictLatch = new ConditionVariable(true);

    /** How long the user-dictionary latch stays closed after a provider notification. */
    private static final long USER_DICT_LATCH_MS = 3000L;

    /**
     * One scheduler for the whole session, shut down in {@link #onClose()}.
     *
     * <p>Audit GD-7: a fresh {@link java.util.Timer} - and therefore a new non-daemon
     * OS thread - used to be spawned on <em>every</em> UserDictionary change
     * notification and never cancelled, so a burst of provider notifications spawned a
     * burst of threads that lived until GC of the Timer.</p>
     */
    private final ScheduledExecutorService mLatchExec = Executors.newSingleThreadScheduledExecutor();

    private final SuggestionCache mSuggestionCache = new SuggestionCache();

    /** Dictionary answers for words already checked, so a recheck skips the engine (P-20). */
    private final SpellVerdictCache mVerdictCache = new SpellVerdictCache();

    private SentenceTokenizer mSentenceTokenizer;

    private static final class CachedResult {

        public final String[] mSuggestions;

        public final int mFlags;

        public CachedResult(String[] strArr, int flags) {
            this.mSuggestions = strArr;
            this.mFlags = flags;
        }
    }

    private static final class SuggestionCache {

        private final LruCache<String, CachedResult> mCache = new LruCache<>(50);

        private static String buildCacheKey(String word, PrevWordsInfo prevWordsInfo) {
            // onGetSuggestions (the single-word entry point) passes null previous words, as the
            // original APK did; dereferencing it threw, and spellCheckWord's catch turned every
            // single-word request into attributes 0 (no typo, no suggestions). Null means "no
            // previous word": the same key an invalid PrevWordsInfo gets. The engine already
            // accepts null (NuanceSDKDictionaryBridge.setNativeContext).
            if (TextUtils.isEmpty(word) || prevWordsInfo == null || !prevWordsInfo.isValid()) {
                return word;
            }
            return word + (char) 65532 + prevWordsInfo;
        }

        public CachedResult getCached(String word, PrevWordsInfo prevWordsInfo) {
            return this.mCache.get(buildCacheKey(word, prevWordsInfo));
        }

        public void putCached(String word, PrevWordsInfo prevWordsInfo, String[] suggestions, int flags) {
            if (suggestions == null || TextUtils.isEmpty(word)) {
                return;
            }
            this.mCache.put(buildCacheKey(word, prevWordsInfo), new CachedResult(suggestions, flags));
        }

        public void clearCache() {
            this.mCache.evictAll();
        }
    }

    public AndroidSpellCheckerSession(AndroidSpellCheckerService service) {
        this.mService = service;
        ContentResolver contentResolver = service.getContentResolver();
        this.mUserDictObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                mUserDictLatch.close();
                mSuggestionCache.clearCache();
                mVerdictCache.clear();
                try {
                    mLatchExec.schedule(mUserDictLatch::open, USER_DICT_LATCH_MS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // Session already closed - never leave the latch shut.
                    mUserDictLatch.open();
                }
            }
        };
        contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, this.mUserDictObserver);
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public String getLocale() {
        String str = this.mLocaleForTesting;
        return str != null ? str : super.getLocale();
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public void onCreate() {
        String locale = getLocale();
        this.mSessionLocale = LocaleUtils.constructLocaleFromString(locale);
        Locale sessionLocale = this.mSessionLocale;
        if (sessionLocale == null) {
            Logger.error("BlackBerrySpellChecker", "onCreate() failed to init locale");
            throw new NullPointerException("Failed to create locale: " + locale);
        }
        this.mScriptCode = ScriptUtils.getScriptFromLocale(sessionLocale);
        // Through the service, not straight to the engine: setting the engine's language here
        // used to drop the keyboard's other languages behind the service's back (it still
        // recorded them as loaded), and the keyboard layout lookup crashed for a script with no
        // layout. The service loads the languages, and the layout when there is one.
        this.mService.beginRequest();
        try {
            ensureDictionaryLoaded();
        } finally {
            this.mService.endRequest();
        }
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public void onClose() {
        this.mService.getContentResolver().unregisterContentObserver(this.mUserDictObserver);
        this.mLatchExec.shutdownNow();
        // Release anyone parked in spellCheckWord.
        this.mUserDictLatch.open();
    }

    /**
     * Whether the service's engine holds this session's languages, loading them when it does not.
     * Cheap once they are loaded, so every request asks; false while the session's language has
     * no installed pack.
     */
    private boolean ensureDictionaryLoaded() {
        return this.mService.initDictionaryForLocale(this.mSessionLocale);
    }

    /**
     * Once per request: loads the session's languages and drops the cached dictionary answers
     * when what they were computed against has changed. False as {@link #ensureDictionaryLoaded}.
     * Runs inside the request's {@code beginRequest}/{@code endRequest} pair, which keeps the
     * service from releasing the engine until the request is done with it.
     */
    private boolean prepareRequest() {
        if (!ensureDictionaryLoaded()) {
            return false;
        }
        // The service's generation moves on every language load and engine release: exactly
        // when the answers could change (it used to be the keyboard's subtype, which can
        // change without a reload and misses a release).
        this.mVerdictCache.syncDictionaryState(this.mService.getEngineGeneration());
        return true;
    }

    /**
     * The settings that change what the service accepts as a word, folded into the verdict key so
     * every session sees a change (only one session consumes the service's changed flag).
     */
    private static int verdictSettings() {
        if (!PrefsManager.INSTANCE.isInitialized()) {
            return 0;
        }
        final SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs();
        return (prefs.getBoolean(AndroidSpellCheckerService.PREF_ACCEPT_LEARNED_WORDS, true) ? 1 : 0)
                | (prefs.getBoolean("pref_spellcheck_use_contacts", true) ? 2 : 0);
    }

    private static int classifyWord(String str, int scriptCode) {
        if (TextUtils.isEmpty(str) || str.length() <= 1) {
            return 5; // TOO_SHORT
        }
        int firstCodePoint = str.codePointAt(0);
        if (!ScriptUtils.isLetterPartOfScript(firstCodePoint, scriptCode) && 39 != firstCodePoint) {
            return 4; // NON_LETTER_START
        }
        int length = str.length();
        int offset = 0;
        int letterCount = 0;
        while (offset < length) {
            int codePoint = str.codePointAt(offset);
            if (64 == codePoint || 47 == codePoint) {
                return 3; // URL_OR_EMAIL (contains @ or /)
            }
            if (46 == codePoint) {
                return 2; // CONTAINS_PERIOD
            }
            if (ScriptUtils.isLetterPartOfScript(codePoint, scriptCode)) {
                letterCount++;
            }
            offset = str.offsetByCodePoints(offset, 1);
        }
        return letterCount * 4 < length * 3 ? 1 : 0; // 1=MOSTLY_NON_LETTERS, 0=NORMAL_WORD
    }

    private boolean isWordInDictionary(String word, int capMode) {
        if (this.mService.isValidWord(word)) {
            return true;
        }
        if (capMode == 0) {
            return false;
        }
        String lowerCase = word.toLowerCase(this.mSessionLocale);
        if (this.mService.isValidWord(lowerCase)) {
            return true;
        }
        if (1 == capMode) {
            return false;
        }
        String capFirst = capitalizeFirstAndLowercaseRest(lowerCase, this.mSessionLocale);
        boolean result = this.mService.isValidWord(capFirst);
        return result;
    }

    /**
     * Checks one word. The caller has run {@link #prepareRequest} once for the whole request
     * (it used to run per word) and passes its answer as {@code dictionaryLoaded}.
     */
    private SuggestionsInfo spellCheckWord(TextInfo textInfo, PrevWordsInfo prevWordsInfo, int sugLimit,
            boolean dictionaryLoaded, int verdictSettings) {
        // Bounded: an unbounded block() parks the spell-check binder thread forever if
        // the reopen task is ever lost (audit GD-7).
        this.mUserDictLatch.block(USER_DICT_LATCH_MS);
        // The single-word entry points used to check against no dictionary at all (every word
        // passed). With no dictionary for this language, say nothing rather than mark the word
        // right or wrong.
        if (!dictionaryLoaded) {
            return AndroidSpellCheckerService.createUnknownResult();
        }
        try {
            String text = textInfo.getText();
            // replace(char, char) rather than replaceAll: this is a single literal
            // character substitution running once per word the framework checks, on a
            // binder thread, and replaceAll compiles a Pattern and allocates a Matcher
            // every call (audit GD-10).
            String normalizedWord = text.replace('’', '\'');
            int capMode = getCapitalizationMode(normalizedWord);
            if (this.mService.hasPreferenceChanged()) {
                this.mSuggestionCache.clearCache();
                this.mVerdictCache.clear();
                this.mService.clearPreferenceChanged();
            }
            // The cached answer first: the engine check is 1 to 3 locked native calls (more with
            // learned words not accepted), and correct words were never cached (P-20).
            Boolean cachedVerdict = this.mVerdictCache.get(normalizedWord, verdictSettings);
            boolean isInDict;
            if (cachedVerdict != null) {
                isInDict = cachedVerdict;
            } else {
                isInDict = isWordInDictionary(normalizedWord, capMode);
                this.mVerdictCache.put(normalizedWord, verdictSettings, isInDict);
            }
            // Audit GD-11: the cache is written under the normalized key (see
            // putCached below), so reading with the raw text meant every word carrying
            // a curly apostrophe - exactly the class fixApostropheSuggestions then
            // post-processes - was written and never read.
            CachedResult cached = this.mSuggestionCache.getCached(normalizedWord, prevWordsInfo);
            if (cached != null) {
                if ((cached.mFlags == 1) == isInDict) {
                    return new SuggestionsInfo(cached.mFlags, cached.mSuggestions);
                }
            }
            int wordClass = classifyWord(text, this.mScriptCode);
            if (wordClass != 0) {
                if (2 == wordClass) {
                    String[] parts = text.split("\\.");
                    boolean allPartsValid = true;
                    for (String part : parts) {
                        if (!this.mService.isValidWord(part)) {
                            allPartsValid = false;
                            break;
                        }
                    }
                    if (allPartsValid) {
                        return new SuggestionsInfo(6, new String[]{TextUtils.join(" ", parts)});
                    }
                }
                if (this.mService.isValidWord(text)) {
                    return AndroidSpellCheckerService.createInDictionaryResult();
                }
                return AndroidSpellCheckerService.createNotInDictionaryResult(2 == wordClass);
            }
            if (isInDict) {
                // What the path below gives a known word (no suggestions are generated for it),
                // without building a composing tracker for nothing.
                return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, null);
            }
            Keyboard keyboard = this.mService.getKeyboardForLocale(this.mSessionLocale);
            ComposingTextTracker composingTracker = new ComposingTextTracker(NuanceSDKManager.getSecondary());
            int[] codePoints = toCodePointArray((CharSequence) normalizedWord);
            ProximityGrid proximityGrid = keyboard != null ? keyboard.getProximityGrid() : null;
            if (composingTracker.getConverterType() == InputMethodCallback.ConverterType.NOT_DEFINED && LocaleUtils.isCurrentSubtypeKorean()) {
                composingTracker.setInputMethodConverter(new HangulInputProcessor());
            }
            composingTracker.setComposingFromCodePoints(codePoints, CoordinateUtils.newCoordinateArray(codePoints.length, -1, -1));
            SuggestionResult suggestionResult;
            try {
                suggestionResult = extractSuggestions(capMode, this.mSessionLocale, sugLimit, normalizedWord, isInDict ? null : this.mService.getSuggestions(composingTracker, prevWordsInfo, proximityGrid));
            } catch (Throwable e) {
                // Fallback to basic spell checking if advanced features fail
                suggestionResult = extractSuggestions(capMode, this.mSessionLocale, sugLimit, normalizedWord, null);
            }
            int flags = (suggestionResult.mHasRecommended ? SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS : 0) | (isInDict ? 1 : 2);
            SuggestionsInfo suggestionsInfo = new SuggestionsInfo(flags, suggestionResult.mSuggestions);
            this.mSuggestionCache.putCached(normalizedWord, prevWordsInfo, suggestionResult.mSuggestions, flags);
            return suggestionsInfo;
        } catch (RuntimeException e) {
            Logger.errorWithException("BlackBerrySpellChecker", e, "Exception while spellchecking");
            return AndroidSpellCheckerService.createNotInDictionaryResult(false);
        }
    }

    private static final class SuggestionResult {

        public final String[] mSuggestions;

        public final boolean mHasRecommended;

        public SuggestionResult(String[] suggestions, boolean hasRecommended) {
            this.mSuggestions = suggestions;
            this.mHasRecommended = hasRecommended;
        }
    }

    private static SuggestionResult extractSuggestions(int capMode, Locale locale, int sugLimit, String word, dev.bbkb.ime.core.suggestion.SuggestionResult engineResult) {
        String transformedWord;
        if (engineResult == null || engineResult.isEmpty() || sugLimit <= 0) {
            return new SuggestionResult(null, false);
        }
        ArrayList<String> suggestions = new ArrayList<>();
        Iterator<SuggestedWords.SuggestedWordInfo> it2 = engineResult.getSuggestions().iterator();
        while (it2.hasNext()) {
            SuggestedWords.SuggestedWordInfo suggestion = it2.next();
            if (!suggestion.isKind(0)) {
                if (2 == capMode) {
                    transformedWord = suggestion.word.toUpperCase(locale);
                } else if (1 == capMode) {
                    transformedWord = capitalizeFirstCodePoint(suggestion.word, locale);
                } else {
                    transformedWord = suggestion.word;
                }
                suggestions.add(transformedWord);
            }
        }
        removeDuplicates(suggestions);
        return new SuggestionResult(suggestions.subList(0, Math.min(suggestions.size(), sugLimit)).toArray(EMPTY_STRING_ARRAY), true);
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public SuggestionsInfo onGetSuggestions(TextInfo textInfo, int sugLimit) {
        long savedIdentity = Binder.clearCallingIdentity();
        this.mService.beginRequest();
        try {
            return spellCheckWord(textInfo, null, sugLimit, prepareRequest(), verdictSettings());
        } finally {
            this.mService.endRequest();
            Binder.restoreCallingIdentity(savedIdentity);
        }
    }

    /**
     * For each IN_THE_DICTIONARY word containing an apostrophe (straight or U+2019), appends an
     * empty override span for every non-empty apostrophe-separated part already in the suggestion cache (keyed
     * with the previous IN_THE_DICTIONARY word). The override covers the part's own characters
     * and carries the word's cookie/sequence. Returns null when nothing is added.
     */
    /** Straight or curly (U+2019) apostrophe; each is one UTF-16 unit, which the offsets rely on. */
    private static final String APOSTROPHE_PATTERN = "['’]";

    private static boolean hasApostrophe(CharSequence s) {
        final String str = s.toString();
        return str.indexOf('\'') >= 0 || str.indexOf('’') >= 0;
    }

    private SentenceSuggestionsInfo fixApostropheSuggestions(TextInfo textInfo, SentenceSuggestionsInfo sentence) {
        final CharSequence text = textInfo.getCharSequence();
        // Curly (U+2019) counts as an apostrophe here, as it does in spellCheckWord's normalisation;
        // checking only the straight one meant curly-apostrophe words never got this pass.
        if (!hasApostrophe(text)) {
            return null;
        }
        final int count = sentence.getSuggestionsCount();
        final ArrayList<Integer> offsets = new ArrayList<>();
        final ArrayList<Integer> lengths = new ArrayList<>();
        final ArrayList<SuggestionsInfo> overrides = new ArrayList<>();
        CharSequence prevWord = null;
        for (int i = 0; i < count; i++) {
            final SuggestionsInfo wordInfo = sentence.getSuggestionsInfoAt(i);
            if ((wordInfo.getSuggestionsAttributes() & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0) {
                continue;
            }
            final int offset = sentence.getOffsetAt(i);
            final CharSequence word = text.subSequence(offset, offset + sentence.getLengthAt(i));
            final PrevWordsInfo prevWordsInfo = new PrevWordsInfo(new PrevWordsInfo.WordInfo(prevWord));
            prevWord = word;
            if (!hasApostrophe(word)) {
                continue;
            }
            final CharSequence[] parts = splitPreservingSpans(word, APOSTROPHE_PATTERN, true);
            if (parts == null || parts.length <= 1) {
                continue;
            }
            // Each override must cover the part's own characters. It used to sit at the WORD's
            // offset with the PART's length (the original APK did the same), so for "don't" the
            // "t" override covered the "d" instead. The separator is one UTF-16 unit.
            int nextPartOffset = offset;
            for (CharSequence part : parts) {
                final int partOffset = nextPartOffset;
                nextPartOffset = partOffset + part.length() + 1;
                if (TextUtils.isEmpty(part) || this.mSuggestionCache.getCached(part.toString(), prevWordsInfo) == null) {
                    continue;
                }
                final SuggestionsInfo override = new SuggestionsInfo(0, EMPTY_STRING_ARRAY);
                override.setCookieAndSequence(wordInfo.getCookie(), wordInfo.getSequence());
                offsets.add(partOffset);
                lengths.add(part.length());
                overrides.add(override);
            }
        }
        if (overrides.isEmpty()) {
            return null;
        }
        final int total = count + overrides.size();
        final int[] offsetArr = new int[total];
        final int[] lengthArr = new int[total];
        final SuggestionsInfo[] infos = new SuggestionsInfo[total];
        for (int i = 0; i < count; i++) {
            offsetArr[i] = sentence.getOffsetAt(i);
            lengthArr[i] = sentence.getLengthAt(i);
            infos[i] = sentence.getSuggestionsInfoAt(i);
        }
        for (int i = count; i < total; i++) {
            offsetArr[i] = offsets.get(i - count);
            lengthArr[i] = lengths.get(i - count);
            infos[i] = overrides.get(i - count);
        }
        return new SentenceSuggestionsInfo(infos, offsetArr, lengthArr);
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public SentenceSuggestionsInfo[] onGetSentenceSuggestionsMultiple(TextInfo[] textInfoArr, int i) {
        if (i == 0) {
            i = 5;
        }
        int length = textInfoArr.length;
        this.mService.beginRequest();
        try {
            if (!prepareRequest()) {
                return new SentenceSuggestionsInfo[length];
            }
            SentenceSuggestionsInfo[] sentenceResults = getSentenceSuggestions(textInfoArr, i);
            if (sentenceResults == null || sentenceResults.length != textInfoArr.length) {
                return new SentenceSuggestionsInfo[length];
            }
            for (int i2 = 0; i2 < sentenceResults.length; i2++) {
                SentenceSuggestionsInfo apostropheFixed = fixApostropheSuggestions(textInfoArr[i2], sentenceResults[i2]);
                if (apostropheFixed != null) {
                    sentenceResults[i2] = apostropheFixed;
                }
            }
            return sentenceResults;
        } finally {
            this.mService.endRequest();
        }
    }

    private SentenceSuggestionsInfo[] getSentenceSuggestions(TextInfo[] textInfoArr, int sugLimit) {
        SentenceTokenizer tokenizer;
        if (textInfoArr == null || textInfoArr.length == 0) {
            return SentenceTokenizer.getEmptyResult();
        }
        synchronized (this) {
            tokenizer = this.mSentenceTokenizer;
            if (tokenizer == null) {
                String locale = getLocale();
                if (!TextUtils.isEmpty(locale)) {
                    // Audit GD-26: getLocale() returns the framework's subtype locale
                    // string ("en_US"), and new Locale("en_US") builds a Locale whose
                    // *language* is the literal "en_us" - not en/US. onCreate parses the
                    // same string correctly, and the tokenizer now splits by this locale.
                    tokenizer = new SentenceTokenizer(LocaleUtils.constructLocaleFromString(locale));
                    this.mSentenceTokenizer = tokenizer;
                }
            }
        }
        if (tokenizer == null) {
            return SentenceTokenizer.getEmptyResult();
        }
        int length = textInfoArr.length;
        SentenceSuggestionsInfo[] results = new SentenceSuggestionsInfo[length];
        final int verdictSettings = verdictSettings();
        for (int i2 = 0; i2 < length; i2++) {
            SentenceTextInfoParams tokenizedParams = tokenizer.tokenize(textInfoArr[i2]);
            if (tokenizedParams == null) {
                return null;
            }
            ArrayList<SentenceWordItem> wordItems = tokenizedParams.items;
            int wordCount = wordItems.size();
            TextInfo[] wordTextInfos = new TextInfo[wordCount];
            for (int i3 = 0; i3 < wordCount; i3++) {
                wordTextInfos[i3] = wordItems.get(i3).textInfo;
            }
            results[i2] = SentenceTokenizer.reconstructSentenceSuggestions(tokenizedParams, checkWords(wordTextInfos, sugLimit, true, true, verdictSettings));
        }
        return results;
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public SuggestionsInfo[] onGetSuggestionsMultiple(TextInfo[] textInfoArr, int sugLimit, boolean sequentialWords) {
        long savedIdentity = Binder.clearCallingIdentity();
        this.mService.beginRequest();
        try {
            return checkWords(textInfoArr, sugLimit, sequentialWords, prepareRequest(), verdictSettings());
        } finally {
            this.mService.endRequest();
            Binder.restoreCallingIdentity(savedIdentity);
        }
    }

    /** Checks each word; the request's dictionary load and settings were read once by the caller. */
    private SuggestionsInfo[] checkWords(TextInfo[] textInfoArr, int sugLimit, boolean sequentialWords,
            boolean dictionaryLoaded, int verdictSettings) {
        long savedIdentity = Binder.clearCallingIdentity();
        try {
            int length = textInfoArr.length;
            SuggestionsInfo[] results = new SuggestionsInfo[length];
            for (int i2 = 0; i2 < length; i2++) {
                CharSequence prevWord = null;
                if (sequentialWords && i2 > 0) {
                    CharSequence prevText = textInfoArr[i2 - 1].getCharSequence();
                    if (!TextUtils.isEmpty(prevText)) {
                        prevWord = prevText;
                    }
                }
                PrevWordsInfo prevWordsInfo = new PrevWordsInfo(new PrevWordsInfo.WordInfo(prevWord));
                TextInfo textInfo = textInfoArr[i2];
                results[i2] = spellCheckWord(textInfo, prevWordsInfo, sugLimit, dictionaryLoaded, verdictSettings);
                results[i2].setCookieAndSequence(textInfo.getCookie(), textInfo.getSequence());
            }
            return results;
        } finally {
            Binder.restoreCallingIdentity(savedIdentity);
        }
    }
}
