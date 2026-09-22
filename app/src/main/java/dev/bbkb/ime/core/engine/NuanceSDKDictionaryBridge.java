package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;

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

    public NuanceSDKDictionaryBridge(NuanceSDK nuanceSDK, Context context, Locale locale) {
        super("main");
        this.initialized = true;
        this.nuanceSdk = nuanceSDK;
        this.locales = new LinkedHashSet();
        this.locales.add(locale);
        this.context = context;
        loadLanguagePacks();
    }

    // §8.7.11: the deposit sequence this bridge last RANKED — each native deposit is ranked
    // exactly once, no matter how many worker requests race over the pending flag. Static: the
    // deposit is engine-global, and the bridge can be re-instantiated across language reloads.
    private static volatile int sConsumedRankSeq = 0;

    // Owned gesture ranking is live when debug.et9.owndecode=1 (the owned decoder fed the engine,
    // so the selection list is a candidate pool to be re-ranked against the path — §8.7.6). Cached
    // per instance; the prop is set-then-force-stop like the native levers.
    private int mOwnedDecodeCached = -1;
    private boolean isOwnedDecodeLive() {
        if (!BuildConfig.DEBUG) return false;
        if (mOwnedDecodeCached < 0) {
            // Must match the C default (kdb_trace.c OWNED_DEFAULT): in DEBUG builds the owned
            // decoder is on when the prop is UNSET (survives reboot, setprops don't). If the Java
            // gate defaulted OFF on an empty prop while C decoded owned, the ranked word would
            // never be taken and the raw skeleton would commit (togeteer/togetheer junk — 2026-08-19).
            mOwnedDecodeCached = 1;   // empty/unset -> ON (debug default)
            String v = SystemProps.get("debug.et9.owndecode");
            if (v != null && !v.isEmpty()) mOwnedDecodeCached = (v.charAt(0) == '1') ? 1 : 0;
        }
        return mOwnedDecodeCached == 1;
    }

    /**
     * True when the two strings are equal after stripping diacritics (NFD + remove combining
     * marks) and lowercasing — i.e. {@code engineWord} is a diacritic-folded twin of
     * {@code typed}. Used to recognize the engine's folded exact-word candidate as the typed word.
     */
    private static boolean foldedEquals(String typed, String engineWord, Locale locale) {
        if (typed.length() != engineWord.length()) {
            // Folding never changes length for the fold class we care about (one base letter per
            // accented letter); a length mismatch means a genuinely different word.
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
        if (!LanguagePackManager.getInstance(this.context).setLanguages(this.nuanceSdk, (Locale[]) this.locales.toArray(new Locale[0]))) {
            return false;
        }
        Locale localeM4259h = SubtypeManager.getInstance().getCurrentSubtypeLocale();
        if (!LocaleUtils.isChinese(localeM4259h)) {
            return true;
        }
        this.nuanceSdk.setInputMethod(LocaleUtils.toNuanceLocaleString(localeM4259h));
        return true;
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker c0670ag, PrevWordsInfo prevWordsInfo, SuggestionStripSettings c0805e, int i) {
        int i2;
        int i3;
        WordInfo wordInfo;
        
        // Enhanced logging for suggestion generation
        
        String strM4360m = c0670ag.getComposingText();
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:getSuggestions:start" +
                " composing=\"" + strM4360m + "\"" +
                " isPredMode=" + c0670ag.isPredictionMode() +
                " thread=" + Thread.currentThread().getName());
        }
        ArrayList<SuggestedWords.SuggestedWordInfo> arrayList = new ArrayList<>();

        // Audit EB-2. This read sequence (clear -> replay -> buildSelectionList ->
        // getSelectionListWord) runs on the suggestion worker and holds no lock against the main
        // thread's tracker-mediated engine writes, so a keystroke or backspace can land in the
        // middle of it and the list gets scored against a symbol stream that never existed.
        //
        // The first attempt at this (reverted 340b1901) compared tracker state before and after
        // the whole method and discarded the list when it differed. That treats the NORMAL case as
        // corruption -- at typing speed the tracker advances during almost every request -- so
        // nearly every result was thrown away, and returning empty makes SuggestionCoordinator
        // merge the typed word with the PREVIOUS suggestions: the strip froze on stale duplicates.
        //
        // What distinguishes the two cases is whether the engine's input buffer was rewritten
        // INSIDE the read window, which is a signal only the shim can give (NuanceSDK's epoch
        // counter). A tracker that merely advanced is "superseded" and is already handled by the
        // delivery-time guards. So: sample the epoch AFTER our own replay (never trip on our own
        // writes), re-check it after the list is collected, and on a genuine mid-read mutation
        // redo the read against current tracker text. Bounded to one retry, and it never returns
        // empty -- that is what froze the strip last time.
        int iBuildSelectionList;
        int selectionListSize;
        for (int attempt = 0; ; attempt++) {
        arrayList.clear();
        if (attempt > 0) {
            strM4360m = c0670ag.getComposingText();   // resync: the buffer moved under us
        }

        // Read the flag and act on it under the gesture lock, so a concurrent touchEnd on the
        // main thread cannot deposit gesture state (and set the flag) in the gap between our
        // read and our clear(). See NuanceSDKManager.gestureLock.
        boolean gesturePending;
        synchronized (NuanceSDKManager.getGestureLock()) {
            gesturePending = TextUtils.isEmpty(strM4360m) && NuanceSDKManager.isGesturePending();
            if (!gesturePending) {
                setNativeContext(prevWordsInfo, strM4360m);
                if (!TextUtils.isEmpty(strM4360m)) {
                    this.nuanceSdk.clear();
                    for (int ci = 0; ci < strM4360m.length(); ci++) {
                        this.nuanceSdk.processKeyBySymbol(strM4360m.charAt(ci));
                    }
                } else {
                    // FIX: For next-word prediction requests (empty composing), defensively clear
                    // the native engine's character buffer so buildSelectionList() returns true
                    // next-word predictions against prevWord context, not residual completions
                    // of the previously-typed word. Guards against any commit path that fails to
                    // transition the engine (e.g. addWord-only branches in CommitController).
                    this.nuanceSdk.clear();
                }
            }
        }
        if (gesturePending) {
            // A swipe's recognised word is the engine's INLINE word, NOT the selection list:
            // ET9 gesture recognition returns count==0 from buildSelectionList and delivers the
            // result via getInlineWord() (confirmed live: "build: count=0 inline='very'"). The
            // original app committed getInlineWord() directly (see NuanceSDK convertText path);
            // a rebuild replaced that with buildSelectionList(), which is empty for gestures, so
            // every swipe reconstructed garbage from an empty list. Restore the original: read the
            // inline word and return it as the (single) gesture suggestion.
            //
            // The ENTIRE branch holds the gesture lock: the owned ranking (§8.7.6) reads the
            // engine's sets AND the native stored path, and a fast next swipe's touchEnd deposit
            // must not land in between — unlocked, corpus-A ranked every gesture against the
            // FOLLOWING swipe's path (§8.7.10: winner == next word, off-by-one down the list).
            // touchEnd takes this same lock to deposit, so consumption is atomic against it.
            synchronized (NuanceSDKManager.getGestureLock()) {
            String inlineGesture = this.nuanceSdk.getInlineWord();
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureInline inline=\"" + inlineGesture + "\"");
            // OWNED RANKING (remediation log §8.7.6): when the owned decode path fed the engine
            // (debug.et9.owndecode=1), the inline word is the raw default-symbol spell and the
            // selection list is the CANDIDATE POOL (blob tap-matcher = recall; option-b evidence:
            // the intended word is present but tap-ranked). Rank the pool against the raw path in
            // libkb (per-row ideal-path scoring, §8.7.6) and use the winner as the gesture word.
            // Falls back to the inline word if ranking is unavailable.
            // §8.7.11 consume-once: the pending FLAG is only consumed by the main-thread commit
            // handler, so stale worker requests (e.g. the prediction request that follows every
            // commit) can re-enter this branch against dead engine state. Pre-ranking that was
            // harmless (empty inline falls through); the ranker would FABRICATE a word from the
            // prediction pool. Key each deposit by its native sequence and rank it exactly once —
            // and never rank when the inline is empty (no gesture state to rank).
            int rankSeq = com.blackberry.nuanceshim.Xt9KdbVariant.rankSeq();
            boolean freshDeposit = rankSeq > 0 && rankSeq != sConsumedRankSeq;
            // W6 sitting-1 fix: `freshDeposit` is overloaded — line ~199 clears it to mean "the
            // C-rank was consumed, skip the Java re-rank", but the §8.7.11b stale-guard below also
            // keys on !freshDeposit and would then wipe the word we JUST consumed (count=1 gesture
            // return fired 0× in the collection log; every gesture fell through to the raw pool ->
            // the committed text was the geometry skeleton, ~24% vs the ranker's ~89%). Track the
            // consume separately so the stale-guard only suppresses genuine re-entrant requests.
            boolean gestureConsumed = false;
            // W1 (§8.7.14): if the deposit ranked the word IN C (debug.et9.ownrankc), take it —
            // the selection list was built+ranked synchronously the instant the sets were fed, so
            // the request-queue race that contaminates the Java-side build (§8.7.13) is impossible.
            // Non-null → use verbatim and skip the Java build/rank entirely.
            if (isOwnedDecodeLive() && freshDeposit) {
                String cWord = com.blackberry.nuanceshim.Xt9KdbVariant.takeGestureWord(rankSeq);
                if (!TextUtils.isEmpty(cWord)) {
                    sConsumedRankSeq = rankSeq;
                    if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureRankC word=\"" + cWord + "\" (inline was \"" + inlineGesture + "\") seq=" + rankSeq);
                    inlineGesture = cWord;
                    freshDeposit = false;   // consumed here; skip the Java-side rank below
                    gestureConsumed = true; // ...but this is NOT a stale re-entry — keep the word
                }
            }
            // §8.7.11b: a STALE request (throttled mid-swipe / post-commit re-entry — the pending
            // flag stays true until the main-thread commit handler) must not touch gesture state
            // AT ALL: taking the raw inline again double-commits, and the trailing clear() wipes
            // the NEXT deposit's freshly-fed sets before its own request arrives (the fresh-seq +
            // real-inline + PREDICTION-pool anomaly of gate 5). Fall through to normal handling.
            if (isOwnedDecodeLive() && !freshDeposit && !gestureConsumed) {
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureStale seq=" + rankSeq
                        + " consumed=" + sConsumedRankSeq + " — skipping gesture consumption");
                inlineGesture = "";   // suppress the consumption path below entirely
            }
            if (isOwnedDecodeLive() && freshDeposit && !TextUtils.isEmpty(inlineGesture)) {
                sConsumedRankSeq = rankSeq;
                long epochBefore = NuanceSDK.getInputBufferEpoch();
                this.nuanceSdk.buildSelectionList();
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:gestureRankSeq seq=" + rankSeq
                        + " epochBefore=" + epochBefore + " epochAfter=" + NuanceSDK.getInputBufferEpoch());
                int n = Math.min(this.nuanceSdk.getSelectionListSize(), 32);
                if (n > 0) {
                    // Build the ranking pool. The tap generator always offers the exact-input
                    // string (the decoder's top-symbol concatenation) as a candidate; a NON-WORD
                    // literal ("hehlo", "fone") must not compete — its ideal key-path is a
                    // projection of the swipe, so it scores tautologically (§8.7.6: "hehlo" edged
                    // out "hello" by 0.06). But with the MINIMAL feed the literal is often the
                    // intended word itself ("done" — §8.7.8: excluding it crowned "dome"), so a
                    // literal that IS a dictionary word stays in the pool like any candidate.
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
                gwi.gestureSeq = rankSeq;   /* §8.7.11c: commit handler consumes THIS seq only */
                arrayList.add(new SuggestedWords.SuggestedWordInfo(
                        inlineGesture, Integer.MAX_VALUE, 0, this, -1, Integer.MAX_VALUE, gwi));
                // Clear the native gesture state now that we've consumed the inline result, so the
                // next request (next-word prediction) doesn't score against the dead gesture.
                this.nuanceSdk.clear();
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:nuanceDict:getSuggestions:done"
                        + " composing=\"\" count=1 words=[" + inlineGesture + "] (gesture inline)"
                        + " thread=" + Thread.currentThread().getName());
                return arrayList;
            }
            }   // gesture lock — held through inline read + ranking + clear (§8.7.10)
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "NuanceDictionary.getSuggestions: gesturePending=true but inline empty — falling through to buildSelectionList");
        }
        
        // Sampled AFTER our own clear+replay above, so the worker never trips on its own writes.
        // buildSelectionList/getSelectionListWord are reads and do not bump the epoch.
        final long epochAtReadStart = NuanceSDK.getInputBufferEpoch();

        // NOT a candidate count: ET9AWSelLstBuild's DEFAULT-WORD INDEX. Proven in the original
        // APK's bytecode -- Java_..._buildSelectionList @0x20424 calls vtable+0x38
        // (alphaBuildSelectionList) then vtable+0xf0 (alphaGetDefaultListIndex @0x1cf0c, ldrb from
        // core+0x5DEA14), while getSelectionListSize reads a different slot (+0x50,
        // alphaGetTotalWords, ldrh from core+0x5DEA12). Index 0 means "the engine's default IS the
        // typed word", which is what arms the duplicate below -- the only protection a validly
        // typed word has against the sugg[1] auto-correct heuristic. Misreading it as a count is
        // how it got mistaken for dead code (2026-09-15).
        final int engineDefaultWordIndex = this.nuanceSdk.buildSelectionList();
        iBuildSelectionList = engineDefaultWordIndex;
        // Audit EB-3: the generation these indices belong to. selectionListSelectWord takes a raw
        // index, so a WordInfo is only safely selectable while the list still has this identity.
        final long listGeneration = NuanceSDK.getSelectionListGeneration();

        selectionListSize = this.nuanceSdk.getSelectionListSize();
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
        // GD-9 (hot path): the typed word and the locale are loop-invariant, but the kind-decision
        // chain below used to re-lower-case them up to six times per candidate. Compute once here
        // and once per candidate; null means "no comparison possible", exactly what the old
        // per-clause `x != null` guards expressed.
        final String typedLower = (strM4360m != null && localeM4872e != null)
                ? strM4360m.toLowerCase(localeM4872e) : null;
        
        int i4 = 0;
        while (i4 < selectionListSize) {
            WordInfo selectionListWord = this.nuanceSdk.getSelectionListWord(i4);
            if (selectionListWord != null) {
                selectionListWord.selectionListGeneration = listGeneration;   // audit EB-3
            }
            // Diacritics fix (2026-08-27): the engine resolves accented symbols to their host key
            // (KDB char lists: ď->d-key) and builds its exact-word candidate from BASE letters, so
            // for a word not in the dictionary candidate [0] comes back folded ("zďár" -> "zdar",
            // confirmed by DIACPROBE on-device). Every UI commit path (flick/swipe-up, strip pick)
            // commits candidate text, so typed diacritics were lost. The literal typed word only
            // survives in the Java composing text — when [0] is a diacritic-folded twin of it,
            // restore the typed text into that candidate. The strict exact-match check below then
            // classifies it kind 0 (exact typed word), which both displays it as typed and shields
            // it from auto-correct. Folding uses NFD + strip combining marks; letters that do not
            // decompose (đ, ł, ø) simply never match, leaving behavior unchanged for them.
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
                // Kind numbering is SWAPPED vs the original APK (swap introduced 2025-03-29):
                // ours: 1 = COMPLETION (prefix extension), 2 = CORRECTION (differing word);
                // original r.java, AOSP-style: 1 = CORRECTION, 2 = COMPLETION.
                // SuggestionEngine.shouldAutoCorrect is written against OUR numbering — corrections
                // (2) auto-correct unconditionally, completions (1) go through the length gate.
                // Keep the two files in sync if this ever changes.
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
                    // kind 1 = COMPLETION (prefix expansion, e.g. "help" when "hel" typed)
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

        // EB-2: did the engine's input buffer change while we were scoring and reading it?
        // Retry once against current tracker text; a second disturbance is vanishingly unlikely
        // and we deliver what we have rather than ever returning empty.
        if (NuanceSDK.getInputBufferEpoch() == epochAtReadStart || attempt >= 1) {
            break;
        }
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:nuanceDict:reread"
                + " reason=input-buffer mutated mid-read (EB-2)"
                + " composing=\"" + strM4360m + "\""
                + " thread=" + Thread.currentThread().getName());
        }
        }   // end EB-2 retry loop

        // Drop multi-word predictions that stutter ("Hello hello hello"). These come from the
        // DLM's n-gram/context state, and the engine gives us no way to remove them at source:
        // ET9AWDLM* is entirely word-level (no DeletePhrase, no phrase enumeration), and the
        // ET9CPDLM* phrase API is the Chinese module, backed by a zhdlm.bin we do not ship.
        // Filtering here is therefore the only per-phrase control available. See
        // docs/2026-07_frozen-suggestions_investigation.md.
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

        // Kind legend: 0=typed 1=completion 2=correction 8=prediction 11=emoji. Pairs with the
        // raw engine dump (XT9WORDS) to separate "engine returned garbage" from "bridge picked
        // the wrong candidate" when swipe results go bad.
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

        // Audit EB-7: restore the engine buffer from what the tracker says NOW, not from the
        // entry snapshot — replaying a stale snapshot leaves copy 4 (the native buffer)
        // diverged from copy 3 (the tracker) until the next read.
        //
        // Deliberately NOT discarding the assembled list when the tracker advanced during the
        // read (an earlier attempt at audit EB-2 did, and it broke live suggestions — see
        // below). The tracker advancing between a request starting and finishing is the NORMAL
        // case at typing speed, not a corruption signal: a list built for "Hel" is valid for
        // "Hel", merely superseded, and staleness is already handled at delivery by
        // InputLogic.onSuggestionsReceived's tracker/cursor guards. Discarding here starved the
        // strip — every keystroke's result was thrown away by the next keystroke — and an empty
        // return makes SuggestionCoordinator fall back to merging the typed word with the
        // PREVIOUS suggestions, so the strip showed stale near-duplicates ("Hello hello hello").
        String composingNow = c0670ag.getComposingText();

        if (!TextUtils.isEmpty(composingNow)) {
            this.nuanceSdk.clear();
            for (int ci = 0; ci < composingNow.length(); ci++) {
                this.nuanceSdk.processKeyBySymbol(composingNow.charAt(ci));
            }
        } else if (!TextUtils.isEmpty(strM4360m)) {
            // The word was committed or cleared while we read: leave no residue behind, or the
            // next next-word-prediction request scores against a dead word (the May 2026
            // post-commit-predictions bug, arrived at from the other direction).
            this.nuanceSdk.clear();
        }

        return arrayList;
    }

    private void setNativeContext(PrevWordsInfo prevWordsInfo, String str) {
        if (prevWordsInfo != null && prevWordsInfo.getContextBefore() != null) {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceDict:setCtxBuf" +
                    " prevWord=\"" + prevWordsInfo.getContextBefore() + "\"" +
                    " composing=\"" + str + "\"" +
                    " thread=" + Thread.currentThread().getName());
            }
            this.nuanceSdk.setContextBuffer(prevWordsInfo.getContextBefore());
            clearNativeBufferIfJapanese(str);
        } else {
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:nuanceDict:setCtxBufEmpty" +
                    " prevWordsNull=" + (prevWordsInfo == null) +
                    " composing=\"" + str + "\"" +
                    " thread=" + Thread.currentThread().getName());
            }
            this.nuanceSdk.setContextBuffer("");
        }
    }

    private void clearNativeBufferIfJapanese(String str) {
        if (TextUtils.isEmpty(str) && LocaleUtils.isCurrentSubtypeJapanese()) {
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

    static boolean isPunctuationSuggestion(WordInfo wordInfo, PunctuationSuggestions c0908w) {
        if (".".equals(wordInfo.word)) {
            return true;
        }
        if (c0908w == null) {
            return false;
        }
        for (int i = 0; i < c0908w.size(); i++) {
            if (wordInfo.word.equals(c0908w.getWordForDisplay(i))) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldAutoCorrect(ComposingTextTracker c0670ag, ArrayList<SuggestedWords.SuggestedWordInfo> arrayList, SuggestionResult c0873an) {
        DictionaryManager.CapsMode bVar;
        String strM4360m = c0670ag.getComposingText();
        if (TextUtils.isEmpty(strM4360m)) {
            if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: SKIP empty composing");
            return false;
        }
        if (c0670ag.isAllCaps()) {
            bVar = DictionaryManager.CapsMode.ALL_CAPS;
        } else if (c0670ag.shouldCapitalizeFirstLetter()) {
            bVar = DictionaryManager.CapsMode.FIRST_LETTER_CAPS;
        } else {
            bVar = DictionaryManager.CapsMode.NO_CAPS;
        }
        boolean pduLoaded = DictionaryManager.getInstance().isLoaded();
        if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: typed='" + strM4360m + "' caps=" + bVar + " pduLoaded=" + pduLoaded + " suggestionsSize=" + arrayList.size());
        try {
            String strM4863a = getWordSubstitution(strM4360m, bVar, c0873an.locale);
            if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: substitution='" + strM4863a + "' changed=" + (!strM4863a.equals(strM4360m)));
            if (!strM4863a.equals(strM4360m)) {
                Logger.debug("NuanceDictionary", "Adding substitution: " + strM4863a);
                arrayList.add(LocaleUtils.isCurrentSubtypeJapanese() ? 0 : 1, new SuggestedWords.SuggestedWordInfo(strM4863a, arrayList.size(), 7, DICTIONARY_APPLICATION_DEFINED, -1, Integer.MAX_VALUE, null));
                return true;
            }
        } catch (SecurityException e) {
            // READ_PHONE_STATE permission removed for privacy - phone number macro disabled
            Logger.info("NuanceDictionary", "Word substitution failed for " + strM4360m + ": " + e.getMessage());
            if (BuildConfig.DEBUG) Log.d("AC_DEBUG", "NuanceDict.shouldAutoCorrect: SecurityException for '" + strM4360m + "'");
        }
        return false;
    }

    /**
     * GD-8: {@code Pattern.matches("\\p{Punct}", ...)} used to compile a fresh regex (and allocate
     * a substring) on every auto-correct decision. {@code \p{Punct}} is the POSIX US-ASCII
     * punctuation class — the four ASCII ranges below, nothing else — so test the char directly.
     */
    private static boolean isPosixPunct(char c) {
        return (c >= '!' && c <= '/')
                || (c >= ':' && c <= '@')
                || (c >= '[' && c <= '`')
                || (c >= '{' && c <= '~');
    }

    private String getWordSubstitution(String str, DictionaryManager.CapsMode bVar, Locale locale) {
        DictionaryManager c0624aM3831b = DictionaryManager.getInstance();
        String strM3845a = c0624aM3831b.getWordSubstitutionForInput(str, bVar, locale);
        if (str.length() <= 0 || !isPosixPunct(str.charAt(str.length() - 1))) {
            return strM3845a;
        }
        String strSubstring = str.substring(0, str.length() - 1);
        if (strSubstring.length() <= 0) {
            return strM3845a;
        }
        String strM3845a2 = c0624aM3831b.getWordSubstitutionForInput(strSubstring, bVar, locale);
        if (strSubstring.equals(strM3845a)) {
            return strM3845a;
        }
        return strM3845a2 + str.substring(str.length() - 1);
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean isWordValid(String str) {
        return this.nuanceSdk.isSpellingCorrect(str);
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public String getWordSeparator(String str) {
        return this.nuanceSdk.isCurrLocaleChinese() ? "" : String.valueOf(this.nuanceSdk.getDefaultWordSeparator());
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean onWordChanged(String str, int i, String str2, String str3) {
        if (str == null || str2 == null || str3 == null || str.isEmpty() || str2.isEmpty() || str3.isEmpty()) {
            return false;
        }
        return this.nuanceSdk.wordChanged(str, i, str2, str3);
    }

    @Override // dev.bbkb.ime.core.engine.Dictionary
    public boolean addLocales(Set<Locale> set) {
        super.addLocales(set);
        this.locales.addAll(set);
        return loadLanguagePacks();
    }

    /**
     * True when {@code s} is a multi-word suggestion containing the same word twice in a row,
     * ignoring case — "Hello hello hello", "I have I have have".
     *
     * <p>Deliberately narrow. It tests <em>adjacent</em> repeats only, so legitimate phrases that
     * reuse a word at a distance are kept: "the more the merrier" repeats "the" but never
     * back-to-back. Single words are never touched, whatever they are.
     *
     * <p>Package-private for {@code RepeatedWordFilterTest}.
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
