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
     * @param z true to register the AUD content observer once loaded
     */
    private void doInitialiseOrSwitchLanguages(final Context context, final List<Locale> list, final boolean z) {
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
                            DictionaryManager.this.doInitBasl(context, list, z);
                        } catch (IOException e) {
                            DictionaryManager.this.initStarted.set(false);
                            Logger.error(DictionaryManager.TAG, "IOException in doInitBasl(): " + e.getMessage());
                        }
                        return;
                    }
                    if (z) {
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
     * @param z true to register the AUD content observer once loaded
     */
        void doInitBasl(Context context, List<Locale> list, final boolean z) throws IOException {
        Logger.debug(TAG, "Initialising PersonalDictionaryManager and PDU");
        this.personalDictionaryManager = PersonalDictionaryManager.getInstance(NuanceSDKManager.getInstance(), context, context.getFilesDir());
        this.personalDictionaryUtil = this.personalDictionaryManager.getPersonalDictionaryUtil("substitution_macros", "primary-model");
        this.personalDictionaryUtil.load(list, new CompletionListener() {
            @Override
            public void complete(boolean z2) {
                Logger.debug(DictionaryManager.TAG, "Load complete: " + z2);
                if (z2) {
                    DictionaryManager.this.onLoadComplete();
                    if (z) {
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
            public void complete(boolean z) {
                Logger.debug(DictionaryManager.TAG, "Language switch completed: " + z);
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
     * @param str the shortcut key
     * @param str2 unused
     */
    public void removeWordSubstitution(String str, String str2) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return;
        }
        WordSubstitution wordSubstitution = this.personalDictionaryUtil.getWordSubstitutions().get(str);
        if (wordSubstitution != null) {
            this.personalDictionaryUtil.remove(wordSubstitution);
        }
    }

    /**
     * @param str unused
     * @param str2 the existing key, or empty to add
     * @param str3 the new word
     * @param str4 the new key; nothing happens when empty
     * @param str5 the locale, or empty for all locales
     * @param z true to keep the word's case (no auto caps)
     */
    public boolean updateOrAddWordSubstitution(String str, String str2, String str3, String str4, String str5, boolean z) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return false;
        }
        WordSubstitution wordSubstitution = TextUtils.isEmpty(str2) ? null : this.personalDictionaryUtil.getWordSubstitutions().get(str2);
        if (!TextUtils.isEmpty(str4)) {
            try {
                WordSubstitution wordSubstitutionCreateUserSubstitution = WordSubstitution.createUserSubstitution(str5.equals("") ? PersonalDictionaryConstants.LOCALE_ALL : str5, str4, str3, !z);
                if (wordSubstitution != null) {
                    this.personalDictionaryUtil.update(wordSubstitution, wordSubstitutionCreateUserSubstitution);
                } else {
                    addWordSubstitution(str3, str4, str5, z);
                }
            } catch (IllegalArgumentException | KeyNotFoundException | AudDeleteException | KeyAlreadyDefinedException | InterruptedException | AudAddException unused) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param str the word (expansion)
     * @param str2 the key (shortcut)
     * @param str3 the locale, or empty for all locales
     * @param z true to keep the word's case (no auto caps)
     * @return false when not loaded or refused; the one-tap add then falls back to a dialog
     */
    public boolean addWordSubstitution(String str, String str2, String str3, boolean z) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return false;
        }
        if (str3.equals("")) {
            str3 = PersonalDictionaryConstants.LOCALE_ALL;
        }
        try {
            this.personalDictionaryUtil.add(WordSubstitution.createUserSubstitution(str3, str2, str, !z));
            return true;
        } catch (IllegalArgumentException | AudAddException | InitialisationIncompleteException | KeyAlreadyDefinedException e) {
            // Logged so an AUD refusing writes is distinguishable from a duplicate.
            Logger.warn(TAG, "addWordSubstitution(" + str2 + ") refused: " + e);
            return false;
        }
    }

    /**
     * @param str the key
     * @param str2 the word
     * @param z true to keep the word's case (no auto caps)
     */
    public boolean syncAddWordSubstitution(String str, String str2, boolean z) {
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded");
            return false;
        }
        try {
            this.personalDictionaryUtil.add(WordSubstitution.createUserSubstitution(PersonalDictionaryConstants.LOCALE_ALL, str, str2, !z));
            return true;
        } catch (IllegalArgumentException | AudAddException | InitialisationIncompleteException | KeyAlreadyDefinedException e4) {
            Logger.errorWithException(TAG, e4, "PersonalDictionaryManager sync new word substitution, IllegalArgumentException");
            return false;
        }
    }

    /** @param z true to include shortcut words */
    public void getLearnedWords(final LearnedWordsCallback callback, boolean z) {
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
                    this.personalDictionaryManager.getLearnedWordsUtil("primary-model").getLearnedWords(learnedWordsListener, z);
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
                    public void complete(boolean z) {
                        Logger.debug(DictionaryManager.TAG, "Save complete: " + z);
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

    /** @param str the typed input */
    public String getWordSubstitutionForInput(String str, CapsMode mode, Locale locale) {
        PersonalDictionaryUtil.CapsMode capsMode;
        if (!isLoaded()) {
            Logger.error(TAG, "PersonalDictionaryManager and PDU not loaded - returning verbatim!");
            return str;
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
        return this.personalDictionaryUtil.getWordSubstitutionForInput(str, capsMode, locale);
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

    /** @param obj a {@link DictionaryWord} */
    public DictionaryWord asDictionaryWord(Object obj) {
        return (DictionaryWord) obj;
    }

    /** @param obj a {@link DictionaryWord} */
    public String getWord(Object obj) {
        return asDictionaryWord(obj).getWord();
    }

    /** @param obj a {@link DictionaryWord}; null is returned unless it is a WordSubstitution */
    public String getSubstitutionKey(Object obj) {
        DictionaryWord dictionaryWordM3844a = asDictionaryWord(obj);
        if (dictionaryWordM3844a instanceof WordSubstitution) {
            return ((WordSubstitution) dictionaryWordM3844a).getKey();
        }
        return null;
    }

    /**
     * @param str the locale string, or empty for entries stored for all locales
     * @param z true for word substitutions, false for personal words
     */
    public List<DictionaryWord> getWordsForLocale(String str, boolean z) {
        Map personalDictionary;
        PersonalDictionaryUtil personalDictionaryUtilM3859d = getPersonalDictionaryUtil();
        ArrayList arrayList = new ArrayList();
        if (personalDictionaryUtilM3859d != null) {
            if (z) {
                personalDictionary = personalDictionaryUtilM3859d.getWordSubstitutions();
            } else {
                personalDictionary = personalDictionaryUtilM3859d.getPersonalDictionary();
            }
            for (Object obj : personalDictionary.values()) {
                DictionaryWord dictionaryWord = (DictionaryWord) obj;
                if (dictionaryWord.getLocale().toString().equals(str) || (str.equals("") && dictionaryWord.getLocale().equals(PersonalDictionaryConstants.LOCALE_ALL))) {
                    arrayList.add(dictionaryWord);
                }
            }
        }
        return arrayList;
    }
}
