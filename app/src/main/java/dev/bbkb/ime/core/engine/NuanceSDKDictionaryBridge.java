package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;

import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.personaldictionary.DictionaryManager;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.PunctuationSuggestions;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import com.blackberry.nuanceshim.languagepack.LanguagePackManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.SystemProps;
import dev.bbkb.ime.core.suggestion.SuggestionResult;
import dev.bbkb.ime.core.shared.EmojiTextAnalyzer;
import com.blackberry.nuanceshim.NuanceSDK;
import com.blackberry.nuanceshim.WordInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import dev.bbkb.ime.core.shared.InputPathDebug;



public class NuanceSDKDictionaryBridge extends Dictionary {

    private Set<Locale> locales;

    private NuanceSDK nuanceSdk;

    private Context context;

    private boolean initialized;

    private final boolean notifyMissingPacks;

    public NuanceSDKDictionaryBridge(NuanceSDK nuanceSDK, Context context, Locale locale) {
        this(nuanceSDK, context, locale, true);
    }

    /**
     * @param notifyMissingPacks whether a supported but not downloaded language is reported to the
     *     user; false for the spell checker's copy, which loads in the background for other apps
     */
    public NuanceSDKDictionaryBridge(NuanceSDK nuanceSDK, Context context, Locale locale, boolean notifyMissingPacks) {
        super("main");
        this.initialized = true;
        this.nuanceSdk = nuanceSDK;
        this.locales = new LinkedHashSet();
        this.locales.add(locale);
        this.context = context;
        this.notifyMissingPacks = notifyMissingPacks;
        loadLanguagePacks();
    }

    // The gesture deposit sequence last ranked: each deposit is ranked exactly once. Static because
    // the deposit is engine-global and the bridge is re-created across language reloads.
    private static volatile int sConsumedRankSeq = 0;

    // Owned gesture ranking is live unless debug.et9.owndecode=0, in every build type: the selection
    // list is then a candidate pool re-ranked against the path. Cached per process (set the prop,
    // then force-stop).
    private static volatile int sOwnedDecodeCached = -1;
    public static boolean isOwnedDecodeLive() {
        int cached = sOwnedDecodeCached;
        if (cached < 0) {
            // Must match the C default (kdb_trace.c OWNED_DEFAULT, on when unset), or the ranked
            // word is never taken and the raw skeleton commits.
            cached = 1;
            String v = SystemProps.get("debug.et9.owndecode");
            if (v != null && !v.isEmpty()) cached = (v.charAt(0) == '1') ? 1 : 0;
            sOwnedDecodeCached = cached;
        }
        return cached == 1;
    }

    // Word sources are read only while "Suggest learned words" is off (SuggestionEngine drops the
    // learned-only ones); the read walks the engine list through libxt9trace, so skip it otherwise.
    private static volatile boolean sWordSourcesNeeded = false;
    public static void setWordSourcesNeeded(boolean needed) {
        sWordSourcesNeeded = needed;
    }

    /** True when {@code engineWord} equals {@code typed} after NFD diacritic stripping and lowercasing. */
    private static boolean foldedEquals(String typed, String engineWord, Locale locale) {
        if (typed.length() != engineWord.length()) {
            // The fold class here is one base letter per accented letter, so length never changes.
            return false;
        }
        Locale cmpLocale = locale != null ? locale : Locale.ROOT;
        String foldedTyped = stripCombiningMarks(typed).toLowerCase(cmpLocale);
        String foldedEngine = stripCombiningMarks(engineWord).toLowerCase(cmpLocale);
        return foldedTyped.equals(foldedEngine);
    }

