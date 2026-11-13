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
     * Languages kept loaded at once. A new language is added rather than replacing the others, so
     * fields in different languages do not reload the engine on every check; the oldest drop off.
     */
    static final int MAX_LOADED_LANGUAGES = 4;

    /** Idle time before the dictionary is released (5 to 6 MB of native heap per language). */
    static final long ENGINE_IDLE_RELEASE_MS = 3 * 60 * 1000L;

    /** Preference: whether words the keyboard learned from typing count as spelled correctly. */
    static final String PREF_ACCEPT_LEARNED_WORDS = "pref_spellcheck_accept_learned_words";

    /**
     * Binder-thread wait for a language load (it queues behind the keyboard's loads). On timeout
     * the request has no opinion and the load still lands for the next one.
     */
    static final long LOAD_WAIT_MS = 30_000L;

    /**
     * Binder-thread wait for one dictionary answer (it queues behind typing). A timed-out validity
     * check answers "valid" so a slow engine paints no squiggle; a suggestion lookup answers none.
     */
    static final long QUERY_WAIT_MS = 5_000L;

    private boolean mUseContacts;

    private volatile boolean mAcceptLearnedWords = true;

    private ContactsLearningManager mContactsManager;

    private volatile boolean mPreferenceChanged;

    private final Semaphore mDictSemaphore = new Semaphore(2, true);

    private final ConcurrentLinkedQueue<Integer> mSessionIdPool = new ConcurrentLinkedQueue<>();

    private DictionaryLoader mDictionaryLoader = null;

    /** Languages the engine holds, the dictionary's own first; null when none. Written with both permits held. */
    private volatile List<Locale> mLoadedLocales = null;

    private final ConcurrentHashMap<Locale, Keyboard> mKeyboardCache = new ConcurrentHashMap<>();

    private final SuggestionStripSettings mSuggestionStripSettings = new SuggestionStripSettings(true, null, false);

    /** Bumped on every language load or release; sessions drop cached verdicts when it moves. */
    private final AtomicInteger mEngineGeneration = new AtomicInteger();

    /**
     * Requests running now. Release happens only while this is zero, read with both permits held: a
     * request counts itself before its first (permit-taking) engine call, so it either blocks the
     * release or runs after it and reloads. No request touches a released dictionary.
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

    @Override
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

    /** Unregisters the pref listener, which would otherwise rebuild the dictionary on a dead instance until GC. */
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

    /** At most one countdown is posted; if it fires early it re-posts for the remainder. */
    private void scheduleIdleRelease(long delayMs) {
        final Handler handler = this.mIdleHandler;
        if (handler != null && this.mIdleReleasePosted.compareAndSet(false, true)) {
            handler.postDelayed(this.mIdleReleaseTask, delayMs);
        }
    }

    /**
     * Releases after {@link #ENGINE_IDLE_RELEASE_MS} idle. Returns the delay before asking again, or 0
     * (released, or a running request will restart the countdown). Main thread, so it never blocks.
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
     * Closes the dictionary and bumps the generation. The engine itself is never disposed: that
     * crashes the typing engine (see NuanceSDKManager.resetForTests). Callers hold both permits
     * and have seen no request running.
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
     * Layout for key positions in {@code locale}, or null when the script has none. Null is not an
     * error (words are checked without proximity); throwing here would crash session creation.
     */
    static String getKeyboardLayoutName(Locale locale) {
        final int script = ScriptUtils.getScriptFromLocale(locale);
        if (script == 3) {
            // Cyrillic.
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

    @Override
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

    @Override
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
     * No opinion (nothing underlined): the answer while the session's language has no installed
     * pack, instead of checking against whatever language the engine held.
     */
    public static SuggestionsInfo createUnknownResult() {
        return new SuggestionsInfo(0, EMPTY_STRING_ARRAY);
    }

    /**
     * Whether the dictionary knows {@code word}; true with no dictionary. With learned words not
     * accepted, the engine checks language dictionaries only, and personal words and contacts (which
     * live in the same learned model) are added back from the keyboard's own lists.
     */
    public boolean isValidWord(String word) {
        this.mDictSemaphore.acquireUninterruptibly();
        try {
            if (this.mDictionaryLoader == null) {
                Logger.info("BlackBerrySpellChecker", "No dictionary loaded, can't check if word is valid");
                return true;
            }
            // The held permit keeps the dictionary from being released or reloaded mid-query.
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
     * Suggestions for the word {@code trackerFactory} builds. The factory runs in the same engine-thread
     * op as the lookup so no keyboard work lands between the feeds and the lookup. Null on timeout.
     */
    public SuggestionResult getSuggestions(Supplier<ComposingTextTracker> trackerFactory, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid) throws Throwable {
        this.mDictSemaphore.acquireUninterruptibly();
        // Pool and semaphore both hold 2; the null check guards against them drifting apart.
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
     * Ensures the engine holds every language of {@link #languagesForSession}; false when it cannot
     * (chiefly no installed pack for the session's language). Nothing reloads while all are loaded:
     * a reload holds the engine lock the keyboard types through.
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
        // The load takes both permits on the loader thread, so a timed-out wait here leaves nothing unguarded.
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
     * Languages to check against: the session's own first, then, if it is one of the current
     * keyboard's, that keyboard's other languages in order. Uninstalled ones are left out; empty when
     * the session's own is not installed (another language would underline nearly every word).
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
     * {@code wanted} first, then already {@code loaded} languages that still fit under {@code max},
     * so a neighbouring field in another language does not reload the engine on each check.
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

    @Override
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
