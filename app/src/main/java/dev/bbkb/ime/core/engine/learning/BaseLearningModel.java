package dev.bbkb.ime.core.engine.learning;

import android.content.Context;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;

import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import com.blackberry.nuanceshim.NuanceSDK;
import com.blackberry.nuanceshim.WordInfo;

/**
 * The engine learner. Kept as a base class with {@link PersonalLearner} as its only subclass: the
 * subclass's simple name is the value of the {@code IME:learn:activeLearner=} PIPELINE debug hook,
 * and {@code LearnStaleSelectionListTest} subclasses this type directly.
 */
public abstract class BaseLearningModel {

    boolean active;

    /**
     * The engine injected by tests; null in production, where each op resolves the engine on the
     * engine thread (resolving it at construction would join the engine start on main).
     */
    private volatile NuanceSDK nuanceSdk;

    /** The engine for an op, on the engine thread; null when it failed to load. */
    private NuanceSDK engineForOp(String site) {
        NuanceSDK injected = this.nuanceSdk;
        return injected != null ? injected : NuanceSDKManager.sdkOrWarn(site);
    }

    /** Empty: {@code context} is not read (NuanceSDK creates the files dir itself). */
    BaseLearningModel(Context context) {
    }

    void deactivate() {
        if (this.active) {
            this.active = false;
        }
    }

    void learnWord(SuggestedWords.SuggestedWordInfo suggestedWordInfo) {
        learnWord(suggestedWordInfo, true);
    }

    /**
     * @param learnByContent whether a stale index may fall back to {@code addWord}; false for a
     *                       session that may not learn (a forced CJK commit still gets its index select).
     */
    void learnWord(final SuggestedWords.SuggestedWordInfo suggestedWordInfo, final boolean learnByContent) {
        // One engine-thread op, posted at commit: it runs before the next word's first feed, and
        // nothing can build a list between its steps.
        EngineThreads.post(() -> {
            NuanceSDK sdk = engineForOp("learnWord");
            if (sdk != null) {
                learnWordOnEngine(sdk, suggestedWordInfo, learnByContent);
            }
        });
    }

    /** {@link #learnWord(SuggestedWords.SuggestedWordInfo, boolean)}'s body; engine thread. */
    private void learnWordOnEngine(NuanceSDK engine, SuggestedWords.SuggestedWordInfo suggestedWordInfo, boolean learnByContent) {
        WordInfo wordInfo = suggestedWordInfo.nuanceWordInfo;
        if (wordInfo == null) {
            // A commit must always end the word in the engine; with nothing to select, just clear.
            engine.clear();
            return;
        }
        // selectionListSelectWord takes a raw index into the engine's current list, and an
        // in-flight suggestion request may have rebuilt it since this WordInfo was read. Trust the
        // index only while the list generation is unchanged.
        long generationNow = NuanceSDK.getSelectionListGeneration();
        if (wordInfo.selectionListGeneration >= 0L && wordInfo.selectionListGeneration == generationNow) {
            boolean ok = engine.selectionListSelectWord(wordInfo.selectionListIndex, true, wordInfo.spell);
            if (!ok) {
                // ET9AWSelLstSelWord returned NEED_SELLIST_BUILD: a later feed invalidated the list.
                // Rebuild and retry when the rebuilt entry is still this word; otherwise fall
                // through to learning by content.
                engine.buildSelectionList();
                WordInfo now = engine.getSelectionListWord(wordInfo.selectionListIndex);
                if (now != null && now.word != null && wordInfo.word != null
                        && now.word.equalsIgnoreCase(wordInfo.word)) {
                    ok = engine.selectionListSelectWord(wordInfo.selectionListIndex, true, wordInfo.spell);
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d("PIPELINE", "IME:learn:indexPath word=\"" + wordInfo.word + "\" index=" + wordInfo.selectionListIndex
                    + " learnByContent=" + learnByContent + " -> " + ok);
            }
            if (ok) {
                return;
            }
        } else if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:learn:staleSelectionList"
                + " word=\"" + wordInfo.word + "\""
                + " stamped=" + wordInfo.selectionListGeneration
                + " now=" + generationNow
                + " -> addWord fallback (EB-3)");
        }
        // Stale index or a twice-refused pick: learn by content (the post-commit clear ends the word).
        // Only unknown words: for a dictionary word addWord adds a boost, and every swipe result
        // lands here, so each misread swipe would become a permanent boost.
        if (learnByContent && wordInfo.word != null && !wordInfo.word.isEmpty()
                && !engine.isSpellingCorrect(wordInfo.word)) {
            boolean added = engine.addWord(wordInfo.word);
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:learn:addWord word=\"" + wordInfo.word + "\" -> " + added);
        }
    }

    /** Learns {@code word} by content when the engine does not know it, then ends the word; one engine op. */
    void learnTypedWord(final String word) {
        EngineThreads.post(() -> {
            NuanceSDK engine = engineForOp("learnTypedWord");
            if (engine == null) {
                return;
            }
            if (!engine.isSpellingCorrect(word)) {
                boolean added = engine.addWord(word);
                if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:learn:typedWord word=\"" + word + "\" -> " + added);
            }
            engine.clear();
        });
    }

    // No save(): the engine writes the native dynamic model through attachDLMFile/detachDLMFile,
    // and NuanceSDK has no save entry point.
}
