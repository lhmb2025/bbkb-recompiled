package dev.bbkb.ime.core.engine.learning;

import android.content.Context;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;

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

    private NuanceSDK nuanceSdk = NuanceSDKManager.getInstance();

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
    void learnWord(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, boolean learnByContent) {
        if (this.nuanceSdk == null) {
            // FIX-D2: the engine had failed to load when this learner was built. Nothing to learn
            // into and no word to end.
            return;
        }
        WordInfo wordInfo = suggestedWordInfoVar.nuanceWordInfo;
        if (wordInfo == null) {
            // Audit EB-6: a commit must always end the word in the engine. Without engine word
            // info there is nothing to select or learn, so just clear, as the no-learner path does.
            this.nuanceSdk.clear();
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
            boolean ok = this.nuanceSdk.selectionListSelectWord(wordInfo.selectionListIndex, true, wordInfo.spell);
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
                this.nuanceSdk.buildSelectionList();
                WordInfo now = this.nuanceSdk.getSelectionListWord(wordInfo.selectionListIndex);
                if (now != null && now.word != null && wordInfo.word != null
                        && now.word.equalsIgnoreCase(wordInfo.word)) {
                    ok = this.nuanceSdk.selectionListSelectWord(wordInfo.selectionListIndex, true, wordInfo.spell);
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
                && !this.nuanceSdk.isSpellingCorrect(wordInfo.word)) {
            boolean added = this.nuanceSdk.addWord(wordInfo.word);
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:learn:addWord word=\"" + wordInfo.word + "\" -> " + added);
        }
    }

    /** Learn {@code word} by content when the engine does not know it, then end the word. */
    void learnTypedWord(String word) {
        if (this.nuanceSdk == null) {
            return;
        }
        if (!this.nuanceSdk.isSpellingCorrect(word)) {
            boolean added = this.nuanceSdk.addWord(word);
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "IME:learn:typedWord word=\"" + word + "\" -> " + added);
        }
        this.nuanceSdk.clear();
    }

    // There is deliberately no save() here. The engine learner has no store of its own to flush:
    // everything it learns goes into the native dynamic model, which the engine writes through
    // attachDLMFile/detachDLMFile — there is no "save the DLM" entry point on NuanceSDK at all.
    // save() used to call DictionaryManager.getInstance().save(), i.e. flushing the *engine*
    // learner wrote the *BASL personal dictionary*: two unrelated stores, one of which this class
    // knows nothing about. That flush now lives on DynamicLearningManager, which is where the
    // broadcast that asks for it arrives (§5.5).
}
