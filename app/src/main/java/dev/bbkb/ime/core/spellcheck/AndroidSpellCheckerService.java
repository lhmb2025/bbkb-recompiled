package dev.bbkb.ime.core.spellcheck;

import android.content.Intent;
import android.content.SharedPreferences;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.provider.Settings;
import android.service.textservice.SpellCheckerService;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SuggestionsInfo;

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.engine.DictionaryLoader;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
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

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import android.util.Log;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.engine.NuanceSDKManager;


public final class AndroidSpellCheckerService extends SpellCheckerService implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private float mSensitivityThreshold;

    private boolean mUseContacts;

    private ContactsLearningManager mContactsManager;

    private volatile boolean mPreferenceChanged;

    // --- SC-AUDIT: Debug counters for runtime validation ---
    private static final String SC_AUDIT_TAG = "SC-AUDIT";
    private final AtomicInteger scAuditDictUnavailableCount = new AtomicInteger(0);
    private final AtomicInteger scAuditWordCheckCount = new AtomicInteger(0);
    // --- end SC-AUDIT ---

    private final Semaphore mDictSemaphore = new Semaphore(2, true);

    private final ConcurrentLinkedQueue<Integer> mSessionIdPool = new ConcurrentLinkedQueue<>();

    private DictionaryLoader mDictionaryLoader = null;

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
            if (BuildConfig.DEBUG) Log.d("PIPELINE", "SC:service:onCreate");
        SubtypeManager.ensureInitialized(getApplicationContext());
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this);
        this.mSensitivityThreshold = parseSensitivity(defaultSharedPreferences);
        this.mContactsManager = new ContactsLearningManager(this);
        this.mPreferenceChanged = false;
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(defaultSharedPreferences, "pref_spellcheck_use_contacts");
        onSharedPreferenceChanged(defaultSharedPreferences, "pref_spellcheck_sensitivity");
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

    public float getSensitivityThreshold() {
        return this.mSensitivityThreshold;
    }

    private float parseSensitivity(SharedPreferences sharedPreferences) {
        String sensitivity = sharedPreferences.getString("pref_spellcheck_sensitivity", "balanced");
        switch (sensitivity) {
            case "lenient":
                return 0.15f;
            case "strict":
                return 0.07f;
            case "balanced":
            default:
                return 0.11f;
        }
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

    private static String getKeyboardLayoutName(Locale locale) {
        final int script = ScriptUtils.getScriptFromLocale(locale);
        if (script == 3) {
            // Cyrillic. This was a jadx-reconstructed String switch left as raw
            // hash comparisons against 3141/3486/3489 with a char sentinel, which
            // would silently mis-key the moment a language tag was added
            // (audit GD-21). An unhandled script throws below, so a mis-keyed
            // branch here would crash the spell-checker service.
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
        final String name = script >= 0 && script < LAYOUT_BY_SCRIPT.length ? LAYOUT_BY_SCRIPT[script] : null;
        if (name == null) {
            throw new RuntimeException("Unknown script supplied for [" + locale + "] locale: " + script);
        }
        return name;
    }

    @Override // android.content.SharedPreferences.OnSharedPreferenceChangeListener
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        boolean useContacts;
        if ("pref_spellcheck_use_contacts".equals(key) && (useContacts = sharedPreferences.getBoolean("pref_spellcheck_use_contacts", true)) != this.mUseContacts) {
            this.mDictSemaphore.acquireUninterruptibly(2);
            try {
                this.mUseContacts = useContacts;
                if (this.mDictionaryLoader != null) {
                    Locale currentLocale = this.mDictionaryLoader.getLocale();
                    this.mDictionaryLoader = null;
                    initDictionaryInternal(currentLocale);
                }
                this.mContactsManager.setContactsDictEnabled(this.mUseContacts);
                this.mPreferenceChanged = true;
            } finally {
                this.mDictSemaphore.release(2);
            }
        } else if ("pref_spellcheck_sensitivity".equals(key)) {
            float newThreshold = parseSensitivity(sharedPreferences);
            if (newThreshold != this.mSensitivityThreshold) {
                this.mSensitivityThreshold = newThreshold;
                this.mPreferenceChanged = true;
            }
        }
    }

    @Override // android.service.textservice.SpellCheckerService
    public SpellCheckerService.Session createSession() {
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "SC:service:createSession" +
                " dictAvailable=" + (this.mDictionaryLoader != null && this.mDictionaryLoader.isDictionaryReady()) +
                " dictLocale=" + (this.mDictionaryLoader != null ? this.mDictionaryLoader.getLocale() : "null"));
        }
        return new AndroidSpellCheckerSession(this);
    }

    public static SuggestionsInfo createNotInDictionaryResult(boolean hasSuggestions) {
        return new SuggestionsInfo(hasSuggestions ? SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO : 0, EMPTY_STRING_ARRAY);
    }

    public static SuggestionsInfo createInDictionaryResult() {
        return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY_STRING_ARRAY);
    }

    public boolean isValidWord(String word) {
        boolean valid;
        this.mDictSemaphore.acquireUninterruptibly();
        try {
            if (this.mDictionaryLoader != null) {
                valid = this.mDictionaryLoader.isWordValid(word, false);
                if (BuildConfig.DEBUG) {
                    Log.d("PIPELINE", "SC:wordCheck" +
                        " word=\"" + word + "\"" +
                        " valid=" + valid +
                        " dictLocale=" + this.mDictionaryLoader.getLocale() +
                        " dictType=" + (this.mDictionaryLoader.getMainDictionary() != null ? this.mDictionaryLoader.getMainDictionary().getClass().getSimpleName() : "null"));
                }
                // SC-AUDIT SC-03: Log word validation results periodically
                if (BuildConfig.DEBUG) {
                    int count = scAuditWordCheckCount.incrementAndGet();
                    if (count <= 5 || count % 50 == 0) {
                        Logger.info(SC_AUDIT_TAG, "SC-03 wordCheck word=\"" + word + "\" valid=" + valid
                            + " dictLocale=" + this.mDictionaryLoader.getLocale()
                            + " totalChecks=" + count);
                    }
                }
            } else {
                Logger.info("BlackBerrySpellChecker", "Dictionary Facilitator isn't available, can't check if word is valid");
                // SC-AUDIT SC-03: Log every dict-unavailable event
                if (BuildConfig.DEBUG) {
                    int unavailCount = scAuditDictUnavailableCount.incrementAndGet();
                    Logger.info(SC_AUDIT_TAG, "SC-03 DICT_UNAVAILABLE word=\"" + word
                        + "\" returningValid=true count=" + unavailCount);
                }
                valid = true;
            }
            return valid;
        } finally {
            this.mDictSemaphore.release();
        }
    }

    public SuggestionResult getSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid) throws Throwable {
        if (BuildConfig.DEBUG) {
            String composing = composingTracker.isComposing() ? composingTracker.getComposingText() : "(not composing)";
            if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "SC:getSuggestions" +
                " composing=\"" + composing + "\"" +
                " dictAvailable=" + (this.mDictionaryLoader != null && this.mDictionaryLoader.isDictionaryReady()) +
                " dictLocale=" + (this.mDictionaryLoader != null ? this.mDictionaryLoader.getLocale() : "null"));
            }
        }
        this.mDictSemaphore.acquireUninterruptibly();
        // Audit GD-12: poll() returns null on an empty queue and sessionId.intValue()
        // dereferenced it unguarded - the two `if (sessionId != null)` checks below it
        // were dead, because the NPE had already fired. mDictionaryLoader was likewise
        // dereferenced with no check although every sibling method checks it. The pool
        // and the semaphore both hold 2, so this is balanced today, but any permit
        // change turns it into an NPE inside the spell-checker binder call.
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

    public boolean initDictionaryForLocale(Locale locale) {
        boolean isAutoSubtype = isAutoSubtypeSelected();
        Set<Locale> activeLocales = SubtypeManager.getInstance().getCurrentSubtypeAdditionalLocales();
        this.mDictSemaphore.acquireUninterruptibly();
        try {
            if (this.mDictionaryLoader == null || !locale.equals(this.mDictionaryLoader.getLocale())) {
                initDictionaryInternal(locale);
            }
            if (this.mDictionaryLoader.isDictionaryReady()) {
                return (!locale.equals(SubtypeManager.getInstance().getCurrentSubtypeLocale()) || activeLocales == null) ? true : this.mDictionaryLoader.updateAdditionalLocales(activeLocales, false, null);
            }
            return false;
        } finally {
            this.mDictSemaphore.release();
        }
    }

    @Override // android.app.Service
    public boolean onUnbind(Intent intent) {
        this.mDictSemaphore.acquireUninterruptibly(2);
        try {
            if (this.mDictionaryLoader != null) {
                this.mDictionaryLoader.closeAndReset();
                this.mDictionaryLoader = null;
            }
            this.mContactsManager.unregister();
            this.mDictSemaphore.release(2);
            this.mKeyboardCache.clear();
            return false;
        } catch (Throwable th) {
            this.mDictSemaphore.release(2);
            throw th;
        }
    }

    public Keyboard getKeyboardForLocale(Locale locale) {
        Keyboard keyboard = this.mKeyboardCache.get(locale);
        if (keyboard == null && (keyboard = buildKeyboard(locale)) != null) {
            this.mKeyboardCache.put(locale, keyboard);
        }
        return keyboard;
    }

    private Keyboard buildKeyboard(Locale locale) {
        return createKeyboardBuilder(SubtypeFactory.createSubtype(locale.toString(), getKeyboardLayoutName(locale))).getKeyboard(0);
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

    private boolean isAutoSubtypeSelected() {
        String string = Settings.Secure.getString(getContentResolver(), "selected_spell_checker_subtype");
        return string != null && string.equals("0");
    }

    private void initDictionaryInternal(Locale locale) {
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "SC:initDictionary" +
                " locale=" + locale +
                " useContacts=" + this.mUseContacts +
                " previousDictLocale=" + (this.mDictionaryLoader != null ? this.mDictionaryLoader.getLocale() : "null"));
        }
        DictionaryLoader oldLoader = this.mDictionaryLoader;
        if (oldLoader != null) {
            oldLoader.closeAndReset();
        }
        this.mDictionaryLoader = new DictionaryLoader();
        this.mDictionaryLoader.initDictionaryWithPrefix(this, locale, this.mUseContacts, false, null, "spellcheck_");
        // SC-AUDIT SC-01/SC-04: Log dictionary initialization with locale and NuanceSDK state
        if (BuildConfig.DEBUG) {
            Locale[] primaryLangs = NuanceSDKManager.getInstance() != null ? NuanceSDKManager.getInstance().getLanguage() : null;
            Locale[] secondaryLangs = NuanceSDKManager.getSecondary() != null ? NuanceSDKManager.getSecondary().getLanguage() : null;
            String imeLocale = SubtypeManager.getInstance() != null ? String.valueOf(SubtypeManager.getInstance().getCurrentSubtypeLocale()) : "null";
            Logger.info(SC_AUDIT_TAG, "SC-01/SC-04 dictInit"
                + " spellCheckerLocale=" + locale
                + " imeActiveLocale=" + imeLocale
                + " primarySDKLangs=" + (primaryLangs != null ? Arrays.toString(primaryLangs) : "null")
                + " secondarySDKLangs=" + (secondaryLangs != null ? Arrays.toString(secondaryLangs) : "null")
                + " dictAvailable=" + (this.mDictionaryLoader != null && this.mDictionaryLoader.isDictionaryReady()));
        }
    }

    boolean hasPreferenceChanged() {
        return this.mPreferenceChanged;
    }

    void clearPreferenceChanged() {
        this.mPreferenceChanged = false;
    }
}
