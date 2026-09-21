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
            this.nuanceSdk.selectionListSelectWord(wordInfo.selectionListIndex, true, wordInfo.spell);
            return;
        }
        // Stale (or unstamped) index. Learn by CONTENT instead: addWord records the same word
        // without indexing a list that has moved on. Losing the index-based path costs the
        // engine's end-of-word transition, which the post-commit clear already performs.
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "IME:learn:staleSelectionList"
                + " word=\"" + wordInfo.word + "\""
                + " stamped=" + wordInfo.selectionListGeneration
                + " now=" + generationNow
                + " -> addWord fallback (EB-3)");
        }
        if (wordInfo.word != null && !wordInfo.word.isEmpty()) {
            this.nuanceSdk.addWord(wordInfo.word);
        }
    }

    // There is deliberately no save() here. The engine learner has no store of its own to flush:
    // everything it learns goes into the native dynamic model, which the engine writes through
    // attachDLMFile/detachDLMFile — there is no "save the DLM" entry point on NuanceSDK at all.
    // save() used to call DictionaryManager.getInstance().save(), i.e. flushing the *engine*
    // learner wrote the *BASL personal dictionary*: two unrelated stores, one of which this class
    // knows nothing about. That flush now lives on DynamicLearningManager, which is where the
    // broadcast that asks for it arrives (§5.5).
}
