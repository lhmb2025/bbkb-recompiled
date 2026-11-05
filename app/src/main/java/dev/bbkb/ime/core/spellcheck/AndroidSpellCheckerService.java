package dev.bbkb.ime.core.spellcheck;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.service.textservice.SpellCheckerService;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SuggestionsInfo;

import com.blackberry.nuanceshim.NuanceSDK;
import com.blackberry.nuanceshim.languagepack.LanguagePackManager;

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.engine.DictionaryLoader;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil;
import dev.bbkb.ime.core.contacts.ContactsLearningManager;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.suggestion.SuggestionResult;
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.ProximityGrid;
import dev.bbkb.ime.keyboard.KeyboardBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.engine.EngineThreads;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;


public final class AndroidSpellCheckerService extends SpellCheckerService implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    /**
     * How many languages the spell checker's engine keeps loaded at once. A request in a language
     * that is not loaded adds it rather than replacing the others, so two fields in different
     * languages do not reload the engine on every check; past this many, the oldest are dropped.
     */
    static final int MAX_LOADED_LANGUAGES = 4;

    /**
     * How long the engine stays loaded with no request before it is disposed (P-9): it holds a
     * private copy of each loaded language's dictionary, 5 to 6 MB of native heap apiece, in the
     * keyboard's own process. The next request loads it again.
     */
    static final long ENGINE_IDLE_RELEASE_MS = 3 * 60 * 1000L;

    /** Preference: whether words the keyboard learned from typing count as spelled correctly. */
    static final String PREF_ACCEPT_LEARNED_WORDS = "pref_spellcheck_accept_learned_words";

    /**
     * How long a binder thread waits for a language load on the engine loader thread. The load
     * queues behind the keyboard's own loads there; past this the request has no opinion on its
     * words and the load still lands for the next one.
     */
    static final long LOAD_WAIT_MS = 30_000L;

    /**
     * How long a binder thread waits for one dictionary answer on the engine thread, where it
     * queues behind typing. A validity check that times out answers "valid" (a slow engine must
     * not paint a red squiggle); a suggestion lookup answers none.
     */
    static final long QUERY_WAIT_MS = 5_000L;

    private boolean mUseContacts;

    private volatile boolean mAcceptLearnedWords = true;

    private ContactsLearningManager mContactsManager;

    private volatile boolean mPreferenceChanged;

    private final Semaphore mDictSemaphore = new Semaphore(2, true);

    private final ConcurrentLinkedQueue<Integer> mSessionIdPool = new ConcurrentLinkedQueue<>();

    private DictionaryLoader mDictionaryLoader = null;

    /**
     * The languages the engine holds, the dictionary's own language first; null when nothing is
     * loaded. Written only with both dictionary permits held.
     */
    private volatile List<Locale> mLoadedLocales = null;

    private final ConcurrentHashMap<Locale, Keyboard> mKeyboardCache = new ConcurrentHashMap<>();

    private final SuggestionStripSettings mSuggestionStripSettings = new SuggestionStripSettings(true, null, false);

    /**
     * Bumped whenever what the engine answers from may have changed under the sessions: a load of
     * languages, or the engine's release. Sessions drop their cached verdicts when it moves.
     */
    private final AtomicInteger mEngineGeneration = new AtomicInteger();

    /**
     * Session requests running now. The engine is released only while this is zero, read with
     * both dictionary permits held: a request counts itself before its first engine call, and
     * that call (the dictionary load) needs a permit, so it either stops the release or runs
     * after it and loads a fresh engine. Nothing in a request touches a disposed engine.
     */
    private final AtomicInteger mActiveRequests = new AtomicInteger();

    private volatile long mLastRequestEndMs;

    /** Main-thread handler for the idle release; null before onCreate and after onDestroy. */
    private volatile Handler mIdleHandler;

    private final AtomicBoolean mIdleReleasePosted = new AtomicBoolean();

    private final Runnable mIdleReleaseTask = () -> {
        this.mIdleReleasePosted.set(false);
        final long remaining = releaseEngineIfIdle(SystemClock.uptimeMillis());
        if (remaining > 0) {
            scheduleIdleRelease(remaining);
        }
    };

    public AndroidSpellCheckerService() {
        for (int i = 0; i < 2; i++) {
            this.mSessionIdPool.add(Integer.valueOf(i));
        }
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        this.mIdleHandler = new Handler(Looper.getMainLooper());
        SubtypeManager.ensureInitialized(getApplicationContext());
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this);
        this.mContactsManager = new ContactsLearningManager(this);
        this.mPreferenceChanged = false;
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(defaultSharedPreferences, "pref_spellcheck_use_contacts");
        onSharedPreferenceChanged(defaultSharedPreferences, PREF_ACCEPT_LEARNED_WORDS);
    }

    /**
     * Audit GD-15/GX-25: onCreate registers this service as a preference-change
     * listener and the class had no onDestroy at all - the only unbalanced
     * registration in the tree. SharedPreferencesImpl holds listeners weakly, so the
     * service is not leaked, but a destroyed-and-recreated service kept receiving
     * onSharedPreferenceChanged on the dead instance until GC - and that callback
     * acquires both dictionary permits and rebuilds the dictionary.
     */
    @Override
    public void onDestroy() {
        PrefsManager.INSTANCE.getPrefs(this).unregisterOnSharedPreferenceChangeListener(this);
        final Handler handler = this.mIdleHandler;
        this.mIdleHandler = null;
        if (handler != null) {
            handler.removeCallbacks(this.mIdleReleaseTask);
        }
        // Normally already done by onUnbind; this catches a destroy without one.
        this.mDictSemaphore.acquireUninterruptibly(2);
        try {
            if (this.mActiveRequests.get() == 0) {
                releaseEngineLocked();
            }
        } finally {
            this.mDictSemaphore.release(2);
        }
        super.onDestroy();
    }

    /** The current {@link #mEngineGeneration}. */
    int getEngineGeneration() {
        return this.mEngineGeneration.get();
    }

    /** A session request starts; pair with {@link #endRequest}. */
    void beginRequest() {
        this.mActiveRequests.incrementAndGet();
    }

    /** A session request ended; the last one to end starts the idle countdown. */
    void endRequest() {
        if (this.mActiveRequests.decrementAndGet() == 0) {
            this.mLastRequestEndMs = SystemClock.uptimeMillis();
            scheduleIdleRelease(ENGINE_IDLE_RELEASE_MS);
        }
    }

    /**
     * At most one countdown is posted; when it fires early (requests came and went since), it
     * re-posts for the rest rather than every request moving it.
     */
    private void scheduleIdleRelease(long delayMs) {
        final Handler handler = this.mIdleHandler;
        if (handler != null && this.mIdleReleasePosted.compareAndSet(false, true)) {
            handler.postDelayed(this.mIdleReleaseTask, delayMs);
        }
    }

    /**
     * Releases the engine when no request has run for {@link #ENGINE_IDLE_RELEASE_MS}. Returns
     * how long to wait before asking again, or 0 when there is nothing to wait for (released,
     * or a request is running and its end restarts the countdown). Never blocks: it runs on the
     * main thread, so a busy dictionary just means another try later.
     */
    long releaseEngineIfIdle(long nowMs) {
        if (this.mActiveRequests.get() > 0) {
            return 0;
        }
        final long idleMs = nowMs - this.mLastRequestEndMs;
        if (idleMs < ENGINE_IDLE_RELEASE_MS) {
            return ENGINE_IDLE_RELEASE_MS - idleMs;
        }
        if (!this.mDictSemaphore.tryAcquire(2)) {
            return ENGINE_IDLE_RELEASE_MS;
        }
        try {
            if (this.mActiveRequests.get() == 0) {
                releaseEngineLocked();
            }
        } finally {
            this.mDictSemaphore.release(2);
        }
        return 0;
    }

    /**
     * Closes the dictionary; the next request's {@link #initDictionaryForLocale} loads the
     * languages again. The engine itself is kept: disposing it while the typing engine is in
     * use crashed the typing engine (see NuanceSDKManager.resetForTests), so the memory the
     * release used to free stays allocated and only the generation the sessions key their
     * cached verdicts on moves. Callers hold both permits and have seen no request running.
     */
    private void releaseEngineLocked() {
        this.mLoadedLocales = null;
        if (this.mDictionaryLoader != null) {
            this.mDictionaryLoader.closeAndReset();
            this.mDictionaryLoader = null;
        }
        this.mEngineGeneration.incrementAndGet();
    }

    /**
     * Keyboard layout by {@link ScriptUtils} script code (the index); null means the spell
     * checker has no layout for that script. Cyrillic (3) is chosen by language instead.
     */
    private static final String[] LAYOUT_BY_SCRIPT = {
            "arabic", "armenian_phonetic", "bengali", null /* 3 Cyrillic */, "hindi", "georgian",
            "greek", null /* 7 Chinese */, "hangul", "hebrew", "romaji", "kannada",
            null /* 12 Khmer */, null /* 13 Lao */, "qwerty", "malayalam", null /* 16 Myanmar */,
            null /* 17 Sinhala */, "tamil", "telugu", "thai", "qwerty" /* 21 Vietnamese */};

    /**
     * The keyboard layout the spell checker reads key positions from for {@code locale}, or null
     * when it has none for that script. Null is not an error: the words are still checked, only
     * without knowing which keys sit next to each other. (This used to throw, and the throw
     * escaped session creation, so a session in, say, Chinese crashed the service.)
     */
    static String getKeyboardLayoutName(Locale locale) {
        final int script = ScriptUtils.getScriptFromLocale(locale);
        if (script == 3) {
            // Cyrillic. This was a jadx-reconstructed String switch left as raw
            // hash comparisons against 3141/3486/3489 with a char sentinel, which
            // would silently mis-key the moment a language tag was added
            // (audit GD-21).
            switch (locale.getLanguage()) {
                case "bg":
                    return "bulgarian";
                case "mn":
                    return "mongolian";
                case "mk":
                    return "south_slavic";
                default:
                    return "east_slavic";
            }
        }
        return script >= 0 && script < LAYOUT_BY_SCRIPT.length ? LAYOUT_BY_SCRIPT[script] : null;
    }

    @Override // android.content.SharedPreferences.OnSharedPreferenceChangeListener
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        boolean useContacts;
        if ("pref_spellcheck_use_contacts".equals(key) && (useContacts = sharedPreferences.getBoolean("pref_spellcheck_use_contacts", true)) != this.mUseContacts) {
            // Main thread: the reload runs on the engine loader thread, which takes both
            // dictionary permits itself; nothing here waits for it.
            this.mUseContacts = useContacts;
            this.mContactsManager.setContactsDictEnabled(useContacts);
            this.mPreferenceChanged = true;
            EngineThreads.load(() -> {
                this.mDictSemaphore.acquireUninterruptibly(2);
                try {
                    final List<Locale> loaded = this.mLoadedLocales;
                    if (loaded != null) {
                        loadLanguages(loaded, useContacts);
                    }
                } finally {
                    this.mDictSemaphore.release(2);
                }
            });
        } else if (PREF_ACCEPT_LEARNED_WORDS.equals(key)) {
            final boolean accept = sharedPreferences.getBoolean(PREF_ACCEPT_LEARNED_WORDS, true);
            if (accept != this.mAcceptLearnedWords) {
                this.mAcceptLearnedWords = accept;
                this.mPreferenceChanged = true;
            }
        }
    }

    @Override // android.service.textservice.SpellCheckerService
    public SpellCheckerService.Session createSession() {
        return new AndroidSpellCheckerSession(this);
    }

    public static SuggestionsInfo createNotInDictionaryResult(boolean hasSuggestions) {
        return new SuggestionsInfo(hasSuggestions ? SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO : 0, EMPTY_STRING_ARRAY);
    }

    public static SuggestionsInfo createInDictionaryResult() {
        return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY_STRING_ARRAY);
    }

    /**
     * No opinion on the word: neither in the dictionary nor a typo, so nothing is underlined.
     * The answer while the session's language cannot be loaded (its pack is not installed), in
     * place of checking the words against whichever language the engine held before.
     */
    public static SuggestionsInfo createUnknownResult() {
        return new SuggestionsInfo(0, EMPTY_STRING_ARRAY);
    }

    /**
     * Whether the loaded dictionary knows {@code word}. The session asks only after
     * {@link #initDictionaryForLocale} has succeeded; with no dictionary the answer is true, so
     * a stray call never underlines a word. With learned words not accepted, the engine answers
     * from its language dictionaries alone, and the personal dictionary's words and (when used)
     * the contact names are added back from the lists the keyboard already keeps: the engine
     * holds those in its learned model too.
     */
    public boolean isValidWord(String word) {
        this.mDictSemaphore.acquireUninterruptibly();
        try {
            if (this.mDictionaryLoader == null) {
                Logger.info("BlackBerrySpellChecker", "No dictionary loaded, can't check if word is valid");
                return true;
            }
            // The engine answers on the engine thread, bounded; the permit held here keeps the
            // dictionary from being released or reloaded under the question.
            final DictionaryLoader loader = this.mDictionaryLoader;
            if (this.mAcceptLearnedWords) {
                return EngineThreads.query(() -> loader.isWordValid(word, false), QUERY_WAIT_MS, Boolean.TRUE);
            }
            return EngineThreads.query(() -> loader.isDictionaryWord(word), QUERY_WAIT_MS, Boolean.TRUE)
                    || PersonalDictionaryUtil.isPersonalWord(word)
                    || (this.mUseContacts && this.mContactsManager.isContactWord(word));
        } finally {
            this.mDictSemaphore.release();
        }
    }

    /**
     * Suggestions for the word the tracker {@code trackerFactory} builds. The factory runs on the
     * engine thread, in the same op as the lookup, so the tracker's feeds and the lookup reach
     * the engine as one group with nothing of the keyboard's in between. Null on a timeout.
     */
    public SuggestionResult getSuggestions(Supplier<ComposingTextTracker> trackerFactory, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid) throws Throwable {
        this.mDictSemaphore.acquireUninterruptibly();
        // Audit GD-12: poll() returns null on an empty queue and sessionId.intValue()
        // dereferenced it unguarded. The pool and the semaphore both hold 2, so this is
        // balanced today, but any permit change turns it into an NPE inside the
        // spell-checker binder call.
        Integer sessionId = this.mSessionIdPool.poll();
        if (sessionId == null || this.mDictionaryLoader == null) {
            this.mDictSemaphore.release();
            return null;
        }
        try {
            final DictionaryLoader loader = this.mDictionaryLoader;
            final int id = sessionId.intValue();
            return EngineThreads.query(() -> {
                final ComposingTextTracker composingTracker = trackerFactory.get();
                return composingTracker == null ? null : loader.generateSuggestions(composingTracker,
                        prevWordsInfo, proximityGrid, this.mSuggestionStripSettings, id);
            }, QUERY_WAIT_MS, null);
        } finally {
            this.mSessionIdPool.add(sessionId);
            this.mDictSemaphore.release();
        }
    }

    /**
     * Makes sure the engine holds every language a session in {@code locale} checks against (see
     * {@link #languagesForSession}). False when that cannot be done, chiefly because the session's
     * own language has no installed pack; the session then has no opinion on its words.
     *
     * <p>Nothing is reloaded while those languages are all loaded already. That used to happen on
     * every request: the check compared the session's language with the dictionary's own language
     * only, so text in a keyboard's second language reloaded the dictionary each time, and the
     * other languages were re-sent to the engine on every call. A reload holds the engine lock the
     * keyboard also types through, so it could stall typing as well as the underlines.
     */
    public boolean initDictionaryForLocale(Locale locale) {
        final SubtypeManager subtypes = SubtypeManager.getInstance();
        final LanguagePackManager packs = LanguagePackManager.getInstance(this);
        final List<Locale> wanted = languagesForSession(locale, subtypes.getCurrentSubtypeLocale(),
                subtypes.getCurrentSubtypeAdditionalLocales(), packs::isInstalled);
        if (wanted.isEmpty()) {
            return false;
        }
        this.mDictSemaphore.acquireUninterruptibly();
        try {
            if (isLoaded(wanted)) {
                return true;
            }
        } finally {
            this.mDictSemaphore.release();
        }
        // Loading needs the engine to itself: no other session may be reading the dictionary.
        // The load runs on the engine loader thread and takes both permits there, so a wait
        // that times out leaves nothing unguarded; this binder thread only waits, bounded.
        final boolean useContacts = this.mUseContacts;
        final Future<Boolean> load = EngineThreads.load(() -> {
            this.mDictSemaphore.acquireUninterruptibly(2);
            try {
                if (!isLoaded(wanted)) {
                    loadLanguages(mergeLanguages(this.mLoadedLocales, wanted, MAX_LOADED_LANGUAGES),
                            useContacts);
                }
                return isLoaded(wanted);
            } finally {
                this.mDictSemaphore.release(2);
            }
        });
        try {
            return Boolean.TRUE.equals(load.get(LOAD_WAIT_MS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            Logger.warn("BlackBerrySpellChecker", "language load still running after " + LOAD_WAIT_MS + " ms");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            Logger.warn("BlackBerrySpellChecker", "language load failed: " + e.getCause());
            return false;
        }
    }

    private boolean isLoaded(List<Locale> wanted) {
        final List<Locale> loaded = this.mLoadedLocales;
        return loaded != null && this.mDictionaryLoader != null
                && this.mDictionaryLoader.isDictionaryReady() && loaded.containsAll(wanted);
    }

    /**
     * Loads {@code languages} into a fresh dictionary, the first as its own language, and the
     * keyboard layout of that first language for key positions. Callers hold both permits and
     * run on the engine loader thread (the engine is created and loaded there).
     */
    private void loadLanguages(List<Locale> languages, boolean useContacts) {
        this.mEngineGeneration.incrementAndGet();
        this.mLoadedLocales = null;
        DictionaryLoader oldLoader = this.mDictionaryLoader;
        if (oldLoader != null) {
            oldLoader.closeAndReset();
        }
        final Locale primary = languages.get(0);
        this.mDictionaryLoader = new DictionaryLoader();
        this.mDictionaryLoader.initDictionaryWithPrefix(this, primary, useContacts, false, null, "spellcheck_");
        if (!this.mDictionaryLoader.isDictionaryReady()) {
            return;
        }
        if (languages.size() > 1) {
            final Set<Locale> others = new LinkedHashSet<>(languages.subList(1, languages.size()));
            if (!this.mDictionaryLoader.updateAdditionalLocales(others, false, null)) {
                // The engine refused the extras: keep the dictionary's own language alone.
                languages = Collections.singletonList(primary);
            }
        }
        final NuanceSDK engine = NuanceSDKManager.getSecondary();
        if (engine != null) {
            // This engine's own copy of the model holds the contact names the keyboard learned;
            // "Use contacts for spellchecking" switches that category off in it.
            engine.setContactsCategoryState(useContacts);
        }
        final Keyboard keyboard = getKeyboardForLocale(primary);
        if (engine != null && keyboard != null) {
            engine.loadKeyboardLayout(keyboard.isTouchKeyboard());
        }
        this.mLoadedLocales = Collections.unmodifiableList(new ArrayList<>(languages));
    }

    /**
     * The languages a session in {@code sessionLocale} checks words against, its own first: when
     * the session's language is one of the current keyboard's ({@code keyboardPrimary} or one of
     * its {@code keyboardAdditional} extras), the rest of that keyboard's languages too, layout
     * language first, then the extras in their order. Languages without an installed pack are
     * left out. Empty when the session's own language is not installed: checking its words
     * against another language would underline nearly all of them.
     */
    static List<Locale> languagesForSession(Locale sessionLocale, Locale keyboardPrimary,
            Set<Locale> keyboardAdditional, Predicate<Locale> isInstalled) {
        final List<Locale> result = new ArrayList<>();
        if (sessionLocale == null || !isInstalled.test(sessionLocale)) {
            return result;
        }
        result.add(sessionLocale);
        if (keyboardAdditional == null) {
            return result;
        }
        final List<Locale> keyboardLocales = ResourceLocaleUtils.getKeyboardLocales(keyboardPrimary, keyboardAdditional);
        if (!keyboardLocales.contains(sessionLocale)) {
            return result;
        }
        for (Locale locale : keyboardLocales) {
            if (!result.contains(locale) && isInstalled.test(locale)) {
                result.add(locale);
            }
        }
        return result;
    }

    /**
     * The languages to load for a request needing {@code wanted}: those first, in their order,
     * then the languages already {@code loaded} that still fit under {@code max}, most recently
     * loaded first. Keeping them means a field in another language next to this one does not
     * reload the engine on each check.
     */
    static List<Locale> mergeLanguages(List<Locale> loaded, List<Locale> wanted, int max) {
        final List<Locale> result = new ArrayList<>(wanted);
        if (loaded != null) {
            for (Locale locale : loaded) {
                if (result.size() >= max) {
                    break;
                }
                if (!result.contains(locale)) {
                    result.add(locale);
                }
            }
        }
        return result;
    }

    @Override // android.app.Service
    public boolean onUnbind(Intent intent) {
        this.mDictSemaphore.acquireUninterruptibly(2);
        try {
            // A call still running on a binder thread keeps the engine; its end starts the idle
            // countdown, which releases it then.
            if (this.mActiveRequests.get() == 0) {
                releaseEngineLocked();
            }
            this.mContactsManager.unregister();
            this.mKeyboardCache.clear();
            return false;
        } finally {
            this.mDictSemaphore.release(2);
        }
    }

    /** The proximity keyboard for {@code locale}, or null when there is no layout for its script. */
    public Keyboard getKeyboardForLocale(Locale locale) {
        Keyboard keyboard = this.mKeyboardCache.get(locale);
        if (keyboard == null && (keyboard = buildKeyboard(locale)) != null) {
            this.mKeyboardCache.put(locale, keyboard);
        }
        return keyboard;
    }

    private Keyboard buildKeyboard(Locale locale) {
        final String layoutName = getKeyboardLayoutName(locale);
        if (layoutName == null) {
            return null;
        }
        return createKeyboardBuilder(SubtypeFactory.createSubtype(locale.toString(), layoutName)).getKeyboard(0);
    }

    private KeyboardBuilder createKeyboardBuilder(InputMethodSubtype inputMethodSubtype) {
        EditorInfo editorInfo = new EditorInfo();
        editorInfo.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(this, editorInfo);
        aVar.setKeyboardGeometry(480, 368);
        aVar.setSubtype(inputMethodSubtype);
        aVar.setSplitLayoutEnabled(true);
        return aVar.build();
    }

    boolean hasPreferenceChanged() {
        return this.mPreferenceChanged;
    }

    void clearPreferenceChanged() {
        this.mPreferenceChanged = false;
    }
}
