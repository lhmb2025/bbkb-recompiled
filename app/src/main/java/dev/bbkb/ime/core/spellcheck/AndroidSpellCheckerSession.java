package dev.bbkb.ime.core.spellcheck;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.ConditionVariable;
import android.provider.UserDictionary;
import android.service.textservice.SpellCheckerService;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.inputmethod.HangulInputProcessor;
import dev.bbkb.ime.core.inputmethod.InputMethodCallback;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.locale.SubtypeManager;
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
import dev.bbkb.ime.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
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

    private Set<Locale> mCachedLocaleSet;

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
        NuanceSDKManager.getSecondary().setLanguage(new Locale[]{this.mSessionLocale});
        if (ensureDictionaryLoaded()) {
            NuanceSDKManager.getSecondary().loadKeyboardLayout(this.mService.getKeyboardForLocale(this.mSessionLocale).isTouchKeyboard());
        }
        // SC-AUDIT SC-01/SC-04: Log session creation locale vs IME locale and NuanceSDK states
        if (BuildConfig.DEBUG) {
            Locale[] primaryLangs = NuanceSDKManager.getInstance() != null ? NuanceSDKManager.getInstance().getLanguage() : null;
            Locale[] secondaryLangs = NuanceSDKManager.getSecondary() != null ? NuanceSDKManager.getSecondary().getLanguage() : null;
            String imeLocale = SubtypeManager.getInstance() != null ? String.valueOf(SubtypeManager.getInstance().getCurrentSubtypeLocale()) : "null";
            Logger.info("SC-AUDIT", "SC-01/SC-04 sessionCreated"
                + " sessionLocale=" + this.mSessionLocale
                + " imeActiveLocale=" + imeLocale
                + " primarySDKLangs=" + (primaryLangs != null ? Arrays.toString(primaryLangs) : "null")
                + " secondarySDKLangs=" + (secondaryLangs != null ? Arrays.toString(secondaryLangs) : "null")
                + " primarySDK==secondarySDK=" + (NuanceSDKManager.getInstance() == NuanceSDKManager.getSecondary()));
            Log.d("PIPELINE", "SC:sessionCreated" +
                " sessionLocale=" + this.mSessionLocale +
                " imeLocale=" + imeLocale +
                " secondarySDKLangs=" + (secondaryLangs != null ? Arrays.toString(secondaryLangs) : "null") +
                " sdkInstancesShared=" + (NuanceSDKManager.getInstance() == NuanceSDKManager.getSecondary()) +
                " scriptCode=" + this.mScriptCode);
        }
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public void onClose() {
        this.mService.getContentResolver().unregisterContentObserver(this.mUserDictObserver);
        this.mLatchExec.shutdownNow();
        // Release anyone parked in spellCheckWord.
        this.mUserDictLatch.open();
    }

    private boolean ensureDictionaryLoaded() {
        Set<Locale> activeLocales = SubtypeManager.getInstance().getCurrentSubtypeAdditionalLocales();
        if (activeLocales == null) {
            this.mCachedLocaleSet = null;
        }
        if (activeLocales != null && !activeLocales.equals(this.mCachedLocaleSet)) {
            this.mCachedLocaleSet = activeLocales;
            this.mScriptCode = ScriptUtils.getScriptFromLocale(this.mSessionLocale);
            if (!this.mService.initDictionaryForLocale(this.mSessionLocale)) {
                return false;
            }
            if (!LocaleUtils.isCurrentSubtypeKorean()) {
                return true;
            }
            NuanceSDKManager.getSecondary().loadKeyboardLayout(this.mService.getKeyboardForLocale(this.mSessionLocale).isTouchKeyboard());
            return true;
        }
        return this.mService.initDictionaryForLocale(this.mSessionLocale);
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
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:wordValid" +
                    " word=\"" + word + "\"" +
                    " capMode=" + capMode +
                    " result=true matchType=exact");
            }
            return true;
        }
        if (capMode == 0) {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:wordValid" +
                    " word=\"" + word + "\"" +
                    " capMode=" + capMode +
                    " result=false matchType=none(noCaseFallback)");
            }
            return false;
        }
        String lowerCase = word.toLowerCase(this.mSessionLocale);
        if (this.mService.isValidWord(lowerCase)) {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:wordValid" +
                    " word=\"" + word + "\"" +
                    " capMode=" + capMode +
                    " result=true matchType=lowercased(\"" + lowerCase + "\")");
            }
            return true;
        }
        if (1 == capMode) {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:wordValid" +
                    " word=\"" + word + "\"" +
                    " capMode=" + capMode +
                    " result=false matchType=none(capFirst)");
            }
            return false;
        }
        String capFirst = capitalizeFirstAndLowercaseRest(lowerCase, this.mSessionLocale);
        boolean result = this.mService.isValidWord(capFirst);
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "SC:wordValid" +
                " word=\"" + word + "\"" +
                " capMode=" + capMode +
                " result=" + result +
                " matchType=" + (result ? "capitalizedFirst(\"" + capFirst + "\")" : "none(allCasesExhausted)"));
        }
        return result;
    }

    private SuggestionsInfo spellCheckWord(TextInfo textInfo, PrevWordsInfo prevWordsInfo, int sugLimit) {
        // Bounded: an unbounded block() parks the spell-check binder thread forever if
        // the reopen task is ever lost (audit GD-7).
        if (!this.mUserDictLatch.block(USER_DICT_LATCH_MS) && BuildConfig.DEBUG) {
            Log.w("BlackBerrySpellChecker", "user-dictionary latch timed out; proceeding");
        }
        try {
            String text = textInfo.getText();
            // replace(char, char) rather than replaceAll: this is a single literal
            // character substitution running once per word the framework checks, on a
            // binder thread, and replaceAll compiles a Pattern and allocates a Matcher
            // every call (audit GD-10).
            String normalizedWord = text.replace('’', '\'');
            int capMode = getCapitalizationMode(normalizedWord);
            boolean isInDict = isWordInDictionary(normalizedWord, capMode);
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:spellCheck" +
                    " word=\"" + normalizedWord + "\"" +
                    " capMode=" + capMode +
                    " isInDict=" + isInDict +
                    " sugLimit=" + sugLimit +
                    " locale=" + this.mSessionLocale +
                    " hasPrevWords=" + (prevWordsInfo != null && prevWordsInfo.isValid()));
            }
            if (this.mService.hasPreferenceChanged()) {
                this.mSuggestionCache.clearCache();
                this.mService.clearPreferenceChanged();
            }
            // Audit GD-11: the cache is written under the normalized key (see
            // putCached below), so reading with the raw text meant every word carrying
            // a curly apostrophe - exactly the class fixApostropheSuggestions then
            // post-processes - was written and never read.
            CachedResult cached = this.mSuggestionCache.getCached(normalizedWord, prevWordsInfo);
            if (cached != null) {
                if (Logger.isLoggable("BlackBerrySpellChecker", Log.DEBUG)) {
                    Logger.debug("BlackBerrySpellChecker", "Cache hit: " + text + ", " + cached.mFlags);
                }
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
                suggestionResult = extractSuggestions(capMode, this.mSessionLocale, sugLimit, this.mService.getSensitivityThreshold(), normalizedWord, isInDict ? null : this.mService.getSuggestions(composingTracker, prevWordsInfo, proximityGrid));
            } catch (Throwable e) {
                // Fallback to basic spell checking if advanced features fail
                suggestionResult = extractSuggestions(capMode, this.mSessionLocale, sugLimit, this.mService.getSensitivityThreshold(), normalizedWord, null);
            }
            if (Logger.isLoggable("BlackBerrySpellChecker", Log.DEBUG)) {
                Logger.debug("BlackBerrySpellChecker", "Spell checking results for " + normalizedWord + " with suggestion limit " + sugLimit);
                Logger.debug("BlackBerrySpellChecker", "IsInDict = " + isInDict);
                Logger.debug("BlackBerrySpellChecker", "LooksLikeTypo = " + !isInDict);
                Logger.debug("BlackBerrySpellChecker", "HasRecommendedSuggestions = " + suggestionResult.mHasRecommended);
                if (suggestionResult.mSuggestions != null) {
                    for (String sug : suggestionResult.mSuggestions) {
                        Logger.debug("BlackBerrySpellChecker", sug);
                    }
                }
            }
            int flags = (suggestionResult.mHasRecommended ? SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS : 0) | (isInDict ? 1 : 2);
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:spellCheckResult" +
                    " word=\"" + normalizedWord + "\"" +
                    " isInDict=" + isInDict +
                    " hasRecommended=" + suggestionResult.mHasRecommended +
                    " suggestions=" + (suggestionResult.mSuggestions != null ? Arrays.toString(suggestionResult.mSuggestions) : "null") +
                    " flags=" + flags +
                    " locale=" + this.mSessionLocale);
            }
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

    private static SuggestionResult extractSuggestions(int capMode, Locale locale, int sugLimit, float threshold, String word, dev.bbkb.ime.core.suggestion.SuggestionResult perfLogger) {
        String transformedWord;
        if (perfLogger == null || perfLogger.isEmpty() || sugLimit <= 0) {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "SC:extractSuggestions:empty" +
                    " word=\"" + word + "\"" +
                    " perfLoggerNull=" + (perfLogger == null) +
                    " perfLoggerEmpty=" + (perfLogger != null && perfLogger.isEmpty()) +
                    " limit=" + sugLimit);
            }
            return new SuggestionResult(null, false);
        }
        if (Logger.isLoggable("BlackBerrySpellChecker", Log.DEBUG)) {
            Iterator<SuggestedWords.SuggestedWordInfo> it = perfLogger.getSuggestions().iterator();
            while (it.hasNext()) {
                SuggestedWords.SuggestedWordInfo next = it.next();
                Logger.debug("BlackBerrySpellChecker", "" + next.score + " " + next.word);
            }
        }
        ArrayList<String> suggestions = new ArrayList<>();
        Iterator<SuggestedWords.SuggestedWordInfo> it2 = perfLogger.getSuggestions().iterator();
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
        try {
            return spellCheckWord(textInfo, null, sugLimit);
        } finally {
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
                if (Logger.isLoggable("BlackBerrySpellChecker", Log.DEBUG)) {
                    Logger.debug("BlackBerrySpellChecker", "Override and remove old span over: " + part + ", " + partOffset + "," + part.length());
                }
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
        if (!ensureDictionaryLoaded()) {
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
        // Diagnostic: log the final SentenceSuggestionsInfo returned to framework
        if (BuildConfig.DEBUG) {
            for (int idx = 0; idx < sentenceResults.length; idx++) {
                SentenceSuggestionsInfo ssi = sentenceResults[idx];
                if (ssi == null) {
                    Log.d("PIPELINE", "SC:sentenceResult[" + idx + "] null");
                    continue;
                }
                int wc = ssi.getSuggestionsCount();
                Log.d("PIPELINE", "SC:sentenceResult[" + idx + "] wordCount=" + wc);
                for (int w = 0; w < wc; w++) {
                    SuggestionsInfo si = ssi.getSuggestionsInfoAt(w);
                    int flags = si != null ? si.getSuggestionsAttributes() : -1;
                    int offset = ssi.getOffsetAt(w);
                    int len = ssi.getLengthAt(w);
                    int sugCount = si != null ? si.getSuggestionsCount() : 0;
                    String firstSug = sugCount > 0 ? si.getSuggestionAt(0) : "(none)";
                    Log.d("PIPELINE", "SC:sentenceResult[" + idx + "] word[" + w + "]"
                        + " offset=" + offset + " len=" + len
                        + " flags=" + flags
                        + " sugCount=" + sugCount
                        + " firstSug=\"" + firstSug + "\"");
                }
            }
        }
        return sentenceResults;
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
                    // same string correctly, and the SC-07 audit line compares this against
                    // the engine's tokenizer locale, so that comparison could never match.
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
            results[i2] = SentenceTokenizer.reconstructSentenceSuggestions(tokenizedParams, onGetSuggestionsMultiple(wordTextInfos, sugLimit, true));
        }
        return results;
    }

    @Override // android.service.textservice.SpellCheckerService.Session
    public SuggestionsInfo[] onGetSuggestionsMultiple(TextInfo[] textInfoArr, int sugLimit, boolean sequentialWords) {
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
                results[i2] = spellCheckWord(textInfo, prevWordsInfo, sugLimit);
                results[i2].setCookieAndSequence(textInfo.getCookie(), textInfo.getSequence());
            }
            return results;
        } finally {
            Binder.restoreCallingIdentity(savedIdentity);
        }
    }
}
