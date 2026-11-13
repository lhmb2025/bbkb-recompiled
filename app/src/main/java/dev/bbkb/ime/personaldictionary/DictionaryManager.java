package dev.bbkb.ime.personaldictionary;

import android.content.Context;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.personaldictionary.PersonalDictionaryManager;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants;
import dev.bbkb.ime.personaldictionary.util.CompletionListener;
import dev.bbkb.ime.personaldictionary.model.DictionaryWord;
import dev.bbkb.ime.personaldictionary.util.LearnedWordsListener;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil;
import dev.bbkb.ime.personaldictionary.model.PersonalWord;
import dev.bbkb.ime.personaldictionary.model.WordSubstitution;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudAddException;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudDeleteException;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.InitialisationIncompleteException;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyAlreadyDefinedException;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyNotFoundException;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.permissions.PermissionRequestHandler;
import dev.bbkb.ime.core.permissions.PermissionUtils;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DictionaryManager {

    private static final String TAG = "DictionaryManager";

    private static volatile DictionaryManager sInstance;

    private volatile PersonalDictionaryManager personalDictionaryManager;

    private volatile PersonalDictionaryUtil personalDictionaryUtil;

    private final Object lock = new Object();

    private AtomicBoolean initStarted = new AtomicBoolean(false);

    private volatile boolean loaded = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();



    
    public interface LearnedWordsCallback {
        /** @param list the learned words */
        void onLearnedWords(List<String> list);
    }

    
    public enum CapsMode {
        NO_CAPS,
        FIRST_LETTER_CAPS,
        ALL_CAPS
    }

    private DictionaryManager() {
    }

    private static void clearInstance() {
        sInstance = null;
    }

    public static void shutdownInstance() {
        if (sInstance == null) {
            return;
        }
        sInstance.shutdown();
    }

    public static DictionaryManager getInstance() {
        DictionaryManager c0624a = sInstance;
        if (c0624a == null) {
            synchronized (DictionaryManager.class) {
                c0624a = sInstance;
                if (c0624a == null) {
                    c0624a = new DictionaryManager();
                    sInstance = c0624a;
                }
            }
        }
        return c0624a;
    }

    /** @param list the active input locales */
    public void initialiseOrSwitchLanguages(Context context, List<Locale> list) {
        doInitialiseOrSwitchLanguages(context, list, true);
    }

    /**
     * @param list the active input locales
     * @param registerAudObserver true to register the AUD content observer once loaded
     */
    private void doInitialiseOrSwitchLanguages(final Context context, final List<Locale> list, final boolean registerAudObserver) {
        Iterator<Locale> it = list.iterator();
        while (it.hasNext()) {
            if (it.next() == null) {
                NullPointerException nullPointerException = new NullPointerException();
                Logger.errorWithException(TAG, nullPointerException, "Null locale, initialisation or switch task rejected");
                throw nullPointerException;
            }
        }
        Logger.debug(TAG, "doInitialiseOrSwitchLanguages");
        try {
            this.executor.submit(new Runnable() {
                @Override
                public void run() {
                    if (DictionaryManager.this.initStarted.compareAndSet(false, true)) {
                        try {
                            DictionaryManager.this.doInitBasl(context, list, registerAudObserver);
                        } catch (IOException e) {
                            DictionaryManager.this.initStarted.set(false);
                            Logger.error(DictionaryManager.TAG, "IOException in doInitBasl(): " + e.getMessage());
                        }
                        return;
                    }
                    if (registerAudObserver) {
                        DictionaryManager.this.registerAudContentObserver();
                    }
                    DictionaryManager.this.switchInputLanguages((List<Locale>) list);
                }
            });
        } catch (RejectedExecutionException e) {
            Logger.error(TAG, "Initialisation or switch task rejected: " + e.toString());
        }
    }

    /**
     * @param list the active input locales
     * @param registerAudObserver true to register the AUD content observer once loaded
     */
        void doInitBasl(Context context, List<Locale> list, final boolean registerAudObserver) throws IOException {
        Logger.debug(TAG, "Initialising PersonalDictionaryManager and PDU");
        this.personalDictionaryManager = PersonalDictionaryManager.getInstance(NuanceSDKManager.getInstance(), context, context.getFilesDir());
        this.personalDictionaryUtil = this.personalDictionaryManager.getPersonalDictionaryUtil("substitution_macros", "primary-model");
        this.personalDictionaryUtil.load(list, new CompletionListener() {
            @Override
            public void complete(boolean success) {
                Logger.debug(DictionaryManager.TAG, "Load complete: " + success);
                if (success) {
                    DictionaryManager.this.onLoadComplete();
                    if (registerAudObserver) {
                        DictionaryManager.this.registerAudContentObserver();
                        return;
                    }
                    return;
                }
                DictionaryManager.this.initStarted.set(false);
            }
        }, false, false);
    }

    /**
     * Registers the AUD observer, the only route by which a word added outside this app reaches
     * the personal dictionary and the engine's DLM. Logged at INFO/WARN: user builds drop debug.
     */
        void registerAudContentObserver() {
        final PersonalDictionaryUtil pdu = this.personalDictionaryUtil;
        if (!isLoaded() || pdu == null) {
            Logger.warn(TAG, "Not loaded - not registering the AUD content observer");
            return;
        }
        try {
            pdu.registerAudContentObserver();
        } catch (SecurityException | InitialisationIncompleteException e2) {
            Logger.warn(TAG, "registerAudContentObserver() got " + e2.toString());
        } catch (RuntimeException e3) {
            Logger.errorWithException(TAG, e3, "registerAudContentObserver() failed");
        }
    }

    /** @param list the new input locales */
        void switchInputLanguages(List<Locale> list) {
        Logger.debug(TAG, "Switching languages");
        this.personalDictionaryUtil.switchInputLanguages(list, new CompletionListener() {
            @Override
            public void complete(boolean success) {
                Logger.debug(DictionaryManager.TAG, "Language switch completed: " + success);
            }
        });
    }

    public boolean isLoaded() {
        return this.loaded;
    }

    public PersonalDictionaryUtil getPersonalDictionaryUtil() {
        return this.personalDictionaryUtil;
    }

    public Map<String, WordSubstitution> getWordSubstitutions() {
        if (this.personalDictionaryUtil == null) {
            return null;
        }
        return this.personalDictionaryUtil.getWordSubstitutions();
    }

    /**
     * @param key the shortcut key
     * @param word unused
     */
    public void removeWordSubstitution(String key, String word) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return;
        }
        WordSubstitution wordSubstitution = this.personalDictionaryUtil.getWordSubstitutions().get(key);
        if (wordSubstitution != null) {
            this.personalDictionaryUtil.remove(wordSubstitution);
        }
    }

    /**
     * @param oldWord unused
     * @param oldKey the existing key, or empty to add
     * @param newWord the new word
     * @param newKey the new key; nothing happens when empty
     * @param locale the locale, or empty for all locales
     * @param fixedCase true to keep the word's case (no auto caps)
     */
    public boolean updateOrAddWordSubstitution(String oldWord, String oldKey, String newWord, String newKey, String locale, boolean fixedCase) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return false;
        }
        WordSubstitution wordSubstitution = TextUtils.isEmpty(oldKey) ? null : this.personalDictionaryUtil.getWordSubstitutions().get(oldKey);
        if (!TextUtils.isEmpty(newKey)) {
            try {
                WordSubstitution wordSubstitutionCreateUserSubstitution = WordSubstitution.createUserSubstitution(locale.equals("") ? PersonalDictionaryConstants.LOCALE_ALL : locale, newKey, newWord, !fixedCase);
                if (wordSubstitution != null) {
                    this.personalDictionaryUtil.update(wordSubstitution, wordSubstitutionCreateUserSubstitution);
                } else {
                    addWordSubstitution(newWord, newKey, locale, fixedCase);
                }
            } catch (IllegalArgumentException | KeyNotFoundException | AudDeleteException | KeyAlreadyDefinedException | InterruptedException | AudAddException unused) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param word the word (expansion)
     * @param key the key (shortcut)
     * @param locale the locale, or empty for all locales
     * @param fixedCase true to keep the word's case (no auto caps)
     * @return false when not loaded or refused; the one-tap add then falls back to a dialog
     */
    public boolean addWordSubstitution(String word, String key, String locale, boolean fixedCase) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return false;
        }
        if (locale.equals("")) {
            locale = PersonalDictionaryConstants.LOCALE_ALL;
        }
        try {
            this.personalDictionaryUtil.add(WordSubstitution.createUserSubstitution(locale, key, word, !fixedCase));
            return true;
        } catch (IllegalArgumentException | AudAddException | InitialisationIncompleteException | KeyAlreadyDefinedException e) {
            // Logged so an AUD refusing writes is distinguishable from a duplicate.
            Logger.warn(TAG, "addWordSubstitution(" + key + ") refused: " + e);
            return false;
        }
    }

    /**
     * @param key the key
     * @param word the word
     * @param fixedCase true to keep the word's case (no auto caps)
     */
    public boolean syncAddWordSubstitution(String key, String word, boolean fixedCase) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return false;
        }
        try {
            this.personalDictionaryUtil.add(WordSubstitution.createUserSubstitution(PersonalDictionaryConstants.LOCALE_ALL, key, word, !fixedCase));
            return true;
        } catch (IllegalArgumentException | AudAddException | InitialisationIncompleteException | KeyAlreadyDefinedException e4) {
            Logger.errorWithException(TAG, e4, "PersonalDictionaryManager sync new word substitution, IllegalArgumentException");
            return false;
        }
    }

    /** @param isWorkProfile true to include shortcut words */
    public void getLearnedWords(final LearnedWordsCallback callback, boolean isWorkProfile) {
        LearnedWordsListener learnedWordsListener = new LearnedWordsListener() {
            @Override
            public void complete(List<String> list) {
                callback.onLearnedWords(list);
            }
        };
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded - returning empty list");
            learnedWordsListener.complete(new ArrayList(0));
        } else {
            synchronized (this.lock) {
                if (this.personalDictionaryManager != null) {
                    this.personalDictionaryManager.getLearnedWordsUtil("primary-model").getLearnedWords(learnedWordsListener, isWorkProfile);
                }
            }
        }
    }

    public Map<String, PersonalWord> getPersonalDictionary() {
        Map<String, PersonalWord> personalDictionary;
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return null;
        }
        synchronized (this.lock) {
            personalDictionary = this.personalDictionaryUtil.getPersonalDictionary();
        }
        return personalDictionary;
    }

    /** No load-time AUD sync here; call {@link PersonalDictionaryUtil#syncAudDifferencesToBasl()} if one is wanted. */
        void onLoadComplete() {
        synchronized (this.lock) {
            this.loaded = true;
        }
    }

    public void save() {
        synchronized (this.lock) {
            if (!this.loaded) {
                Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded - cannot save");
            } else {
                this.personalDictionaryUtil.save(new CompletionListener() {
                    @Override
                    public void complete(boolean success) {
                        Logger.debug(DictionaryManager.TAG, "Save complete: " + success);
                    }
                });
            }
        }
    }

    /**
     * How long {@link #shutdown} waits for the final save. Only matters when the save is stuck
     * behind the AUD sync lock; a save that outlives it still finishes on its own thread.
     */
    static final long FINAL_SAVE_WAIT_MS = 500L;

    public void shutdown() {
        // Write unsaved changes first: a save queued by onDestroy runs on the dictionary's
        // scope, which the teardown below cancels.
        final PersonalDictionaryUtil pdu;
        synchronized (this.lock) {
            pdu = this.loaded ? this.personalDictionaryUtil : null;
        }
        flushUnsaved(pdu);
        synchronized (this.lock) {
            this.loaded = false;
            this.executor.shutdown();
            if (this.personalDictionaryManager != null) {
                this.personalDictionaryManager.shutDown(true);
                this.personalDictionaryManager = null;
            }
            clearInstance();
        }
    }


    /**
     * One process-wide thread for the final save. It never enters the engine: doSave only writes
     * files, and engine calls are posted to the engine thread (see EngineThreads).
     */
    private static final ExecutorService FINAL_SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DictionaryFinalSave");
        t.setDaemon(true);
        return t;
    });

    /** Writes {@code pdu}'s unsaved changes off-thread, waiting at most {@link #FINAL_SAVE_WAIT_MS}. */
    private static void flushUnsaved(final PersonalDictionaryUtil pdu) {
        if (pdu == null || !pdu.getAreDataDirty()) {
            return;
        }
        final FutureTask<Void> task = new FutureTask<>(() -> {
            pdu.doSave();
            return null;
        });
        FINAL_SAVE_EXECUTOR.execute(task);
        try {
            task.get(FINAL_SAVE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Logger.warn(TAG, "Final save still running after " + FINAL_SAVE_WAIT_MS + " ms; not waiting");
        } catch (ExecutionException e) {
            Logger.errorWithException(TAG, e.getCause() != null ? e.getCause() : e, "Final save failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    PersonalDictionaryManager getBasl() {
        return this.personalDictionaryManager;
    }

    /** Attaches a loaded {@link PersonalDictionaryUtil} so a test can run the add chain without the SDK. */
    @VisibleForTesting
    void attachLoadedUtilForTest(PersonalDictionaryUtil util) {
        this.personalDictionaryUtil = util;
        this.initStarted.set(true);
        onLoadComplete();
    }

    @VisibleForTesting
    void detachForTest() {
        synchronized (this.lock) {
            this.loaded = false;
        }
        this.personalDictionaryUtil = null;
        this.initStarted.set(false);
        clearInstance();
    }

    /** @param input the typed input */
    public String getWordSubstitutionForInput(String input, CapsMode mode, Locale locale) {
        PersonalDictionaryUtil.CapsMode capsMode;
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded - returning verbatim!");
            return input;
        }
        switch (mode) {
            case NO_CAPS:
                capsMode = PersonalDictionaryUtil.CapsMode.NO_CAPS;
                break;
            case FIRST_LETTER_CAPS:
                capsMode = PersonalDictionaryUtil.CapsMode.FIRST_LETTER_CAPS;
                break;
            case ALL_CAPS:
                capsMode = PersonalDictionaryUtil.CapsMode.ALL_CAPS;
                break;
            default:
                capsMode = null;
                break;
        }
        return this.personalDictionaryUtil.getWordSubstitutionForInput(input, capsMode, locale);
    }


    public void ensureEnglishCapitalISubstitution(Locale locale) {
        if (locale.getLanguage().equals("en")) {
            if (!isLoaded()) {
                Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
                return;
            }
            synchronized (this.lock) {
                if (!this.personalDictionaryUtil.getWordSubstitutions().containsKey("i")) {
                    addWordSubstitution("I", "i", locale.toString(), true);
                }
            }
        }
    }

    /** @param entry a {@link DictionaryWord} */
    public DictionaryWord asDictionaryWord(Object entry) {
        return (DictionaryWord) entry;
    }

    /** @param entry a {@link DictionaryWord} */
    public String getWord(Object entry) {
        return asDictionaryWord(entry).getWord();
    }

    /** @param entry a {@link DictionaryWord}; null is returned unless it is a WordSubstitution */
    public String getSubstitutionKey(Object entry) {
        DictionaryWord dictionaryWordM3844a = asDictionaryWord(entry);
        if (dictionaryWordM3844a instanceof WordSubstitution) {
            return ((WordSubstitution) dictionaryWordM3844a).getKey();
        }
        return null;
    }

    /**
     * @param locale the locale string, or empty for entries stored for all locales
     * @param wantSubstitutions true for word substitutions, false for personal words
     */
    public List<DictionaryWord> getWordsForLocale(String locale, boolean wantSubstitutions) {
        Map personalDictionary;
        PersonalDictionaryUtil personalDictionaryUtilM3859d = getPersonalDictionaryUtil();
        ArrayList arrayList = new ArrayList();
        if (personalDictionaryUtilM3859d != null) {
            if (wantSubstitutions) {
                personalDictionary = personalDictionaryUtilM3859d.getWordSubstitutions();
            } else {
                personalDictionary = personalDictionaryUtilM3859d.getPersonalDictionary();
            }
            for (Object obj : personalDictionary.values()) {
                DictionaryWord dictionaryWord = (DictionaryWord) obj;
                if (dictionaryWord.getLocale().toString().equals(locale) || (locale.equals("") && dictionaryWord.getLocale().equals(PersonalDictionaryConstants.LOCALE_ALL))) {
                    arrayList.add(dictionaryWord);
                }
            }
        }
        return arrayList;
    }
}
