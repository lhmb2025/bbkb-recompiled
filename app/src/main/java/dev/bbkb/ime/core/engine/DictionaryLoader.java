package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.suggestion.SuggestionResult;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.shared.ThreadUtils;
import dev.bbkb.ime.keyboard.ProximityGrid;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import dev.bbkb.ime.BuildConfig;


/**
 * Owns the one main {@link Dictionary}, its locale and additional locales, and reloads it when they
 * change. Load/reload/threading behaviour is pinned by {@code DictionaryLoaderTest}.
 */
public class DictionaryLoader {

    public static final String TAG = "DictionaryLoader";

    private volatile DictionaryGroup mDictionaryGroup = new DictionaryGroup();

    private final Object mLock = new Object();


    public interface DictionaryInitCallback {
        void onDictionaryInitialized(boolean available);

        void onLocalesUpdated();
    }

    /**
     * Hook called when a word should be removed (or demoted) in the dynamic learning model:
     * after an auto-correction is reverted, and when backspacing through a word in
     * prediction mode.
     *
     * <p><b>Intentionally a no-op.</b> Verified against the original smali
     * ({@code core/g.a(Ljava/lang/String;)V} is {@code return-void}): the original
     * keyboard never implemented unlearning at these call sites either. This was
     * previously misnamed {@code onLanguagePackInstalled(String languageTag)} by the
     * deobfuscation. If unlearning is ever wanted, {@code NuanceSDK.deleteDLMWord(String)}
     * is the available engine API — note it deletes the learned entry outright rather
     * than decrementing its frequency.
     */
    public void unlearnWord(String word) {
    }

    /**
     * A locale and the dictionary loaded for it, swapped into {@link #mDictionaryGroup} as a unit.
     * The dictionary arrives later on the async path; the additional locales are recorded only when
     * a dictionary is kept or {@link #updateAdditionalLocales} succeeds.
     */
    private static class DictionaryGroup {

        public final Locale mLocale;

        /**
         * True only for the empty group a fresh loader or {@link #closeAndReset} installs. Its locale
         * is null, like a group initialised for a null locale, so the locale alone can't tell a
         * pending load "you were reset" apart from "your null-locale load is still wanted".
         */
        final boolean mRefusesPendingLoads;

        private volatile Set<Locale> mAdditionalLocales;

        private volatile Dictionary mMainDictionary;

        public DictionaryGroup() {
            this(null, null, null, true);
        }

        public DictionaryGroup(Locale locale, Dictionary dictionary, Set<Locale> additionalLocales) {
            this(locale, dictionary, additionalLocales, false);
        }

        private DictionaryGroup(Locale locale, Dictionary dictionary, Set<Locale> additionalLocales, boolean refusesPendingLoads) {
            this.mLocale = locale;
            this.mAdditionalLocales = additionalLocales;
            this.mMainDictionary = dictionary;
            this.mRefusesPendingLoads = refusesPendingLoads;
        }

        public void closeMainDictionary() {
            Dictionary dictionary = this.mMainDictionary;
            if (dictionary != null) {
                dictionary.close();
            }
        }
    }

    public Locale getLocale() {
        return this.mDictionaryGroup.mLocale;
    }

    /** The IME path: an async load on the primary engine. {@code useContacts} is not read. */
    public boolean initDictionary(Context context, Locale locale, boolean useContacts, boolean forceReload, DictionaryInitCallback callback) {
        return initDictionaryWithPrefix(context, locale, useContacts, forceReload, callback, "");
    }

