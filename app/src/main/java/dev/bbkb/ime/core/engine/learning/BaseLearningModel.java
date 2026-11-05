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
     * The engine to learn into when one was injected (the tests); null in production, where each
     * learn op resolves the primary engine on the engine thread. Resolving it here, at
     * construction, joined the engine start on the main thread (Wave 3 Phase A forbids that).
     */
    private volatile NuanceSDK nuanceSdk;

    /** The engine for an op, on the engine thread; null when it failed to load (FIX-D2). */
    private NuanceSDK engineForOp(String site) {
        NuanceSDK injected = this.nuanceSdk;
        return injected != null ? injected : NuanceSDKManager.sdkOrWarn(site);
    }

    /** {@code context} is not read. (The files dir it once resolved is created by NuanceSDK's own ctor.) */
    BaseLearningModel(Context context) {
    }

    void deactivate() {
        if (this.active) {
            this.active = false;
        }
    }

    void learnWord(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        learnWord(suggestedWordInfoVar, true);
    }

    /**
     * @param learnByContent whether a stale index may fall back to {@code addWord}. That fallback
     *                       is learning and nothing else (it does not end the word), so a session
     *                       that may not learn passes {@code false}: a forced CJK commit in such a
     *                       session still gets the index select its engine transition needs, but
     *                       never an {@code addWord}.
     */
    void learnWord(final SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, final boolean learnByContent) {
        // Wave 3 Phase A: the whole learn (select by index, the rebuild-and-retry, the content
        // fallback, the clear) is ONE op on the engine thread, posted at commit: it runs after
        // every request posted before the commit and before the next word's first feed, so the
        // word is ended in the engine before the next prediction, and nothing can build a list
        // between its steps. Results are only logged, inside the op.
        EngineThreads.post(() -> {
            NuanceSDK sdk = engineForOp("learnWord");
            if (sdk != null) {
                learnWordOnEngine(sdk, suggestedWordInfoVar, learnByContent);
            }
        });
    }

    /** {@link #learnWord(SuggestedWords.SuggestedWordInfo, boolean)}'s body; engine thread. */
    private void learnWordOnEngine(NuanceSDK engine, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, boolean learnByContent) {
        WordInfo wordInfo = suggestedWordInfoVar.nuanceWordInfo;
        if (wordInfo == null) {
            // Audit EB-6: a commit must always end the word in the engine. Without engine word
            // info there is nothing to select or learn, so just clear, as the no-learner path does.
            engine.clear();
            return;
        }
        // Audit EB-3: selectionListSelectWord takes a RAW INDEX into whatever selection list the
        // engine holds RIGHT NOW. A separator commit neither cancels nor drains an in-flight
        // suggestion request, so the worker can rebuild the list between the moment this WordInfo
        // was assembled and this call — and then the index points into a different list, learning
        // a word the user never typed and applying the end-of-word engine transition to the wrong
        // candidate. Only trust the index while the list still has the identity it was read from.
        long generationNow = NuanceSDK.getSelectionListGeneration();
        if (wordInfo.selectionListGeneration >= 0L && wordInfo.selectionListGeneration == generationNow) {
            boolean ok = engine.selectionListSelectWord(wordInfo.selectionListIndex, true, wordInfo.spell);
            if (!ok) {
                // The engine refused the pick (the shim logs "Failed to select word"): its
                // selection list is no longer current at commit time, because the main thread
                // feeds every typed character into the engine (ComposingTextTracker) while the
                // worker clears, replays and builds the list asynchronously, so the list the
                // engine holds when the separator lands has been invalidated by a later feed.
                // ET9AWSelLstSelWord then returns NEED_SELLIST_BUILD and the word is never
                // learned: on the emulator (2026-10-17) a novel word typed twelve times was
                // never predicted. Rebuild and retry when the rebuilt entry is still this word;
                // otherwise fall through to learning by content below.
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
        // Stale (or unstamped) index, or a pick the engine refused twice. Learn by CONTENT
        // instead: addWord records the same word without indexing a list that has moved on.
        // Losing the index-based path costs the engine's end-of-word transition, which the
        // post-commit clear already performs.
        //
        // Only a word the engine does NOT already know, though (2026-10-12). addWord is how a
        // NEW word enters the learned model; for a word the dictionary already holds it only adds
        // a learned entry that boosts that word over the typed one. Every swipe result lands here
        // (the bridge clears the engine after taking it, so a gesture word never has a live
        // index), which made each misread swipe a permanent boost — "bout" over the typed
        // "about". A swiped dictionary word is therefore no longer taught; a swiped word the
        // dictionary lacks still is, and a typed word still learns through the index path above.
        if (learnByContent && wordInfo.word != null && !wordInfo.word.isEmpty()
                && !engine.isSpellingCorrect(wordInfo.word)) {
            boolean added = engine.addWord(wordInfo.word);
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:learn:addWord word=\"" + wordInfo.word + "\" -> " + added);
        }
    }

    /**
     * Learn {@code word} by content when the engine does not know it, then end the word. One
     * posted engine op, like {@link #learnWord(SuggestedWords.SuggestedWordInfo, boolean)}.
     */
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

    // There is deliberately no save() here. The engine learner has no store of its own to flush:
    // everything it learns goes into the native dynamic model, which the engine writes through
    // attachDLMFile/detachDLMFile — there is no "save the DLM" entry point on NuanceSDK at all.
    // save() used to call DictionaryManager.getInstance().save(), i.e. flushing the *engine*
    // learner wrote the *BASL personal dictionary*: two unrelated stores, one of which this class
    // knows nothing about. That flush now lives on DynamicLearningManager, which is where the
    // broadcast that asks for it arrives (§5.5).
}
