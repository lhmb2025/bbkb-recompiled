package dev.bbkb.ime.core.engine.learning;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.personaldictionary.DictionaryManager;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.contacts.ContactsDataProvider;
import dev.bbkb.ime.core.shared.ThreadUtils;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;


public class DynamicLearningManager implements NuanceSDK.ManagedModeCallback {

    private final BaseLearningModel personalLearner;

    private boolean dynamicLearningEnabled = true;

    /** The global {@code dynamic_learning} preference without the per-field gates; read by contacts import. */
    private boolean learningPreferenceEnabled = true;

    /**
     * The field set {@code IME_FLAG_NO_PERSONALIZED_LEARNING}. Already folded into
     * {@link #dynamicLearningEnabled}; kept for boards that store personal data (emoji recents, clipboard).
     */
    private boolean noPersonalizedLearning = false;

    /** A field so tests can substitute a direct executor; production uses the shared single-thread one. */
    Executor backgroundExecutor = ThreadUtils.getBackgroundExecutor("dynamic_learning_executor");

    /**
     * Null when the engine failed to load, so a failed load degrades to no learning instead of an
     * NPE. Call only on the engine or loader thread ({@link EngineThreads}): it may join the engine's start.
     */
    private static NuanceSDK engineOrNull() {
        return NuanceSDKManager.getInstance();
    }

    public DynamicLearningManager(Context context) {
        // The learner first: setCallbackHandler answers at once when the learned model is already
        // attached, and onManagedModeChanged then needs a non-null learner.
        this.personalLearner = new PersonalLearner(context);
        // Posted: this runs on main, possibly before the engine has started, and only the engine
        // thread may wait for that start.
        EngineThreads.post(() -> {
            NuanceSDK engine = engineOrNull();
            if (engine != null) engine.setCallbackHandler(this);
        });
    }

    /** @param z true if this session may learn (preference, field type and incognito folded in) */
    public void setDynamicLearningEnabled(boolean z) {
        this.dynamicLearningEnabled = z;
        EngineThreads.post(() -> {
            NuanceSDK engine = engineOrNull();
            if (engine != null) engine.setIsExplicitLearning(!z);
            if (BuildConfig.DEBUG) android.util.Log.d("PIPELINE", "IME:learn:setDynamicLearningEnabled=" + z + " engine=" + (engine != null));
        });
    }

    /** Whether this session may learn: the preference is on, the field type allows it, and the field is not incognito. */
    public boolean isLearningAllowed() {
        return this.dynamicLearningEnabled;
    }

    /** @param z the global learning preference */
    public void setLearningPreferenceEnabled(boolean z) {
        this.learningPreferenceEnabled = z;
    }

    /** @param z true if the focused field asked for no personalized learning */
    public void setNoPersonalizedLearning(boolean z) {
        this.noPersonalizedLearning = z;
    }

    /** Whether the focused field asked for no personalized learning (incognito). */
    public boolean isNoPersonalizedLearning() {
        return this.noPersonalizedLearning;
    }

    /** Ends the current word in the engine; nothing to end when the engine failed to load. */
    private static void clearEngine() {
        EngineThreads.post(() -> {
            NuanceSDK engine = engineOrNull();
            if (engine != null) engine.clear();
        });
    }

    private BaseLearningModel getActiveLearner() {
        BaseLearningModel learner = this.personalLearner;
        if (learner == null || !learner.active) {
            return null;
        }
        return learner;
    }

    /**
     * Flushes the personal dictionary (the BASL store) to disk; the engine learner has no save entry
     * point. Safe unconditionally: an unloaded store no-ops and clean files are skipped.
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
        // Posted after the constructor's registration (FIFO); peek, because a teardown must not start the engine.
        EngineThreads.post(() -> {
            NuanceSDK engine = NuanceSDKManager.peekInstance();
            if (engine != null) engine.setCallbackHandler(null);
        });
        savePersonalDictionary();
        this.personalLearner.deactivate();
    }

    /**
     * @param z force: true for a Japanese or partial Chinese pick, where the engine's selection is
     *          the transition that keeps the composition alive. Selects by index even when the
     *          session may not learn, but never learns by content.
     */
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

    /**
     * A typed word committed with no candidate list (suggestions and auto-correction both off).
     * Learned by content when allowed; either way the word is ended in the engine.
     */
    public void learnTypedWord(String word) {
        BaseLearningModel learner = this.dynamicLearningEnabled ? getActiveLearner() : null;
        if (learner != null && word != null && !word.isEmpty()) {
            learner.learnTypedWord(word);
            return;
        }
        clearEngine();
    }

    /**
     * @param strArr contact name words
     * @param bVar which contacts (all, personal or work)
     */
    public void addContactsWords(final String[] strArr, final ContactsDataProvider.ContactsType bVar) {
        // The global preference only: the focused field (password, incognito) has no say in an address book import.
        if (this.learningPreferenceEnabled) {
            if (strArr.length <= 500) {
                addContactsWordsOnLoader(strArr, bVar);
            } else {
                this.backgroundExecutor.execute(() -> addContactsWordsOnLoader(strArr, bVar));
            }
        }
    }

    /**
     * Runs the batch (a long hold) on the loader thread. A background caller waits so its
     * bookkeeping follows the add; the main thread never waits.
     *
     * @param strArr contact name words
     * @param bVar which contacts
     */
    private void addContactsWordsOnLoader(String[] strArr, ContactsDataProvider.ContactsType bVar) {
        java.util.concurrent.Future<?> done = EngineThreads.load(() -> addContactsWordsInternal(strArr, bVar));
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return;
        }
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Log.w("DynamicLearningManager", "adding contacts to the engine failed", e.getCause());
        }
    }

    private void learnWithActiveLearner(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        BaseLearningModel abstractC0928aM6055e = getActiveLearner();
        if (BuildConfig.DEBUG) {
            // active is set only from onManagedModeChanged(false); if that never fires, every commit
            // skips the engine's SelLstSelWord transition.
            android.util.Log.d("PIPELINE", "IME:learn:activeLearner="
                + (abstractC0928aM6055e == null ? "NONE (commit does not reach the engine)"
                                                : abstractC0928aM6055e.getClass().getSimpleName()));
        }
        if (abstractC0928aM6055e != null) {
            abstractC0928aM6055e.learnWord(suggestedWordInfoVar, this.dynamicLearningEnabled);
            return;
        }
        // No learner: still end the word, or the next prediction scores against the committed word.
        clearEngine();
    }

    /**
     * Loader thread (see {@link #addContactsWordsOnLoader}).
     *
     * @param strArr contact name words
     * @param bVar which contacts
     */
    void addContactsWordsInternal(String[] strArr, ContactsDataProvider.ContactsType bVar) {
        NuanceSDK engine = engineOrNull();
        if (engine == null) {
            return; // No engine to learn contacts into.
        }
        if (bVar == ContactsDataProvider.ContactsType.ALL) {
            engine.addContactsWords(strArr);
        } else {
            engine.addContactsWords(strArr, bVar == ContactsDataProvider.ContactsType.WORK);
        }
    }

    @Override // com.blackberry.nuanceshim.NuanceSDK.ManagedModeCallback
    /** @param z true for managed (work profile) mode, which activates no learner */
    public void onManagedModeChanged(boolean z) {
        if (z) {
            return;
        }
        this.personalLearner.active = true;
    }
}