    /**
     * {@code prefix} selects the path: {@code "spellcheck_"} loads synchronously on the secondary
     * engine (no callback, and the previous dictionary is not closed); anything else loads
     * asynchronously on the primary engine.
     */
    public boolean initDictionaryWithPrefix(Context context, Locale locale, boolean useContacts, boolean forceReload, DictionaryInitCallback callback, String prefix) {
        if (BuildConfig.DEBUG) {
            String pipeline = prefix.equals("spellcheck_") ? "SC" : "IME";
            Log.d("PIPELINE", pipeline + ":dictLoader:init" +
                " requestedLocale=" + locale +
                " currentLocale=" + this.mDictionaryGroup.mLocale +
                " prefix=\"" + prefix + "\"" +
                " forceReload=" + forceReload +
                " useContacts=" + useContacts +
                " currentDictAvailable=" + isDictionaryReady());
        }
        boolean localeChanged = locale == null ? this.mDictionaryGroup.mLocale != null : !locale.equals(this.mDictionaryGroup.mLocale);
        Set<Locale> activeLocales = SubtypeManager.getInstance().getCurrentSubtypeAdditionalLocales();
        boolean localesChanged = activeLocales == null ? this.mDictionaryGroup.mAdditionalLocales != null : !activeLocales.equals(this.mDictionaryGroup.mAdditionalLocales);
        Dictionary existingDict = this.mDictionaryGroup.mMainDictionary;
        boolean needsReload = localeChanged || localesChanged || forceReload || (existingDict != null && !existingDict.isInitialized());
        Dictionary keptDict = needsReload ? null : existingDict;
        // A reload records the additional locales it was started for, not null. With null, every
        // init before the async updateAdditionalLocales landed saw a change and reloaded again.
        // updateAdditionalLocales clears the record if the engine refuses them, so the next init
        // still retries.
        DictionaryGroup newGroup = new DictionaryGroup(locale, keptDict, activeLocales);
        synchronized (this.mLock) {
            DictionaryGroup oldGroup = this.mDictionaryGroup;
            this.mDictionaryGroup = newGroup;
            if (needsReload) {
                if (prefix.equals("spellcheck_")) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "creating a new spellchecker main dictionary");
                    this.mDictionaryGroup.mMainDictionary = DictionaryFactory.createDictionary(context, locale, true);
                    if (BuildConfig.DEBUG) {
                        Log.d("PIPELINE", "SC:dictLoader:created" +
                            " locale=" + locale +
                            " dictType=" + (getMainDictionary() != null ? getMainDictionary().getClass().getSimpleName() : "null") +
                            " available=" + isDictionaryReady());
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.i(TAG, "creating a new predictions main dictionary");
                    loadDictionaryAsync(context, locale, callback, oldGroup);
                    if (BuildConfig.DEBUG) {
                        Log.d("PIPELINE", "IME:dictLoader:asyncInitStarted" +
                            " locale=" + locale);
                    }
                }
            }
        }
        return needsReload;
    }

    private void loadDictionaryAsync(final Context context, final Locale locale, final DictionaryInitCallback callback, final DictionaryGroup oldGroup) {
        ThreadUtils.getBackgroundExecutor("InitializeBinaryDictionary").execute(() -> {
            oldGroup.closeMainDictionary();
            Logger.info(TAG, "Initializing main dictionary");
            Dictionary newDict = DictionaryFactory.createDictionary(context, locale);
            synchronized (this.mLock) {
                // Install only if no later init (or closeAndReset) has moved the locale on. A null
                // locale is a real request (the factory answers it with FallbackDictionary); the
                // original non-null requirement discarded that fallback on every load.
                DictionaryGroup current = this.mDictionaryGroup;
                if (!current.mRefusesPendingLoads && Objects.equals(current.mLocale, locale)) {
                    // Two loads for the same locale can both be pending (e.g. two forced reloads).
                    // Each closes only the group current when IT was queued, so the later install
                    // would otherwise replace the earlier one's dictionary without closing it.
                    Dictionary replaced = current.mMainDictionary;
                    current.mMainDictionary = newDict;
                    if (replaced != null && replaced != newDict) {
                        replaced.close();
                    }
                } else {
                    newDict.close();
                }
            }
            if (callback != null) {
                callback.onDictionaryInitialized(isDictionaryReady());
            }
        });
    }

    public boolean updateAdditionalLocales(final Set<Locale> locales, boolean async, final DictionaryInitCallback callback) {
        if (locales == null) {
            return false;
        }
        if (async) {
            ThreadUtils.getBackgroundExecutor("InitializeBinaryDictionary").execute(
                () -> updateAdditionalLocales(locales, false, callback));
        } else {
            Dictionary mainDict = getMainDictionary();
            if (mainDict == null || !mainDict.isInitialized()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "updateAdditionalLocales() needs an initialized main dictionary");
                return false;
            }
            if (mainDict.addLocales(locales)) {
                this.mDictionaryGroup.mAdditionalLocales = locales;
                if (callback != null) {
                    callback.onLocalesUpdated();
                }
                return true;
            }
            // Refused: forget the locales a reload recorded when it started, so the next init sees
            // them as changed and reloads (the retry the old null record gave implicitly).
            this.mDictionaryGroup.mAdditionalLocales = null;
        }
        return false;
    }

    public void closeAndReset() {
        DictionaryGroup oldGroup;
        synchronized (this.mLock) {
            oldGroup = this.mDictionaryGroup;
            this.mDictionaryGroup = new DictionaryGroup();
        }
        oldGroup.closeMainDictionary();
    }

    public Dictionary getMainDictionary() {
        return this.mDictionaryGroup.mMainDictionary;
    }

    public boolean isDictionaryReady() {
        Dictionary dict = this.mDictionaryGroup.mMainDictionary;
        return dict != null && dict.isInitialized();
    }

    public SuggestionResult generateSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, int sessionId) {
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions;
        DictionaryGroup group = this.mDictionaryGroup;
        SuggestionResult perfLogger = new SuggestionResult(group.mLocale, SuggestedWords.getMaxSuggestionCount(), prevWordsInfo.wordInfos[0].isBeginningOfSentence);
        Dictionary dict = group.mMainDictionary;
        if (dict != null && !ResourceLocaleUtils.isNoLanguage(group.mLocale) && (suggestions = dict.generateSuggestions(composingTracker, prevWordsInfo, stripSettings, sessionId)) != null) {
            perfLogger.addSuggestions(suggestions);
            if (BuildConfig.DEBUG) {
                String composing = composingTracker.isComposing() ? composingTracker.getComposingText() : "(not composing)";
                ArrayList<String> words = new ArrayList<>();
                for (int j = 0; j < Math.min(suggestions.size(), 5); j++) {
                    words.add(suggestions.get(j).word);
                }
                Log.d("PIPELINE", "dictLoader:suggestionsGenerated" +
                    " dictName=main" +
                    " dictType=" + dict.getClass().getSimpleName() +
                    " locale=" + group.mLocale +
                    " composing=\"" + composing + "\"" +
                    " resultCount=" + suggestions.size() +
                    " top5=" + words +
                    " thread=" + Thread.currentThread().getName());
            }
        } else if (BuildConfig.DEBUG) {
            boolean localeBlocked = ResourceLocaleUtils.isNoLanguage(group.mLocale);
            Log.d("PIPELINE", "dictLoader:suggestionsSkipped" +
                " dictName=main" +
                " dictNull=" + (dict == null) +
                " localeBlocked=" + localeBlocked +
                " locale=" + group.mLocale);
        }
        return perfLogger;
    }

    public boolean isWordValid(String word, boolean caseInsensitive) {
        if (TextUtils.isEmpty(word)) {
            return false;
        }
        DictionaryGroup group = this.mDictionaryGroup;
        if (group.mLocale == null) {
            return false;
        }
        String lowerCase = word.toLowerCase(group.mLocale);
        Dictionary dict = group.mMainDictionary;
        if (dict != null) {
            if (dict.isValidWord(word)) {
                if (BuildConfig.DEBUG) {
                    Log.d("PIPELINE", "dictLoader:wordCheck" +
                        " word=\"" + word + "\"" +
                        " caseInsensitive=" + caseInsensitive +
                        " result=true matchType=exact" +
                        " locale=" + group.mLocale);
                }
                return true;
            }
            if (caseInsensitive && dict.isValidWord(lowerCase)) {
                if (BuildConfig.DEBUG) {
                    Log.d("PIPELINE", "dictLoader:wordCheck" +
                        " word=\"" + word + "\"" +
                        " caseInsensitive=" + caseInsensitive +
                        " result=true matchType=lowercased(\"" + lowerCase + "\")" +
                        " locale=" + group.mLocale);
                }
                return true;
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d("PIPELINE", "dictLoader:wordCheck" +
                " word=\"" + word + "\"" +
                " caseInsensitive=" + caseInsensitive +
                " result=false" +
                " locale=" + group.mLocale +
                " dictAvailable=" + isDictionaryReady());
        }
        return false;
    }

}
