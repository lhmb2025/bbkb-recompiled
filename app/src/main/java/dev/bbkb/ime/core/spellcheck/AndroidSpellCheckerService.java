package dev.bbkb.ime.core.spellcheck;

import android.content.Intent;
import android.content.SharedPreferences;
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
import dev.bbkb.ime.core.engine.dlm.LearnedModelStream;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.locale.SubtypeManager;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

import dev.bbkb.ime.core.engine.NuanceSDKManager;


public final class AndroidSpellCheckerService extends SpellCheckerService implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    /**
     * How many languages the spell checker's engine keeps loaded at once. A request in a language
     * that is not loaded adds it rather than replacing the others, so two fields in different
     * languages do not reload the engine on every check; past this many, the oldest are dropped.
     */
    static final int MAX_LOADED_LANGUAGES = 4;

    /** Preference: whether words the keyboard learned from typing count as spelled correctly. */
    static final String PREF_ACCEPT_LEARNED_WORDS = "pref_spellcheck_accept_learned_words";

    /** How long a read of the learned words is trusted before the model is read again. */
    private static final long LEARNED_WORDS_REFRESH_MS = 30_000L;

    /**
     * The learned-model qualities of a word that is known only because the keyboard learned it:
     * 0/1 learned, −11…−1 typed once and not kept yet. Words added on purpose (15, the personal
     * dictionary) and the usage records of dictionary and contact words (2/3) are not in it.
     */
    private static final int LEARNED_QUALITY_MIN = -11;
    private static final int LEARNED_QUALITY_MAX = 1;

    private boolean mUseContacts;

    private volatile boolean mAcceptLearnedWords = true;

    /** The learned-only words as last read from the model, and when; null until first read. */
    private volatile Set<String> mLearnedWords = null;
    private volatile long mLearnedWordsReadAt = 0L;

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

    public AndroidSpellCheckerService() {
        for (int i = 0; i < 2; i++) {
            this.mSessionIdPool.add(Integer.valueOf(i));
        }
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
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
        super.onDestroy();
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
            this.mDictSemaphore.acquireUninterruptibly(2);
            try {
                this.mUseContacts = useContacts;
                final List<Locale> loaded = this.mLoadedLocales;
                if (loaded != null) {
                    loadLanguages(loaded);
                }
                this.mContactsManager.setContactsDictEnabled(this.mUseContacts);
                this.mPreferenceChanged = true;
            } finally {
                this.mDictSemaphore.release(2);
            }
        } else if (PREF_ACCEPT_LEARNED_WORDS.equals(key)) {
            final boolean accept = sharedPreferences.getBoolean(PREF_ACCEPT_LEARNED_WORDS, true);
            if (accept != this.mAcceptLearnedWords) {
                this.mAcceptLearnedWords = accept;
                this.mLearnedWords = null;
                this.mPreferenceChanged = true;
            }
        }
    }

    /**
     * Whether {@code word} is known only because the keyboard learned it, and learned words are
     * not to count: the session then judges it misspelled even though the engine knows it.
     * Always false while learned words are accepted (the default).
     */
    public boolean isRejectedLearnedWord(String word) {
        if (this.mAcceptLearnedWords) {
            return false;
        }
        final Set<String> learned = learnedWords();
        return learned != null && learned.contains(word);
    }

    /**
     * The learned-only words, read from the engine's export of the learned model at most every
     * {@link #LEARNED_WORDS_REFRESH_MS}; the keyboard keeps learning while this runs. Exact
     * spellings: a learned capitalisation of a dictionary word ("BlackBerry") must not reject the
     * dictionary word itself. Null when the model cannot be read on this phone.
     */
    private Set<String> learnedWords() {
        final long now = SystemClock.elapsedRealtime();
        final Set<String> cached = this.mLearnedWords;
        if (cached != null && now - this.mLearnedWordsReadAt < LEARNED_WORDS_REFRESH_MS) {
            return cached;
        }
        NuanceSDK engine = NuanceSDKManager.peekSecondary();
        if (engine == null || !engine.isDLMTransferAvailable()) {
            engine = NuanceSDKManager.peekInstance();
        }
        if (engine == null || !engine.isDLMTransferAvailable()) {
            return cached;
        }
        final byte[] bytes = engine.exportDLM();
        if (bytes == null) {
            return cached;
        }
        final Set<String> words;
        try {
            words = learnedOnlyWords(LearnedModelStream.INSTANCE.parse(bytes).getWords());
        } catch (Exception e) {
            // LearnedModelStream.InvalidModelException; Kotlin does not declare it for Java.
            Logger.warn("BlackBerrySpellChecker", "the learned model does not parse: " + e.getMessage());
            return cached;
        }
        this.mLearnedWords = words;
        this.mLearnedWordsReadAt = now;
        return words;
    }

    /** The words of {@code records} known only because the keyboard learned them. */
    static Set<String> learnedOnlyWords(List<LearnedModelStream.Word> records) {
        final Set<String> words = new HashSet<>();
        for (LearnedModelStream.Word record : records) {
            final int quality = record.getQuality();
            if (quality >= LEARNED_QUALITY_MIN && quality <= LEARNED_QUALITY_MAX) {
                words.add(record.getText());
            }
        }
        return words;
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
     * a stray call never underlines a word.
     */
    public boolean isValidWord(String word) {
        this.mDictSemaphore.acquireUninterruptibly();
        try {
            if (this.mDictionaryLoader == null) {
                Logger.info("BlackBerrySpellChecker", "No dictionary loaded, can't check if word is valid");
                return true;
            }
            return this.mDictionaryLoader.isWordValid(word, false);
        } finally {
            this.mDictSemaphore.release();
        }
    }

    public SuggestionResult getSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid) throws Throwable {
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
            return this.mDictionaryLoader.generateSuggestions(composingTracker, prevWordsInfo,
                    proximityGrid, this.mSuggestionStripSettings, sessionId.intValue());
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
        this.mDictSemaphore.acquireUninterruptibly(2);
        try {
            if (!isLoaded(wanted)) {
                loadLanguages(mergeLanguages(this.mLoadedLocales, wanted, MAX_LOADED_LANGUAGES));
            }
            return isLoaded(wanted);
        } finally {
            this.mDictSemaphore.release(2);
        }
    }

    private boolean isLoaded(List<Locale> wanted) {
        final List<Locale> loaded = this.mLoadedLocales;
        return loaded != null && this.mDictionaryLoader != null
                && this.mDictionaryLoader.isDictionaryReady() && loaded.containsAll(wanted);
    }

    /**
     * Loads {@code languages} into a fresh dictionary, the first as its own language, and the
     * keyboard layout of that first language for key positions. Callers hold both permits.
     */
    private void loadLanguages(List<Locale> languages) {
        this.mLoadedLocales = null;
        DictionaryLoader oldLoader = this.mDictionaryLoader;
        if (oldLoader != null) {
            oldLoader.closeAndReset();
        }
        final Locale primary = languages.get(0);
        this.mDictionaryLoader = new DictionaryLoader();
        this.mDictionaryLoader.initDictionaryWithPrefix(this, primary, this.mUseContacts, false, null, "spellcheck_");
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
            this.mLoadedLocales = null;
            if (this.mDictionaryLoader != null) {
                this.mDictionaryLoader.closeAndReset();
                this.mDictionaryLoader = null;
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