    private static String stripCombiningMarks(String s) {
        String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean loadLanguagePacks() {
        final LanguagePackManager packs = LanguagePackManager.getInstance(this.context);
        final Locale[] wanted = this.locales.toArray(new Locale[0]);
        // Set even when a pack is missing: the engine then switches to an empty language (typed
        // and learned words only, nothing stale from the previous language).
        final boolean set = packs.setLanguages(this.nuanceSdk, wanted);
        if (this.notifyMissingPacks) {
            for (Locale locale : wanted) {
                // Unsupported locales ("No language", no engine table entry) stay silent.
                if (packs.isSupported(locale) && !packs.isInstalled(locale)) {
                    DictionaryAvailabilityNotice.packMissing(this.context, locale);
                }
            }
        }
        if (!set) {
            return false;
        }
        Locale localeM4259h = SubtypeManager.getInstance().getCurrentSubtypeLocale();
        if (!LocaleUtils.isChinese(localeM4259h)) {
            return true;
        }
        this.nuanceSdk.setInputMethod(LocaleUtils.toNuanceInputMethodName(localeM4259h));
        return true;
    }

    /**
     * Tracker form for the spell checker: the tracker is snapshotted on the calling thread (inside
     * the spell checker's own engine op).
     *
     * @param composingTracker the composing tracker
     * @param stripSettings the suggestion strip settings
     * @param sessionId the session id
     */
    @Override // dev.bbkb.ime.core.engine.Dictionary
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
        return generateSuggestions(ComposingSnapshot.of(composingTracker), prevWordsInfo, stripSettings, sessionId);
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestionsForSnapshot(ComposingSnapshot composing, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
        return generateSuggestions(composing, prevWordsInfo, stripSettings, sessionId);
    }

    /**
     * Builds the suggestion list. Runs on the engine thread as one op: feeds for this text were
     * posted before it and feeds for later text after it, so nothing writes the buffer meanwhile.
     *
     * @param composing the composing state the request was made for
     * @param stripSettings the suggestion strip settings
     * @param sessionId the session id
     */
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingSnapshot composing, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
        int i2;
        int i3;
        WordInfo wordInfo;
        if (composing == null) {
            composing = ComposingSnapshot.EMPTY;
        }

        String strM4360m = composing.text;
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:getSuggestions:start" +
                " composing=\"" + strM4360m + "\"" +
                " isPredMode=" + composing.isPredictionMode +
                " thread=" + Thread.currentThread().getName());
        }
        ArrayList<SuggestedWords.SuggestedWordInfo> arrayList = new ArrayList<>();

        // Mid-read mutation guard: sample the input-buffer epoch after our own replay, re-check it
        // after the list is read, and redo the read once if it moved. Only the spell checker's engine
        // (which shares the static epoch) can still move it. Never return empty: an empty result
        // makes SuggestionCoordinator keep the previous suggestions and the strip freezes.
        int iBuildSelectionList;
        int selectionListSize;
        for (int attempt = 0; ; attempt++) {
        arrayList.clear();

        // The touchEnd deposit runs on this same thread, so FIFO order alone decides whether the
        // clear() runs before it or sees the flag; no lock needed. Seq-only check: queued touchEnd
        // ops (counted by the main-side isGesturePending()) can never be seen from here.
        boolean gesturePending = TextUtils.isEmpty(strM4360m) && NuanceSDKManager.isGestureDepositPending();
        if (!gesturePending) {
            setNativeContext(prevWordsInfo, strM4360m);
            if (!TextUtils.isEmpty(strM4360m)) {
                // The replay is load-bearing: the typed-word guard's isSpellingCorrect lookup
                // (SuggestionEngine.getCorrections) just rewrote this buffer, and keys fed under
                // shift yield capitalised candidates; the replay restores the lower-case list the
                // case transform expects.
                if (!engineOwnsComposingState(composing, this.nuanceSdk)) {
                    this.nuanceSdk.clear();
                    for (int ci = 0; ci < strM4360m.length(); ci++) {
                        this.nuanceSdk.processKeyBySymbol(strM4360m.charAt(ci));
                    }
                }
            } else {
                // Next-word request: clear residual characters so predictions use only the
                // context, not completions of the previous word. Needed after ordinary commits too.
                this.nuanceSdk.clear();
            }
        }
        if (gesturePending) {
            // A swipe's word is the engine's inline word: ET9 gesture recognition returns count 0
            // from buildSelectionList and delivers the result via getInlineWord(). It is returned
            // as the single gesture suggestion. The branch reads the engine sets and the stored
            // path, so the next swipe's deposit must not land mid-branch; FIFO on the engine
            // thread guarantees that.
            String inlineGesture = this.nuanceSdk.getInlineWord();
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureInline inline=\"" + inlineGesture + "\"");
            // Owned ranking: the inline word is the raw default-symbol spell and the selection list
            // is the candidate pool; libkb ranks the pool against the raw path (per-row ideal-path
            // scoring) and the winner is the gesture word. Each deposit is keyed by its native seq
            // and ranked exactly once: the pending flag is cleared only by the main-thread commit
            // handler, so stale requests (the post-commit prediction) re-enter here and the ranker
            // would fabricate a word from the prediction pool.
            int rankSeq = com.blackberry.nuanceshim.Xt9KdbVariant.rankSeq();
            boolean freshDeposit = rankSeq > 0 && rankSeq != sConsumedRankSeq;
            // Separate from freshDeposit (which the C-rank take clears to skip the Java rank), so
            // the stale guard below does not wipe a word just taken.
            boolean gestureConsumed = false;
            // A word ranked in C at deposit time (debug.et9.ownrankc) is used verbatim: it was
            // built the instant the sets were fed, free of request-queue races.
            if (isOwnedDecodeLive() && freshDeposit) {
                String cWord = com.blackberry.nuanceshim.Xt9KdbVariant.takeGestureWord(rankSeq);
                if (!TextUtils.isEmpty(cWord)) {
                    sConsumedRankSeq = rankSeq;
                    if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureRankC word=\"" + cWord + "\" (inline was \"" + inlineGesture + "\") seq=" + rankSeq);
                    inlineGesture = cWord;
                    freshDeposit = false;
                    gestureConsumed = true;
                }
            }
            // A stale request must not touch gesture state: retaking the inline double-commits, and
            // the trailing clear() wipes the next deposit's sets. Fall through to normal handling.
            if (isOwnedDecodeLive() && !freshDeposit && !gestureConsumed) {
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureStale seq=" + rankSeq
                        + " consumed=" + sConsumedRankSeq + " — skipping gesture consumption");
                inlineGesture = "";
            }
            if (isOwnedDecodeLive() && freshDeposit && !TextUtils.isEmpty(inlineGesture)) {
                sConsumedRankSeq = rankSeq;
                long epochBefore = NuanceSDK.getInputBufferEpoch();
                this.nuanceSdk.buildSelectionList();
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureRankSeq seq=" + rankSeq
                        + " epochBefore=" + epochBefore + " epochAfter=" + NuanceSDK.getInputBufferEpoch());
                int n = Math.min(this.nuanceSdk.getSelectionListSize(), 32);
                if (n > 0) {
                    // The pool always holds the exact-input literal (top-symbol concatenation). A
                    // non-word literal ("hehlo") is dropped since it scores tautologically against
                    // its own path; a literal that is a dictionary word ("done") stays.
                    boolean literalIsWord = !TextUtils.isEmpty(inlineGesture)
                            && this.nuanceSdk.isSpellingCorrect(inlineGesture);
                    ArrayList<String> pool = new ArrayList<>(n);
                    for (int gi = 0; gi < n; gi++) {
                        WordInfo w = this.nuanceSdk.getSelectionListWord(gi);
                        if (w == null || TextUtils.isEmpty(w.word)) continue;
                        if (!literalIsWord && w.word.equalsIgnoreCase(inlineGesture)) continue;
                        pool.add(w.word);
                    }
                    if (!pool.isEmpty()) {
                        String[] cand = pool.toArray(new String[0]);
                        int win = com.blackberry.nuanceshim.Xt9KdbVariant.rankGesture(cand);
                        if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureOwnRank pool=" + cand.length
                                + " win=" + win + " word=\"" + (win >= 0 ? cand[win] : "-") + "\" (inline was \"" + inlineGesture + "\")");
                        if (win >= 0) inlineGesture = cand[win];
                    }
                }
            }
            if (!TextUtils.isEmpty(inlineGesture)) {
                WordInfo gwi = new WordInfo();
                gwi.word = inlineGesture;
                gwi.spell = inlineGesture;
                gwi.shouldAutoAccept = true;
                gwi.gestureSeq = rankSeq;   /* the commit handler consumes this seq only */
                arrayList.add(new SuggestedWords.SuggestedWordInfo(
                        inlineGesture, Integer.MAX_VALUE, 0, this, -1, Integer.MAX_VALUE, gwi));
                // So the next-word request does not score against the dead gesture.
                this.nuanceSdk.clear();
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:getSuggestions:done"
                        + " composing=\"\" count=1 words=[" + inlineGesture + "] (gesture inline)"
                        + " thread=" + Thread.currentThread().getName());
                return arrayList;
            }
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "NuanceDictionary.getSuggestions: gesturePending=true but inline empty — falling through to buildSelectionList");
        }

        // Sampled after our own clear and replay; buildSelectionList and getSelectionListWord do
        // not bump the epoch.
        final long epochAtReadStart = NuanceSDK.getInputBufferEpoch();

        // Not a count: the default-word index. buildSelectionList (@0x20424) calls vtable+0x38
        // (alphaBuildSelectionList) then vtable+0xf0 (alphaGetDefaultListIndex @0x1cf0c, ldrb
        // core+0x5DEA14); the size is vtable+0x50 (alphaGetTotalWords, ldrh core+0x5DEA12). Index 0
        // means the default is the typed word, which arms the duplicate below: the typed word's only
        // protection against the sugg[1] auto-correct heuristic.
        final int engineDefaultWordIndex = this.nuanceSdk.buildSelectionList();
        iBuildSelectionList = engineDefaultWordIndex;
        // selectionListSelectWord takes a raw index, so a WordInfo is selectable only while the
        // list keeps this generation.
        final long listGeneration = NuanceSDK.getSelectionListGeneration();

        selectionListSize = this.nuanceSdk.getSelectionListSize();
        final byte[] sources = sWordSourcesNeeded ? this.nuanceSdk.getSelectionListSources(selectionListSize) : null;
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:defaultIndex typed=\"" + strM4360m + "\""
                    + " engineDefaultWordIndex=" + engineDefaultWordIndex
                    + " listSize=" + selectionListSize
                    + " (0 = engine's default is the typed word -> typed word duplicated into"
                    + " sugg[1], which blocks auto-correct)");
        }
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:buildSelList" +
                " composing=\"" + strM4360m + "\"" +
                " result=" + iBuildSelectionList +
                " listSize=" + selectionListSize +
                " thread=" + Thread.currentThread().getName());
        }
        
        PunctuationSuggestions c0908wM4870d = getSuggestedPunctuations();
        Locale localeM4872e = getPrimaryEngineLocale();
        // Lower-cased once (hot path); null means no comparison is possible.
        final String typedLower = (strM4360m != null && localeM4872e != null)
                ? strM4360m.toLowerCase(localeM4872e) : null;
        
        int i4 = 0;
        while (i4 < selectionListSize) {
            WordInfo selectionListWord = this.nuanceSdk.getSelectionListWord(i4);
            if (selectionListWord != null) {
                selectionListWord.selectionListGeneration = listGeneration;
                selectionListWord.fromLearnedModel = sources != null && i4 < sources.length
                        && sources[i4] == NuanceSDK.WORD_SOURCE_LEARNED_MODEL;
            }
            // The engine builds its exact-word candidate from base letters (ď is on the d key), so
            // for an unknown word [0] comes back folded ("zďár" -> "zdar"). Restore the typed text
            // so it is classified kind 0, shown as typed and shielded from auto-correct. Letters
            // that do not decompose (đ, ł, ø) never match.
            if (i4 == 0 && selectionListWord != null && selectionListWord.word != null
                    && strM4360m.length() > 0 && !strM4360m.equals(selectionListWord.word)
                    && foldedEquals(strM4360m, selectionListWord.word, localeM4872e)) {
                if (BuildConfig.DEBUG) {
                    Log.d("PIPELINE", "IME:nuanceDict:diacriticsRestore engine='" + selectionListWord.word
                            + "' -> typed='" + strM4360m + "'");
                }
                selectionListWord.word = strM4360m;
                selectionListWord.spell = strM4360m;
            }
            if (selectionListWord == null) {
                i2 = i4;
                i3 = selectionListSize;
            } else if (isPunctuationSuggestion(selectionListWord, c0908wM4870d)) {
                i2 = i4;
                i3 = selectionListSize;
            } else {
                // Kinds: 1 = completion (prefix extension), 2 = correction (differing word).
                // SuggestionEngine.shouldAutoCorrect depends on this numbering; keep them in sync.
                int i5 = 2;
                final String wordLower = (selectionListWord.word != null && localeM4872e != null)
                        ? selectionListWord.word.toLowerCase(localeM4872e) : null;
                final boolean typedEqualsWord = typedLower != null && wordLower != null && typedLower.equals(wordLower);
                if (strM4360m.length() == 0) {
                    wordInfo = selectionListWord;
                    i2 = i4;
                    i5 = 8;
                } else if (i4 == 0 && typedEqualsWord && !LocaleUtils.isCurrentSubtypeJapanese()) {
                    wordInfo = selectionListWord;
                    i2 = i4;
                    i5 = 0;
                } else if (wordLower != null && typedLower != null && wordLower.startsWith(typedLower) && !typedEqualsWord) {
                    wordInfo = selectionListWord;
                    i2 = i4;
                    i5 = 1;
                } else if (i4 == 0 && !typedEqualsWord && LocaleUtils.isCurrentSubtypeChinese()) {
                    WordInfo wordInfo2 = new WordInfo();
                    wordInfo2.word = strM4360m;
                    i2 = i4;
                    arrayList.add(new SuggestedWords.SuggestedWordInfo(wordInfo2.word, selectionListSize - i4, 0, this, -1, Integer.MAX_VALUE, wordInfo2));
                    wordInfo = selectionListWord;
                } else {
                    i2 = i4;
                    wordInfo = selectionListWord;
                    if (EmojiTextAnalyzer.isEmoji(wordInfo.word)) {
                        i5 = 11;
                    }
                }
                int i6 = selectionListSize - i2;
                i3 = selectionListSize;
                arrayList.add(new SuggestedWords.SuggestedWordInfo(wordInfo.word, i6, i5, this, -1, Integer.MAX_VALUE, wordInfo));
                if (i2 == 0 && iBuildSelectionList == 0 && strM4360m.length() > 0 && i5 == 0) {
                    arrayList.add(new SuggestedWords.SuggestedWordInfo(wordInfo.word, i6, 0, this, -1, Integer.MAX_VALUE, wordInfo));
                }
            }
            i4 = i2 + 1;
            selectionListSize = i3;
        }

        if (NuanceSDK.getInputBufferEpoch() == epochAtReadStart || attempt >= 1) {
            break;
        }
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:reread"
                + " reason=input-buffer mutated mid-read (EB-2)"
                + " composing=\"" + strM4360m + "\""
                + " thread=" + Thread.currentThread().getName());
        }
        }

        // Drop stuttering multi-word predictions ("Hello hello hello") from the DLM's context
        // state. No engine API removes them at source: ET9AWDLM* is word-level only, and the
        // ET9CPDLM* phrase API is the Chinese module (zhdlm.bin, not shipped).
        for (int fi = arrayList.size() - 1; fi >= 0; fi--) {
            if (hasAdjacentDuplicateWords(arrayList.get(fi).word)) {
                arrayList.remove(fi);
            }
        }

        if (BuildConfig.DEBUG) {
            StringBuilder sb = new StringBuilder();
            for (int idx = 0; idx < arrayList.size(); idx++) {
                if (idx > 0) sb.append(",");
                sb.append(arrayList.get(idx).word);
            }
            if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:getSuggestions:done" +
                " composing=\"" + strM4360m + "\"" +
                " count=" + arrayList.size() +
                " words=[" + sb + "]" +
                " thread=" + Thread.currentThread().getName());
            }
        }

        // Kinds: 0 typed, 1 completion, 2 correction, 8 prediction, 11 emoji. Compare with the raw
        // XT9WORDS dump to tell engine output from bridge choice.
        if (InputPathDebug.on()) {
            StringBuilder kinds = new StringBuilder();
            for (int ki = 0; ki < arrayList.size(); ki++) {
                if (ki > 0) kinds.append(" | ");
                kinds.append('\'').append(arrayList.get(ki).word).append("' kind=")
                     .append(arrayList.get(ki).getKind());
            }
            Logger.info("CKB_SWIPE_TYPE_DEBUG", "NuanceDictionary.getSuggestions: assembled composing='"
                    + strM4360m + "' -> " + kinds);
        }

        // The buffer is left as the feeds made it: later feeds and commit clears are queued
        // behind this op, so replaying or clearing here would apply them twice.
        return arrayList;
    }

    /**
     * True when the engine buffer must be left as the typing path built it: the Hangul, Telex and
     * Romaji converters and the stroke and Cangjie maps feed something other than the composing
     * characters (jamo, base letters plus accents, stroke numbers, romaji), so a replay breaks it.
     */
    static boolean engineOwnsComposingState(ComposingTextTracker tracker, NuanceSDK sdk) {
        return tracker.hasInputMethodConverter() || sdk.isChineseStrokeMode() || sdk.isChineseCangjieMode();
    }

    /** {@link #engineOwnsComposingState(ComposingTextTracker, NuanceSDK)} for a request's snapshot. */
    static boolean engineOwnsComposingState(ComposingSnapshot composing, NuanceSDK sdk) {
        return composing.hasInputMethodConverter || sdk.isChineseStrokeMode() || sdk.isChineseCangjieMode();
    }

    /** @param composingText the composing text */
    private void setNativeContext(PrevWordsInfo prevWordsInfo, String composingText) {
        if (prevWordsInfo != null && prevWordsInfo.getContextBefore() != null) {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceDict:setCtxBuf" +
                    " prevWord=\"" + prevWordsInfo.getContextBefore() + "\"" +
                    " composing=\"" + composingText + "\"" +
                    " thread=" + Thread.currentThread().getName());
            }
            this.nuanceSdk.setContextBuffer(prevWordsInfo.getContextBefore());
            clearNativeBufferIfJapanese(composingText);
        } else {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceDict:setCtxBufEmpty" +
                    " prevWordsNull=" + (prevWordsInfo == null) +
                    " composing=\"" + composingText + "\"" +
                    " thread=" + Thread.currentThread().getName());
            }
            this.nuanceSdk.setContextBuffer("");
        }
    }

    /** @param composingText the composing text */
    private void clearNativeBufferIfJapanese(String composingText) {
        if (TextUtils.isEmpty(composingText) && LocaleUtils.isCurrentSubtypeJapanese()) {
            this.nuanceSdk.clear();
        }
    }

    private PunctuationSuggestions getSuggestedPunctuations() {
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        if (c0804dM5050c != null) {
            return c0804dM5050c.spacingAndPunctuation.suggestedPunctuations;
        }
        return null;
    }

    private Locale getPrimaryEngineLocale() {
        if (this.nuanceSdk.getLanguage().length > 0) {
            return this.nuanceSdk.getLanguage()[0];
        }
        return null;
    }

    /** @param punctuationSuggestions the suggested punctuation, or null */
    static boolean isPunctuationSuggestion(WordInfo wordInfo, PunctuationSuggestions punctuationSuggestions) {
        if (".".equals(wordInfo.word)) {
            return true;
        }
        if (punctuationSuggestions == null) {
            return false;
        }
        for (int i = 0; i < punctuationSuggestions.size(); i++) {
            if (wordInfo.word.equals(punctuationSuggestions.getWordForDisplay(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inserts the personal dictionary substitution for the typed word; true when one was added.
     *
     * @param composingSnapshot the composing state
     * @param arrayList the suggestions, modified in place
     * @param suggestionResult the suggestion result (for its locale)
     */
    public boolean shouldAutoCorrect(ComposingSnapshot composingSnapshot, ArrayList<SuggestedWords.SuggestedWordInfo> arrayList, SuggestionResult suggestionResult) {
        DictionaryManager.CapsMode bVar;
        String strM4360m = composingSnapshot.text;
        if (TextUtils.isEmpty(strM4360m)) {
            if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: SKIP empty composing");
            return false;
        }
        if (composingSnapshot.isAllCaps) {
            bVar = DictionaryManager.CapsMode.ALL_CAPS;
        } else if (composingSnapshot.shouldCapitalizeFirstLetter) {
            bVar = DictionaryManager.CapsMode.FIRST_LETTER_CAPS;
        } else {
            bVar = DictionaryManager.CapsMode.NO_CAPS;
        }
        boolean pduLoaded = DictionaryManager.getInstance().isLoaded();
        if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: typed='" + strM4360m + "' caps=" + bVar + " pduLoaded=" + pduLoaded + " suggestionsSize=" + arrayList.size());
        try {
            String strM4863a = getWordSubstitution(strM4360m, bVar, suggestionResult.locale);
            if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: substitution='" + strM4863a + "' changed=" + (!strM4863a.equals(strM4360m)));
            if (!strM4863a.equals(strM4360m)) {
                Logger.debug("NuanceDictionary", "Adding substitution: " + strM4863a);
                arrayList.add(LocaleUtils.isCurrentSubtypeJapanese() ? 0 : 1, new SuggestedWords.SuggestedWordInfo(strM4863a, arrayList.size(), 7, DICTIONARY_APPLICATION_DEFINED, -1, Integer.MAX_VALUE, null));
                return true;
            }
        } catch (SecurityException e) {
            // No READ_PHONE_STATE, so the phone number macro can throw.
            Logger.info("NuanceDictionary", "Word substitution failed for " + strM4360m + ": " + e.getMessage());
            if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: SecurityException for '" + strM4360m + "'");
        }
        return false;
    }

    /** The POSIX US-ASCII {@code \p{Punct}} class, tested without a regex (hot path). */
    private static boolean isPosixPunct(char c) {
        return (c >= '!' && c <= '/')
                || (c >= ':' && c <= '@')
                || (c >= '[' && c <= '`')
                || (c >= '{' && c <= '~');
    }

    /**
     * The substitution for the typed word, also tried without a trailing punctuation mark.
     *
     * @param input the typed word
     * @param capsMode its caps mode
     */
    private String getWordSubstitution(String input, DictionaryManager.CapsMode capsMode, Locale locale) {
        DictionaryManager c0624aM3831b = DictionaryManager.getInstance();
        String strM3845a = c0624aM3831b.getWordSubstitutionForInput(input, capsMode, locale);
        if (input.length() <= 0 || !isPosixPunct(input.charAt(input.length() - 1))) {
            return strM3845a;
        }
        String strSubstring = input.substring(0, input.length() - 1);
        if (strSubstring.length() <= 0) {
            return strM3845a;
        }
        String strM3845a2 = c0624aM3831b.getWordSubstitutionForInput(strSubstring, capsMode, locale);
        if (strSubstring.equals(strM3845a)) {
            return strM3845a;
        }
        return strM3845a2 + input.substring(input.length() - 1);
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean isWordValid(String word) {
        return this.nuanceSdk.isSpellingCorrect(word);
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean isDictionaryWord(String word) {
        return this.nuanceSdk.isSpellingCorrectWithoutLearnedWords(word);
    }

    /**
     * Removes the word's learned entry (the static lexicon is untouched), trying the lower-case form
     * too: a sentence-start correction commits "Bout" for a learned "bout".
     */
    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean unlearnWord(String word) {
        if (TextUtils.isEmpty(word)) {
            return false;
        }
        // Both deletes are one engine op (the retry needs the first answer); true once queued.
        final NuanceSDK sdk = this.nuanceSdk;
        EngineThreads.post(() -> {
            boolean removed = sdk.deleteDLMWord(word);
            if (!removed) {
                Locale locale = getPrimaryEngineLocale();
                String lower = word.toLowerCase(locale != null ? locale : Locale.ROOT);
                removed = !lower.equals(word) && sdk.deleteDLMWord(lower);
            }
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:unlearn word=\"" + word + "\" -> " + removed);
        });
        return true;
    }

    /**
     * Read from the published facts, never the engine (it changes only with the language): this
     * bridge's engine's facts, else the primary's.
     */
    @Override // dev.bbkb.ime.core.engine.Dictionary
    public String getWordSeparator(String precedingText) {
        EngineFacts facts = this.nuanceSdk != null ? this.nuanceSdk.getFacts() : null;
        return wordSeparatorFor(facts != null ? facts : PrimaryEngineFacts.get());
    }

    /** The separator a word is committed with under {@code facts}: none for Chinese. */
    static String wordSeparatorFor(EngineFacts facts) {
        return PrimaryEngineFacts.wordSeparator(facts);
    }

    /**
     * @param contextBefore the text holding the recorrected word
     * @param cursorPosition the recorrection cursor position in {@code contextBefore}
     * @param originalWord the original word
     * @param currentWord the current (replacement) word
     */
    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean onWordChanged(String contextBefore, int cursorPosition, String originalWord, String currentWord) {
        if (contextBefore == null || originalWord == null || currentWord == null || contextBefore.isEmpty() || originalWord.isEmpty() || currentWord.isEmpty()) {
            return false;
        }
        // Posted: learning only, nothing waits for it.
        final NuanceSDK sdk = this.nuanceSdk;
        EngineThreads.post(() -> sdk.wordChanged(contextBefore, cursorPosition, originalWord, currentWord));
        return true;
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean addLocales(Set<Locale> set) {
        super.addLocales(set);
        this.locales.addAll(set);
        return loadLanguagePacks();
    }

    /**
     * True when {@code s} has the same word twice in a row, ignoring case ("Hello hello"). Only
     * adjacent repeats count, so "the more the merrier" is kept.
     */
    static boolean hasAdjacentDuplicateWords(String s) {
        if (s == null || s.length() < 3) return false;
        int i = 0, n = s.length();
        int prevStart = -1, prevEnd = -1;
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
            if (prevStart >= 0) {
                int prevLen = prevEnd - prevStart;
                if (prevLen == i - start
                        && s.regionMatches(true, prevStart, s, start, prevLen)) {
                    return true;
                }
            }
            prevStart = start;
            prevEnd = i;
        }
        return false;
    }
}
