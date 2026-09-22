package dev.bbkb.ime.core.engine.learning;

import android.content.Context;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.personaldictionary.DictionaryManager;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.contacts.ContactsDataProvider;
import dev.bbkb.ime.core.shared.ThreadUtils;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.concurrent.Executor;


public class DynamicLearningManager implements NuanceSDK.ManagedModeCallback {

    private final BaseLearningModel personalLearner;

    private boolean dynamicLearningEnabled = true;

    /**
     * The executor deferred learning work runs on. Held in a field rather than looked up per call
     * so a test can substitute a direct executor; production always gets the process-wide
     * single-thread executor, so ordering against the init task is unchanged.
     */
    Executor backgroundExecutor = ThreadUtils.getBackgroundExecutor("dynamic_learning_executor");

    /**
     * FIX-D2: NuanceSDKManager answers null when the engine failed to load (it swallows the load
     * error). Every engine call here goes through this, so a failed load degrades to "no learning"
     * instead of an NPE in IME onCreate / on every commit. With an engine present nothing changes.
     */
    private static NuanceSDK engineOrNull() {
        return NuanceSDKManager.getInstance();
    }

    public DynamicLearningManager(Context context) {
        // No engine, no managed-mode callback: the learner is then never activated, so every
        // commit takes the clear-only path (itself a no-op without an engine).
        NuanceSDK engine = engineOrNull();
        if (engine != null) engine.setCallbackHandler(this);
        this.personalLearner = new PersonalLearner(context);
    }

    public void setDynamicLearningEnabled(boolean z) {
        this.dynamicLearningEnabled = z;
        NuanceSDK engine = engineOrNull();
        if (engine != null) engine.setIsExplicitLearning(!z);
    }

    /** Ends the current word in the engine; nothing to end when the engine failed to load. */
    private static void clearEngine() {
        NuanceSDK engine = engineOrNull();
        if (engine != null) engine.clear();
    }

    private BaseLearningModel getActiveLearner() {
        BaseLearningModel learner = this.personalLearner;
        if (learner == null || !learner.active) {
            return null;
        }
        return learner;
    }

    /**
     * Flushes the personal dictionary (the BASL store) to disk. Called by
     * {@code LearningModelSaveReceiver} and by {@link #destroy()}.
     *
     * <p>History, because the name and the gate were both wrong. UT-1: this was
     * {@code saveWorkLearner()} and guarded on {@code workProfileState == 2 && workLearner != null}.
     * Work-profile learning was removed — {@code workLearner} was never assigned and
     * {@code workProfileState} never left 0 — so the body was unreachable and the broadcast that
     * logs "Writing learning model to disk" wrote nothing at all. UT-1's fix made it reachable by
     * saving whichever learner is active, which is the personal one.
     *
     * <p>§5.5 finishes the job. What that reached was {@code BaseLearningModel.save()} →
     * {@code DictionaryManager.save()} — flushing the *engine* learner wrote the *BASL personal
     * dictionary*, two unrelated stores. The engine learner has nothing to flush (its state is in
     * the native dynamic model, which NuanceSDK exposes no save entry point for), so the indirection
     * was pure misdirection: the only thing this ever did was save the personal dictionary.
     *
     * <p>It now says so, and the engine-learner gate is gone with it. That gate meant a user's
     * word substitutions and personal words were written only if the engine's DLM happened to have
     * loaded and activated a learner — an unrelated precondition that could silently drop edits.
     * The call is safe to make unconditionally: {@code DictionaryManager.save()} no-ops when the
     * store is not loaded, and {@code PersonalDictionaryUtil} skips each file whose dirty flag is
     * clear, so a redundant flush costs a log line.
     */
    public void savePersonalDictionary() {
        this.backgroundExecutor.execute(this::flushPersonalDictionary);
    }

    /** The body of {@link #savePersonalDictionary()}, on whatever thread calls it. */
    void flushPersonalDictionary() {
        DictionaryManager.getInstance().save();
    }

    /** Unregisters, queues the personal-dictionary flush, then deactivates the learner (pinned by DynamicLearningManagerTest). */
    public void destroy() {
        NuanceSDK engine = engineOrNull();
        if (engine != null) engine.setCallbackHandler(null);
        savePersonalDictionary();
        this.personalLearner.deactivate();
    }

    public void learn(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, boolean z) {
        if (suggestedWordInfoVar == null || (!this.dynamicLearningEnabled && !z)) {
            clearEngine();
        } else {
            learnWithActiveLearner(suggestedWordInfoVar);
        }
    }

    public void learn(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        learn(suggestedWordInfoVar, false);
    }

    public void addContactsWords(final String[] strArr, final ContactsDataProvider.ContactsType bVar) {
        if (this.dynamicLearningEnabled) {
            if (strArr.length <= 500) {
                addContactsWordsInternal(strArr, bVar);
            } else {
                this.backgroundExecutor.execute(() -> addContactsWordsInternal(strArr, bVar));
            }
        }
    }

    private void learnWithActiveLearner(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        BaseLearningModel abstractC0928aM6055e = getActiveLearner();
        if (BuildConfig.DEBUG) {
            // Open question (2026-08): personalLearner.active is set ONLY from
            // onManagedModeChanged(false), which fires from loadDLMFromFile(). If the DLM loads
            // before DynamicLearningManager registers its callback handler, no learner is ever
            // active and every commit silently skips the engine's SelLstSelWord transition.
            android.util.Log.d("PIPELINE", "IME:learn:activeLearner="
                + (abstractC0928aM6055e == null ? "NONE (commit does not reach the engine)"
                                                : abstractC0928aM6055e.getClass().getSimpleName()));
        }
        if (abstractC0928aM6055e != null) {
            abstractC0928aM6055e.learnWord(suggestedWordInfoVar);
            return;
        }
        // Audit EB-6: with no active learner this returned having done NOTHING — neither
        // selectionListSelectWord nor clear() — silently violating the commit-transition
        // invariant that CommitController documents at :312-318 ("always transition the engine
        // out of the just-typed word"). The engine then still holds the committed word, so the
        // next prediction request can score against it. Mirror the null-info branch of learn()
        // above and end the word explicitly.
        clearEngine();
    }

        void addContactsWordsInternal(String[] strArr, ContactsDataProvider.ContactsType bVar) {
        NuanceSDK engine = engineOrNull();
        if (engine == null) {
            return; // FIX-D2: no engine to learn contacts into.
        }
        if (bVar == ContactsDataProvider.ContactsType.ALL) {
            engine.addContactsWords(strArr);
        } else {
            engine.addContactsWords(strArr, bVar == ContactsDataProvider.ContactsType.WORK);
        }
    }

    @Override // com.blackberry.nuanceshim.NuanceSDK.ManagedModeCallback
    public void onManagedModeChanged(boolean z) {
        // UT-1/UT-12: the managed-mode branch used to activate a work-profile learner and record
        // its DLM path in a write-only static set. Work profile learning was removed, the learner
        // was never constructed, so that branch could not run and the set was never read. Managed
        // mode simply does not activate a learner; unmanaged mode activates the personal one.
        if (z) {
            return;
        }
        this.personalLearner.active = true;
    }
}
