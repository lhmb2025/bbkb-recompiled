package dev.bbkb.ime.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.Xml;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.KeyboardId;
import dev.bbkb.ime.R;
import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository;
import dev.bbkb.ime.core.layout.LetterLayoutChoice;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.shared.EmojiTextAnalyzer;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;
import dev.bbkb.ime.core.shared.XmlParseUtils;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.KeyboardParams;
import dev.bbkb.ime.keyboard.internal.UniqueKeysCache;
import dev.bbkb.ime.keyboard.internal.KeyboardXMLParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import dev.bbkb.ime.BuildConfig;



public final class KeyboardBuilder {

    private static final String TAG = "KeyboardBuilder";

    /** A layout set's XML resource is this prefix and its name: {@code keyboard_layout_set_qwerty}. */
    static final String LAYOUT_SET_PREFIX = "keyboard_layout_set_";

    private static boolean sLowerRightCornerIsEnter = false;

    /** Built keyboards kept between fields; see {@link KeyboardCacheKey} for what tells them apart. */
    private static final int KEYBOARD_CACHE_SIZE = 48;

    /** LRU, bounded, soft values; entries of collected keyboards are purged ({@link #purgeClearedKeyboards}). */
    private static final LinkedHashMap<KeyboardCacheKey, KeyboardReference> sKeyboardCache =
            new LinkedHashMap<KeyboardCacheKey, KeyboardReference>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<KeyboardCacheKey, KeyboardReference> eldest) {
                    return size() > KEYBOARD_CACHE_SIZE;
                }
            };

    private static final ReferenceQueue<Keyboard> sClearedKeyboards = new ReferenceQueue<>();

    /** Parsed layout sets, by resolved XML resource id; a handful at most (one per keyboard). */
    private static final int LAYOUT_SET_CACHE_SIZE = 8;

    private static final LinkedHashMap<Integer, ParsedLayoutSet> sLayoutSetCache =
            new LinkedHashMap<Integer, ParsedLayoutSet>(LAYOUT_SET_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, ParsedLayoutSet> eldest) {
                    return size() > LAYOUT_SET_CACHE_SIZE;
                }
            };

    /** {@code getIdentifier} results for layout-set names; the resource table never changes. */
    private static final HashMap<String, Integer> sLayoutSetIds = new HashMap<>();

    private static final UniqueKeysCache sKeysCache = new UniqueKeysCache();

    private final Context mContext;

    private final Params mParams;

    private final SharedPreferences mPrefs;

    /** Custom-symbol page index from KeyboardState. */
    private int mCustomSymbolPage = 0;

    
    public static final class ElementParams {

        int mKeyboardXmlId;

        boolean mProximityCharsCorrectionEnabled;
    }

    
    public static final class Params {
        String mKeyboardLayoutSetName;

        /**
         * Layout set to build from ({@code qwerty}, {@code colemak}), or null. Usually the subtype's own;
         * a letter layout chosen for the language can replace it ({@link Builder#setKeyboardLayoutSet}).
         */
        public String keyboardLayoutSet() {
            final String name = mKeyboardLayoutSetName;
            return name != null && name.startsWith(LAYOUT_SET_PREFIX) ? name.substring(LAYOUT_SET_PREFIX.length()) : null;
        }

        public int mMode;

        public EditorInfo mEditorInfo;

        boolean mIsPasswordField;

        public boolean mVoiceKeyEnabled;

        public boolean mClobberSettingsKey;

        public boolean mLanguageSwitchKeyEnabled;

        public boolean mLanguageQuickSwitchKeyEnabled;

        public boolean mInputBoardBarEnabled;

        public InputMethodSubtype mSubtype;

        boolean mIsSplitLayout;

        public int mKeyboardWidth;

        public int mKeyboardHeight;

        /** The layout set's {@code supportedScript} attribute (res/values/attrs.xml); latin = 14. */
        int mSupportedScriptId = 14;

        /** Shared with every builder of the same layout set (sLayoutSetCache); read only. */
        SparseArray<ElementParams> mMoreKeySpecIdToParamsMap = new SparseArray<>();
    }

    
    public static final class KeyboardLayoutSetException extends RuntimeException {

        public final KeyboardId mKeyboardId;

        /** @param keyboardId the id of the keyboard that failed to build */
        public KeyboardLayoutSetException(Throwable cause, KeyboardId keyboardId) {
            super(cause);
            this.mKeyboardId = keyboardId;
        }
    }

    public static void clearKeyboardCache() {
        clearKeyboardCacheInternal();
    }

    public static void onSystemLocaleChanged() {
        clearKeyboardCacheInternal();
    }

    public static void onKeyboardThemeChanged() {
        clearKeyboardCacheInternal();
    }

    private static void clearKeyboardCacheInternal() {
        synchronized (sKeyboardCache) {
            sKeyboardCache.clear();
            while (sClearedKeyboards.poll() != null) {
                // Drained only: their entries went with the map.
            }
        }
        sKeysCache.clear();
    }

    /** Removes the entries whose keyboards the garbage collector has taken. Call under the lock. */
    private static void purgeClearedKeyboards() {
        Reference<? extends Keyboard> cleared;
        while ((cleared = sClearedKeyboards.poll()) != null) {
            final KeyboardReference ref = (KeyboardReference) cleared;
            // Only if the entry still holds this reference: a rebuild may have replaced it.
            if (sKeyboardCache.get(ref.key) == ref) {
                sKeyboardCache.remove(ref.key);
            }
        }
    }

    /** Number of cached keyboards, for tests. */
    static int keyboardCacheSizeForTest() {
        synchronized (sKeyboardCache) {
            purgeClearedKeyboards();
            return sKeyboardCache.size();
        }
    }

    /** Number of parsed layout sets kept, for tests. */
    static int layoutSetCacheSizeForTest() {
        synchronized (sLayoutSetCache) {
            return sLayoutSetCache.size();
        }
    }

    /** Drops the parsed layout sets, for tests. */
    static void clearLayoutSetCacheForTest() {
        synchronized (sLayoutSetCache) {
            sLayoutSetCache.clear();
        }
        synchronized (sLayoutSetIds) {
            sLayoutSetIds.clear();
        }
    }

    private static final class KeyboardReference extends SoftReference<Keyboard> {

        final KeyboardCacheKey key;

        KeyboardReference(KeyboardCacheKey key, Keyboard keyboard) {
            super(keyboard, sClearedKeyboards);
            this.key = key;
        }
    }

    /**
     * The fields {@link KeyboardId#equals} compares, copied so the cache key does not pin the
     * first field's {@link EditorInfo} (extras, surrounding text) for the process lifetime.
     */
    static final class KeyboardCacheKey {

        private final int elementId;
        private final int mode;
        private final int width;
        private final int height;
        private final boolean passwordInput;
        private final boolean clobberSettingsKey;
        private final boolean voiceKeyEnabled;
        private final boolean languageSwitchKeyEnabled;
        private final boolean languageQuickSwitchKeyEnabled;
        private final boolean inputBoardBarEnabled;
        private final boolean multiLine;
        private final int imeAction;
        private final String customActionLabel;
        private final boolean navigateNext;
        private final boolean navigatePrevious;
        private final InputMethodSubtype subtype;
        private final String keyboardLayoutSet;
        private final int hashCode;

        KeyboardCacheKey(KeyboardId id) {
            this.elementId = id.mElementId;
            this.mode = id.mMode;
            this.width = id.mWidth;
            this.height = id.mHeight;
            this.passwordInput = id.passwordInput();
            this.clobberSettingsKey = id.mClobberSettingsKey;
            this.voiceKeyEnabled = id.mVoiceKeyEnabled;
            this.languageSwitchKeyEnabled = id.mLanguageSwitchKeyEnabled;
            this.languageQuickSwitchKeyEnabled = id.mLanguageQuickSwitchKeyEnabled;
            this.inputBoardBarEnabled = id.mInputBoardBarEnabled;
            this.multiLine = id.isMultiLine();
            this.imeAction = id.imeAction();
            this.customActionLabel = id.mCustomActionLabel;
            this.navigateNext = id.navigateNext();
            this.navigatePrevious = id.navigatePrevious();
            this.subtype = id.mSubtype;
            this.keyboardLayoutSet = id.mKeyboardLayoutSet;
            this.hashCode = id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof KeyboardCacheKey)) {
                return false;
            }
            final KeyboardCacheKey o = (KeyboardCacheKey) obj;
            return o.elementId == elementId && o.mode == mode && o.width == width && o.height == height
                    && o.passwordInput == passwordInput && o.clobberSettingsKey == clobberSettingsKey
                    && o.voiceKeyEnabled == voiceKeyEnabled && o.languageSwitchKeyEnabled == languageSwitchKeyEnabled
                    && o.languageQuickSwitchKeyEnabled == languageQuickSwitchKeyEnabled
                    && o.inputBoardBarEnabled == inputBoardBarEnabled && o.multiLine == multiLine
                    && o.imeAction == imeAction && TextUtils.equals(o.customActionLabel, customActionLabel)
                    && o.navigateNext == navigateNext && o.navigatePrevious == navigatePrevious
                    && o.subtype.equals(subtype) && TextUtils.equals(o.keyboardLayoutSet, keyboardLayoutSet);
        }

        @Override
        public int hashCode() {
            // KeyboardId hashes exactly the fields compared above.
            return this.hashCode;
        }
    }

    /** One layout set's {@code <Element>}s and {@code <Feature>}, as parsed from its XML. Immutable once built. */
    private static final class ParsedLayoutSet {

        final SparseArray<ElementParams> elements;

        final int supportedScriptId;

        ParsedLayoutSet(SparseArray<ElementParams> elements, int supportedScriptId) {
            this.elements = elements;
            this.supportedScriptId = supportedScriptId;
        }
    }

    KeyboardBuilder(Context context, Params params) {
        this.mContext = context;
        this.mParams = params;
        this.mPrefs = PrefsManager.INSTANCE.getPrefs(this.mContext);
    }

    /** @param elementId the requested element id */
    public Keyboard getKeyboard(int elementId) {
        return getKeyboardForShift(elementId, false);
    }

    /**
     * @param elementId the requested element id
     * @param pkb true for the physical-keyboard family (pkb)
     */
    public Keyboard getKeyboardForShift(int elementId, boolean pkb) {
        return getKeyboardInternal(elementId, pkb);
    }

    /**
     * PKB element replacing the requested one in url/email, postal and password fields, or 0.
     * Symbol pages (5..8) and the input menu (38) are exempt, see {@link #getKeyboardInternal}.
     */
    private static int pkbAliasElement(int mode) {
        switch (mode) {
            case 1:  // url   -> uri
            case 2:  // email -> uri
                return 35;
            case 10: // postal
                return 37;
            case 11: // password
                return 36;
            default:
                return 0;
        }
    }

    /**
     * Resolves (input mode, element, pkb) to the {@link KeyboardId} element id and its {@code <Element>}.
     * @param pkb selects the physical-keyboard family (+100), not "shifted"
     */
    public Keyboard getKeyboardInternal(int requested, boolean pkb) {
        final int mode = this.mParams.mMode;
        final int element;
        if (mode == 4) {
            // Phone: everything is `phone`, except symbol page 0 -> `phoneSymbols`, which also
            // drops the pkb flag, so `phoneSymbolsPkb` (110) is unreachable (intended).
            element = requested == 5 ? 10 : 9;
            if (requested == 5) {
                pkb = false;
            }
        } else if (mode >= 5 && mode <= 8) {
            element = 11; // number / date / time / datetime
        } else {
            final int alias = pkbAliasElement(mode);
            final boolean exempt = requested == 38 || (requested >= 5 && requested <= 8);
            element = pkb && alias != 0 && !exempt ? alias : requested;
        }

        int id = pkb ? element + 100 : element;
        final SparseArray<ElementParams> declared = this.mParams.mMoreKeySpecIdToParamsMap;
        ElementParams params = declared.get(id);
        if (params == null && pkb) {
            // Fallback 1: alphabetPkb, recorded under the computed id.
            if (id != 100) {
                params = declared.get(100);
            }
            // Fallback 2: the on-screen element. The recorded id becomes that element even when
            // it misses too and fallback 3 supplies the layout.
            if (params == null) {
                params = declared.get(element);
                id = element;
            }
        }
        if (params == null) {
            params = declared.get(0); // Fallback 3: alphabet, recorded under the current id.
        }

        updateLanguageQuickSwitchKey(requested);
        KeyboardId keyboardId = new KeyboardId(id, this.mParams);
        if (params == null) {
            throw new KeyboardLayoutSetException(new IllegalStateException("Missing keyboard layout configuration"), keyboardId);
        }
        try {
            return buildKeyboard(params, keyboardId);
        } catch (RuntimeException e) {
            throw new KeyboardLayoutSetException(e, keyboardId);
        }
    }

    /**
     * @param elementParams the layout set element to build from
     * @param keyboardId the id the keyboard gets
     */
    private Keyboard buildKeyboard(ElementParams elementParams, KeyboardId keyboardId) {
        if (elementParams == null) {
            throw new IllegalStateException("No keyboard layout found for configuration: " + keyboardId);
        }
        
        Keyboard c0965e = null;
        boolean zM6720l = keyboardId.isTypingKeyboard();
        final KeyboardCacheKey cacheKey = zM6720l ? new KeyboardCacheKey(keyboardId) : null;
        if (cacheKey != null) {
            synchronized (sKeyboardCache) {
                purgeClearedKeyboards();
                final KeyboardReference softReference = sKeyboardCache.get(cacheKey);
                c0965e = softReference != null ? softReference.get() : null;
            }
        }
        if (c0965e != null) {
            if ((keyboardId.mElementId == 8 || keyboardId.mElementId == 108) && this.mCustomSymbolPage != 0) {
                applySymbolList(c0965e, loadCustomSymbolList(keyboardId.mElementId == 108),
                        this.mCustomSymbolPage);
            }
            return c0965e;
        }
        KeyboardXMLParser c1022ae = new KeyboardXMLParser(this.mContext, new KeyboardParams());
        if (keyboardId.isAlphabetKeyboard()) {
            c1022ae.setUniqueKeysCache(sKeysCache);
        }
        c1022ae.load(elementParams.mKeyboardXmlId, keyboardId);
        c1022ae.setAllowRedundantMoreKeys(elementParams.mProximityCharsCorrectionEnabled);
        Keyboard c0965eMo5338b = c1022ae.build();
        if (cacheKey != null) {
            synchronized (sKeyboardCache) {
                sKeyboardCache.put(cacheKey, new KeyboardReference(cacheKey, c0965eMo5338b));
            }
        }
        if (keyboardId.mElementId == 8 || keyboardId.mElementId == 108) {
            applySymbolList(c0965eMo5338b, loadCustomSymbolList(keyboardId.mElementId == 108),
                    this.mCustomSymbolPage);
        }
        return c0965eMo5338b;
    }


    /**
     * The stored custom symbol page in on-keyboard order (row by row). The on-screen page is stored
     * in a legacy order the settings editor maps through {@code vkbStorageToVisualOrder}; the same
     * mapping applies here so the drawn page matches the editor.
     */
    private List<String> loadCustomSymbolList(boolean physicalKeyboard) {
        final List<String> stored = CustomSymbolRepository.loadSymbolList(
                physicalKeyboard ? "pref_pkb_symbol_page_layout" : "pref_vkb_symbol_page_layout",
                this.mPrefs);
        return physicalKeyboard ? stored : CustomSymbolRepository.vkbStorageToVisualOrder(stored);
    }

    /** Puts {@code labels} on {@code keyboard}'s custom-symbol keys, row by row, for the editor preview. */
    public void applyCustomSymbols(Keyboard keyboard, List<String> labels) {
        applySymbolList(keyboard, labels, this.mCustomSymbolPage);
    }

    /**
     * Retitles the custom-symbol keys from {@code labels}, in layout order. Shared by all entry points;
     * the label-equality guard skips unchanged keys so a page open does not replace every key.
     * @param page the custom-symbol page index; page 1 is the letter page
     */
    private void applySymbolList(Keyboard keyboard, List<String> labels, int page) {
        if (labels == null) {
            return;
        }
        int index = 0;
        boolean replacedAny = false;
        for (Key key : keyboard.getKeys()) {
            String outputText = key.getOutputText();
            if (outputText == null || !outputText.equals("customSymbolKeyStyle")) {
                continue;
            }
            // A symbol list saved against a different layout can be shorter than this layout's
            // custom-key count; stop rather than throwing IndexOutOfBounds out of buildKeyboard.
            if (index >= labels.size()) {
                break;
            }
            String label = labels.get(index);
            index++;
            if (label.equals(key.getLabel())) {
                continue;
            }
            boolean letterKey = label.length() == 1 && Character.isLetter(label.charAt(0)) && page == 1;
            if (keyboard.replaceKey(key, createCustomKey(key, label, letterKey, keyboard))) {
                replacedAny = true;
            } else {
                if (BuildConfig.DEBUG) Log.e(TAG, "Cannot replace custom key: " + label + " on keyboard");
            }
        }
        if (replacedAny) {
            keyboard.rebuildProximityGrid();
        }
    }

    /**
     * @param label the symbol to put on the key
     * @param needsToUpperCase true for a letter on the letter page (upper-cased spec, no case long-press)
     * @param keyboard the keyboard the key belongs to
     */
    private Key createCustomKey(Key key, String label, boolean needsToUpperCase, Keyboard keyboard) {
        Key key2 = new Key(new MoreKeySpec(label, needsToUpperCase, keyboard.mId.mLocale), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, key.getHintLabel(), 0, 1, key.getX(), key.getY(), key.getWidth() + keyboard.mHorizontalGap, key.getHeight() + keyboard.mVerticalGap, keyboard.mHorizontalGap, keyboard.mVerticalGap, "customSymbolKeyStyle", getMoreKeysForCustomKey(label, !needsToUpperCase, keyboard.mId.mLocale), null, key.getMoreKeysFlags(), 8, key.getScanCode());
        if (label.equals("\u0000")) {
            key2.setActive(false);
        }
        return key2;
    }

    /**
     * @param label the key's symbol
     * @param needsToUpperCase true to upper-case a letter's long-press spec
     */
    private MoreKeySpec[] getMoreKeysForCustomKey(String label, boolean needsToUpperCase, Locale locale) {
        char[] charArray = label.toCharArray();
        if (charArray.length == 0) {
            return null;
        }
        if (charArray.length > 1) {
            return getEmojiVariantMoreKeys(label, locale);
        }
        if (Character.isLetter(charArray[0])) {
            return new MoreKeySpec[]{new MoreKeySpec(label, needsToUpperCase, locale)};
        }
        Keyboard c0965eM6734a = getKeyboardForShift(5, false);
        Key keyM6604b = c0965eM6734a.getKeyByCode(charArray[0]);
        if (keyM6604b != null) {
            return keyM6604b.getMoreKeys();
        }
        Key keyM6604b2 = getKeyboardForShift(6, false).getKeyByCode(charArray[0]);
        if (keyM6604b2 != null) {
            return keyM6604b2.getMoreKeys();
        }
        if (Arrays.asList(this.mContext.getResources().getStringArray(R.array.currency_array)).contains(String.valueOf(charArray[0]))) {
            return c0965eM6734a.getKeyByOutputText("currencyExtendedKeyStyle").getMoreKeys();
        }
        return null;
    }

    /**
     * Skin-tone variants (from emojibase, as on the emoji board) for a custom symbol key holding an
     * emoji, or null. The data set loads only when an emoji key is present.
     * @param emoji the key's text
     */
    private MoreKeySpec[] getEmojiVariantMoreKeys(String emoji, Locale locale) {
        if (!EmojiTextAnalyzer.isEmoji(emoji)) {
            return null;
        }
        List<String> variants = EmojibaseDataProvider.getSharedLoaded(this.mContext).getSkinToneVariants(emoji);
        if (variants.isEmpty()) {
            return null;
        }
        MoreKeySpec[] moreKeys = new MoreKeySpec[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            moreKeys[i] = new MoreKeySpec(variants.get(i), false, locale);
        }
        return moreKeys;
    }

    /**
     * The layout set's {@code supportedScript} id (latin = 14, han = 7, thai = 20, ...), used for
     * word-character classification in {@code RichInputConnection.getWordRangeAtCursor}.
     */
    public int getSupportedScriptId() {
        return this.mParams.mSupportedScriptId;
    }

    
    public static final class Builder {

        private static final EditorInfo EMPTY_EDITOR_INFO = new EditorInfo();

        private final Context mContext;

        private final String mPackageName;

        private final Resources mResources;

        private final Params mParams = new Params();

        public Builder(Context context, EditorInfo editorInfo) {
            this.mContext = context;
            this.mPackageName = context.getPackageName();
            this.mResources = context.getResources();
            Params dVar = this.mParams;
            boolean unused = KeyboardBuilder.sLowerRightCornerIsEnter = this.mResources.getBoolean(R.bool.lower_right_corner_is_enter);
            editorInfo = editorInfo == null ? EMPTY_EDITOR_INFO : keyboardFieldsOf(editorInfo);
            dVar.mMode = getKeyboardMode(editorInfo);
            dVar.mEditorInfo = editorInfo;
            dVar.mIsPasswordField = InputTypeUtils.isPasswordInputType(editorInfo.inputType);
            dVar.mClobberSettingsKey = EditorCapabilities.hasPrivateImeOption(this.mPackageName, "noSettingsKey", editorInfo);
        }

        /**
         * Copies the fields a keyboard is built from: the keyboard and its cache keep this EditorInfo,
         * and the app's own carries extras and the text around the cursor.
         */
        static EditorInfo keyboardFieldsOf(EditorInfo editorInfo) {
            final EditorInfo copy = new EditorInfo();
            copy.inputType = editorInfo.inputType;
            copy.imeOptions = editorInfo.imeOptions;
            copy.privateImeOptions = editorInfo.privateImeOptions;
            copy.actionLabel = editorInfo.actionLabel != null ? editorInfo.actionLabel.toString() : null;
            copy.actionId = editorInfo.actionId;
            copy.packageName = editorInfo.packageName;
            copy.fieldId = editorInfo.fieldId;
            return copy;
        }

        /**
         * @param keyboardWidth keyboard width in pixels
         * @param keyboardHeight keyboard height in pixels
         */
        public Builder setKeyboardGeometry(int keyboardWidth, int keyboardHeight) {
            Params dVar = this.mParams;
            dVar.mKeyboardWidth = keyboardWidth;
            dVar.mKeyboardHeight = keyboardHeight;
            return this;
        }

        public Builder setSubtype(InputMethodSubtype inputMethodSubtype) {
            boolean zM3930a = InputMethodSubtypeCompat.isAsciiCapable(inputMethodSubtype);
            if (((this.mParams.mEditorInfo.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) != 0 || EditorCapabilities.hasPrivateImeOption(this.mPackageName, "forceAscii", this.mParams.mEditorInfo)) && !zM3930a) {
                inputMethodSubtype = SubtypeManager.getInstance().getNoLanguageSubtype();
            }
            Params dVar = this.mParams;
            dVar.mSubtype = inputMethodSubtype;
            dVar.mKeyboardLayoutSetName = LAYOUT_SET_PREFIX + ResourceLocaleUtils.getKeyboardLayoutSetName(inputMethodSubtype);
            return this;
        }

        /**
         * Builds the keyboard from the layout set [layoutSet] instead of the subtype's own. The
         * subtype still decides everything else: language, dictionaries, the keyboard's identity.
         */
        public Builder setKeyboardLayoutSet(String layoutSet) {
            this.mParams.mKeyboardLayoutSetName = LAYOUT_SET_PREFIX + layoutSet;
            return this;
        }

        /**
         * Uses the language's chosen letter layout when it has on-screen rows
         * ({@link LetterLayoutChoice#keyboardLayoutSet}). Typing keyboard only. Call after
         * {@link #setSubtype}, so a field that forces the no-language keyboard gets that choice.
         */
        public Builder useChosenLetterLayout() {
            final InputMethodSubtype subtype = this.mParams.mSubtype;
            if (subtype != null) {
                setKeyboardLayoutSet(LetterLayoutChoice.keyboardLayoutSet(this.mContext, subtype));
            }
            return this;
        }

        /** @param enabled true to enable the split layout */
        public Builder setSplitLayoutEnabled(boolean enabled) {
            this.mParams.mIsSplitLayout = enabled;
            return this;
        }

        /** @param enabled true to show the voice input key */
        public Builder setVoiceInputKeyEnabled(boolean enabled) {
            this.mParams.mVoiceKeyEnabled = enabled;
            return this;
        }

        /** @param enabled true to show the language switch key */
        public Builder setLanguageSwitchKeyEnabled(boolean enabled) {
            this.mParams.mLanguageSwitchKeyEnabled = enabled;
            return this;
        }

        /** @param enabled true to show the language quick switch key */
        public Builder setLanguageQuickSwitchKeyEnabled(boolean enabled) {
            this.mParams.mLanguageQuickSwitchKeyEnabled = enabled;
            return this;
        }

        /** @param enabled true to show the input board bar */
        public Builder setInputBoardBarEnabled(boolean enabled) {
            this.mParams.mInputBoardBarEnabled = enabled;
            return this;
        }

        /** @param scriptId the script id (latin = 14) */
        public void setSupportedScript(int scriptId) {
            this.mParams.mSupportedScriptId = scriptId;
        }

        public KeyboardBuilder build() throws Resources.NotFoundException {
            if (this.mParams.mSubtype == null) {
                throw new RuntimeException("KeyboardLayoutSet subtype is not specified");
            }
            String str = this.mParams.mKeyboardLayoutSetName;
            try {
                // Parsed layout sets are shared between builders so a field focus does not reparse.
                final int resolvedId = DeviceProfile.resolveLayoutResource(this.mResources, layoutSetXmlId(str));
                ParsedLayoutSet parsed;
                synchronized (sLayoutSetCache) {
                    parsed = sLayoutSetCache.get(resolvedId);
                }
                if (parsed == null) {
                    readKeyboardLayoutSet(this.mResources, resolvedId);
                    parsed = new ParsedLayoutSet(this.mParams.mMoreKeySpecIdToParamsMap, this.mParams.mSupportedScriptId);
                    synchronized (sLayoutSetCache) {
                        sLayoutSetCache.put(resolvedId, parsed);
                    }
                }
                this.mParams.mMoreKeySpecIdToParamsMap = parsed.elements;
                this.mParams.mSupportedScriptId = parsed.supportedScriptId;
                return new KeyboardBuilder(this.mContext, this.mParams);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage() + " in " + str, e);
            } catch (XmlPullParserException e2) {
                throw new RuntimeException(e2.getMessage() + " in " + str, e2);
            }
        }

        /** The XML id of the layout set named {@code name}, 0 when there is none. */
        private int layoutSetXmlId(String name) {
            synchronized (sLayoutSetIds) {
                final Integer known = sLayoutSetIds.get(name);
                if (known != null) {
                    return known;
                }
            }
            final String resourcePackageName = this.mResources.getResourcePackageName(R.xml.keyboard_layout_set_qwerty);
            final int id = this.mResources.getIdentifier(name, "xml", resourcePackageName);
            synchronized (sLayoutSetIds) {
                sLayoutSetIds.put(name, id);
            }
            return id;
        }

        /** Parses layout set {@code resolvedResourceId} (device overrides already applied) into mParams. */
        private void readKeyboardLayoutSet(Resources resources, int resolvedResourceId) throws Resources.NotFoundException, IOException, XmlPullParserException {
            XmlResourceParser xml = resources.getXml(resolvedResourceId);
            try {
                while (xml.getEventType() != 1) {
                    if (xml.next() == 2) {
                        String name = xml.getName();
                        if ("KeyboardLayoutSet".equals(name)) {
                            parseKeyboardLayoutSet(xml);
                        } else {
                            throw new XmlParseUtils.IllegalStartTag(xml, name, "KeyboardLayoutSet");
                        }
                    }
                }
            } finally {
                xml.close();
            }
        }

        private void parseKeyboardLayoutSet(XmlPullParser xmlPullParser) throws XmlPullParserException, IOException, XmlParseUtils.ParseException, XmlParseUtils.NonEmptyTag, XmlParseUtils.IllegalStartTag, XmlParseUtils.IllegalEndTag {
            while (xmlPullParser.getEventType() != 1) {
                int next = xmlPullParser.next();
                if (next == 2) {
                    String name = xmlPullParser.getName();
                    if ("Element".equals(name)) {
                        parseMoreKeySpec(xmlPullParser);
                    } else if ("Feature".equals(name)) {
                        parseKeyboardLayoutSetFeature(xmlPullParser);
                    } else {
                        throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "KeyboardLayoutSet");
                    }
                } else if (next == 3) {
                    String name2 = xmlPullParser.getName();
                    if (!"KeyboardLayoutSet".equals(name2)) {
                        throw new XmlParseUtils.IllegalEndTag(xmlPullParser, name2, "KeyboardLayoutSet");
                    }
                    return;
                }
            }
        }

        private void parseMoreKeySpec(XmlPullParser xmlPullParser) throws XmlParseUtils.ParseException, XmlParseUtils.NonEmptyTag {
            TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.KeyboardLayoutSet_Element);
            try {
                XmlParseUtils.checkAttributeExists(typedArrayObtainAttributes, R.styleable.KeyboardLayoutSet_Element_elementName, "elementName", "Element", xmlPullParser);
                XmlParseUtils.checkAttributeExists(typedArrayObtainAttributes, R.styleable.KeyboardLayoutSet_Element_elementKeyboard, "elementKeyboard", "Element", xmlPullParser);
                XmlParseUtils.checkEndTag("Element", xmlPullParser);
                ElementParams bVar = new ElementParams();
                int i = typedArrayObtainAttributes.getInt(R.styleable.KeyboardLayoutSet_Element_elementName, 0);
                int elementKeyboard = typedArrayObtainAttributes.getResourceId(R.styleable.KeyboardLayoutSet_Element_elementKeyboard, 0);
                bVar.mKeyboardXmlId = elementKeyboard;
                boolean enabled = typedArrayObtainAttributes.getBoolean(R.styleable.KeyboardLayoutSet_Element_enableProximityCharsCorrection, false);
                bVar.mProximityCharsCorrectionEnabled = enabled;
                this.mParams.mMoreKeySpecIdToParamsMap.put(i, bVar);
            } finally {
                typedArrayObtainAttributes.recycle();
            }
        }

        private void parseKeyboardLayoutSetFeature(XmlPullParser xmlPullParser) throws XmlParseUtils.NonEmptyTag {
            TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.KeyboardLayoutSet_Feature);
            try {
                int i = typedArrayObtainAttributes.getInt(R.styleable.KeyboardLayoutSet_Feature_supportedScript, 14);
                XmlParseUtils.checkEndTag("Feature", xmlPullParser);
                setSupportedScript(i);
            } finally {
                typedArrayObtainAttributes.recycle();
            }
        }

        private static int getKeyboardMode(EditorInfo editorInfo) {
            int i = editorInfo.inputType;
            int i2 = i & 4080;
            switch (i & 15) {
                case 1:
                    if (InputTypeUtils.isEmailVariation(i2)) {
                        return 2;
                    }
                    if (i2 == 16) {
                        return 1;
                    }
                    if (i2 == 64) {
                        return KeyboardBuilder.sLowerRightCornerIsEnter ? 9 : 3;
                    }
                    if (i2 == 176) {
                        return 0;
                    }
                    if (i2 == 112) {
                        return 10;
                    }
                    return (InputTypeUtils.isPasswordInputType(i) || InputTypeUtils.isVisiblePasswordInputType(i)) ? 11 : 0;
                case 2:
                    return 5;
                case 3:
                    return 4;
                case 4:
                    if (i2 != 16) {
                        return i2 != 32 ? 8 : 7;
                    }
                    return 6;
                default:
                    return 0;
            }
        }
    }

    public boolean hasPkbLayout() {
        return this.mParams.mMoreKeySpecIdToParamsMap.get(100) != null;
    }

    public void setCustomSymbolPage(int page) {
        this.mCustomSymbolPage = page;
    }

    /** @param elementId the element id being built */
    private void updateLanguageQuickSwitchKey(int elementId) {
        if (elementId != 38) {
            switch (elementId) {
                case 5:
                case 6:
                case 7:
                case 8:
                    this.mParams.mLanguageQuickSwitchKeyEnabled = false;
                    return;
                default:
                    this.mParams.mLanguageQuickSwitchKeyEnabled = SettingsManager.isLanguageQuickSwitchEnabled();
                    return;
            }
        }
        this.mParams.mLanguageQuickSwitchKeyEnabled = false;
    }

    /** A single-row keyboard of {@code labels}, for the accent bar and similar aux bars (no paging key). */
    public static Keyboard createFromLabels(Context context, List<CharSequence> labels,
            int keyHeight) {
        
        int keyCount = labels.size();
        if (keyCount == 0) return null;
        
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int keyWidth = screenWidth / keyCount;

        // mOccupiedHeight/mOccupiedWidth size the view in KeyboardView.onMeasure.
        KeyboardParams params = new KeyboardParams();
        params.mBaseWidth = screenWidth;
        params.mOccupiedHeight = keyHeight;
        params.mOccupiedWidth = screenWidth;
        params.mBaseHeight = keyHeight;
        params.mHorizontalGap = 0;
        params.mVerticalGap = 0;
        params.mMaxMoreKeysKeyboardColumn = keyCount;
        params.mGridWidth = keyWidth;
        params.mGridHeight = keyHeight;
        params.mMostCommonKeyHeight = keyHeight;
        params.mMostCommonKeyWidth = keyHeight;

        Params dVar = new Params();
        dVar.mKeyboardWidth = screenWidth;
        dVar.mKeyboardHeight = keyHeight;
        dVar.mEditorInfo = new EditorInfo(); // KeyboardId reads actionLabel
        params.mId = new KeyboardId(0, dVar);

        int x = 0;
        Locale locale = Locale.getDefault();
        
        for (int i = 0; i < labels.size(); i++) {
            CharSequence label = labels.get(i);
            // Result unused: the read rejects an empty label by throwing.
            Character.codePointAt(label, 0);
            params.mSortedKeys.add(singleRowKey(label.toString(), x, keyWidth, keyHeight, locale));
            x += keyWidth;
        }
        return new Keyboard(params);
    }

    /**
     * One key of a {@link #createFromLabels} row. Background type 1 = normal. Width is passed
     * as-is, not x + width: the hit box adds x itself.
     */
    private static Key singleRowKey(String label, int x, int width, int height, Locale locale) {
        return new Key(new MoreKeySpec(label, false, locale), MoreKeySpec.getEmpty(),
                KeyHintPosition.HIDDEN, null, 0, 1, x, 0, width, height, 0, 0,
                label, null, null, 0, 0, 0);
    }
}
