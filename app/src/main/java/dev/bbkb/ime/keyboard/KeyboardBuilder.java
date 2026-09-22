package dev.bbkb.ime.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
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
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import dev.bbkb.ime.BuildConfig;



public final class KeyboardBuilder {

    private static final String TAG = "KeyboardBuilder";

    private static boolean sLowerRightCornerIsEnter = false;

    private static final HashMap<KeyboardId, SoftReference<Keyboard>> sKeyboardCache = new HashMap<>();

    private static final UniqueKeysCache sKeysCache = new UniqueKeysCache();

    private final Context mContext;

    private final Params mParams;

    private final SharedPreferences mPrefs;

    /** Custom-symbol page index from KeyboardState; misnamed by the decompiler as a theme id. */
    private int mCustomSymbolPage = 0;

    
    public static final class ElementParams {

        int mKeyboardXmlId;

        boolean mProximityCharsCorrectionEnabled;
    }

    
    public static final class Params {

        String mKeyboardLayoutSetName;

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

        final SparseArray<ElementParams> mMoreKeySpecIdToParamsMap = new SparseArray<>();
    }

    
    public static final class KeyboardLayoutSetException extends RuntimeException {

        public final KeyboardId mKeyboardId;

        public KeyboardLayoutSetException(Throwable th, KeyboardId c0977g) {
            super(th);
            this.mKeyboardId = c0977g;
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
        sKeyboardCache.clear();
        sKeysCache.clear();
    }

    KeyboardBuilder(Context context, Params dVar) {
        this.mContext = context;
        this.mParams = dVar;
        this.mPrefs = PrefsManager.INSTANCE.getPrefs(this.mContext);
    }

    public Keyboard getKeyboard(int i) {
        return getKeyboardForShift(i, false);
    }

    public Keyboard getKeyboardForShift(int i, boolean z) {
        return getKeyboardInternal(i, z);
    }

    /**
     * The PKB element that replaces a requested element in url/email, postal and password fields
     * on a physical keyboard, or 0 when {@code mode} has no alias. Symbol pages (5..8) and the
     * unified input menu (38) are exempt — see {@link #getKeyboardInternal}.
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
     * Resolves (input mode, requested element, pkb) to the element id recorded on the
     * {@link KeyboardId} and the layout set's {@code <Element>} it is built from.
     *
     * @param pkb selects the physical-keyboard family (+100), not "shifted"
     */
    public Keyboard getKeyboardInternal(int requested, boolean pkb) {
        final int mode = this.mParams.mMode;
        final int element;
        if (mode == 4) {
            // Phone: everything is `phone`, except symbol page 0 -> `phoneSymbols`, which also
            // drops the pkb flag, so `phoneSymbolsPkb` (110) is unreachable (audit §6 #15, kept).
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

    private Keyboard buildKeyboard(ElementParams bVar, KeyboardId c0977g) {
        // Add null check to prevent NPE
        if (bVar == null) {
            throw new IllegalStateException("No keyboard layout found for configuration: " + c0977g);
        }
        
        Keyboard c0965e;
        boolean zM6720l = c0977g.isTypingKeyboard();
        SoftReference<Keyboard> softReference = zM6720l ? sKeyboardCache.get(c0977g) : null;
        if (softReference != null && (c0965e = softReference.get()) != null) {
            if ((c0977g.mElementId == 8 || c0977g.mElementId == 108) && this.mCustomSymbolPage != 0) {
                applySymbolList(c0965e, loadCustomSymbolList(c0977g.mElementId == 108),
                        this.mCustomSymbolPage);
            }
            return c0965e;
        }
        KeyboardXMLParser c1022ae = new KeyboardXMLParser(this.mContext, new KeyboardParams());
        if (c0977g.isAlphabetKeyboard()) {
            c1022ae.setUniqueKeysCache(sKeysCache);
        }
        c1022ae.load(bVar.mKeyboardXmlId, c0977g);
        c1022ae.setAllowRedundantMoreKeys(bVar.mProximityCharsCorrectionEnabled);
        Keyboard c0965eMo5338b = c1022ae.build();
        if (zM6720l) {
            sKeyboardCache.put(c0977g, new SoftReference<>(c0965eMo5338b));
        }
        if (c0977g.mElementId == 8 || c0977g.mElementId == 108) {
            applySymbolList(c0965eMo5338b, loadCustomSymbolList(c0977g.mElementId == 108),
                    this.mCustomSymbolPage);
        }
        return c0965eMo5338b;
    }


    private List<String> loadCustomSymbolList(boolean physicalKeyboard) {
        return CustomSymbolRepository.loadSymbolList(
                physicalKeyboard ? "pref_pkb_symbol_page_layout" : "pref_vkb_symbol_page_layout",
                this.mPrefs);
    }

    /**
     * Retitles this keyboard's custom-symbol keys from {@code labels}, in layout order.
     *
     * <p>Single implementation for all three entry points (initial build, cache-hit refresh and
     * the explicit list overload): they had drifted, and only one of them carried the
     * label-equality guard — without it every custom key was replaced on every page open, and
     * {@link Keyboard#replaceKey} rebuilds the whole proximity grid per call.
     *
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

    private Key createCustomKey(Key key, String str, boolean z, Keyboard c0965e) {
        Key key2 = new Key(new MoreKeySpec(str, z, c0965e.mId.mLocale), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, key.getHintLabel(), 0, 1, key.getX(), key.getY(), key.getWidth() + c0965e.mHorizontalGap, key.getHeight() + c0965e.mVerticalGap, c0965e.mHorizontalGap, c0965e.mVerticalGap, "customSymbolKeyStyle", getMoreKeysForCustomKey(str, !z, c0965e.mId.mLocale), null, key.getMoreKeysFlags(), 8, key.getScanCode());
        if (str.equals("\u0000")) {
            key2.setActive(false);
        }
        return key2;
    }

    private MoreKeySpec[] getMoreKeysForCustomKey(String str, boolean z, Locale locale) {
        char[] charArray = str.toCharArray();
        if (charArray.length == 0) {
            return null;
        }
        if (charArray.length > 1) {
            return getEmojiVariantMoreKeys(str, locale);
        }
        if (Character.isLetter(charArray[0])) {
            return new MoreKeySpec[]{new MoreKeySpec(str, z, locale)};
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
     * Long-press variants for a custom symbol key that holds an emoji, using the same
     * source as the emoji board (EmojiKeyboardFactory): the skin-tone variants from the
     * emojibase data set. The base emoji stays on the key; the popup lists only the
     * variants. Returns null for non-emoji strings or emojis without variants, and only
     * loads the (shared, cached) emoji data set when an emoji key is actually present.
     */
    private MoreKeySpec[] getEmojiVariantMoreKeys(String str, Locale locale) {
        if (!EmojiTextAnalyzer.isEmoji(str)) {
            return null;
        }
        List<String> variants = EmojibaseDataProvider.getSharedLoaded(this.mContext).getSkinToneVariants(str);
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
     * The script id this layout set declares via {@code supportedScript} (latin = 14, han = 7,
     * thai = 20, ...) — NOT a more-keys column count and NOT a {@link KeyboardId} element id.
     * It feeds word-character classification in {@code RichInputConnection.getWordRangeAtCursor}.
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
            editorInfo = editorInfo == null ? EMPTY_EDITOR_INFO : editorInfo;
            dVar.mMode = getKeyboardMode(editorInfo);
            dVar.mEditorInfo = editorInfo;
            dVar.mIsPasswordField = InputTypeUtils.isPasswordInputType(editorInfo.inputType);
            dVar.mClobberSettingsKey = EditorCapabilities.hasPrivateImeOption(this.mPackageName, "noSettingsKey", editorInfo);
        }

        public Builder setKeyboardGeometry(int i, int i2) {
            Params dVar = this.mParams;
            dVar.mKeyboardWidth = i;
            dVar.mKeyboardHeight = i2;
            return this;
        }

        public Builder setSubtype(InputMethodSubtype inputMethodSubtype) {
            boolean zM3930a = InputMethodSubtypeCompat.isAsciiCapable(inputMethodSubtype);
            if (((this.mParams.mEditorInfo.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) != 0 || EditorCapabilities.hasPrivateImeOption(this.mPackageName, "forceAscii", this.mParams.mEditorInfo)) && !zM3930a) {
                inputMethodSubtype = SubtypeManager.getInstance().getNoLanguageSubtype();
            }
            Params dVar = this.mParams;
            dVar.mSubtype = inputMethodSubtype;
            dVar.mKeyboardLayoutSetName = "keyboard_layout_set_" + ResourceLocaleUtils.getKeyboardLayoutSetName(inputMethodSubtype);
            return this;
        }

        public Builder setSplitLayoutEnabled(boolean z) {
            this.mParams.mIsSplitLayout = z;
            return this;
        }

        public Builder setVoiceInputKeyEnabled(boolean z) {
            this.mParams.mVoiceKeyEnabled = z;
            return this;
        }

        public Builder setLanguageSwitchKeyEnabled(boolean z) {
            this.mParams.mLanguageSwitchKeyEnabled = z;
            return this;
        }

        public Builder setLanguageQuickSwitchKeyEnabled(boolean z) {
            this.mParams.mLanguageQuickSwitchKeyEnabled = z;
            return this;
        }

        public Builder setInputBoardBarEnabled(boolean z) {
            this.mParams.mInputBoardBarEnabled = z;
            return this;
        }


        public void setSupportedScript(int i) {
            this.mParams.mSupportedScriptId = i;
        }

        public KeyboardBuilder build() throws Resources.NotFoundException {
            if (this.mParams.mSubtype == null) {
                throw new RuntimeException("KeyboardLayoutSet subtype is not specified");
            }
            String resourcePackageName = this.mResources.getResourcePackageName(R.xml.keyboard_layout_set_qwerty);
            String str = this.mParams.mKeyboardLayoutSetName;
            try {
                readKeyboardLayoutSet(this.mResources, this.mResources.getIdentifier(str, "xml", resourcePackageName));
                return new KeyboardBuilder(this.mContext, this.mParams);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage() + " in " + str, e);
            } catch (XmlPullParserException e2) {
                throw new RuntimeException(e2.getMessage() + " in " + str, e2);
            }
        }

        private void readKeyboardLayoutSet(Resources resources, int i) throws Resources.NotFoundException, IOException, XmlPullParserException {
            // Apply device-specific layout overrides if configured
            int resolvedResourceId = DeviceProfile.resolveLayoutResource(resources, i);
            
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

    private void updateLanguageQuickSwitchKey(int i) {
        if (i != 38) {
            switch (i) {
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

    /**
     * Create a simple single-row keyboard from a list of character labels.
     * Used by accent bar and similar auxiliary bars that need dynamic keyboards.
     *
     * @param context Context for resources
     * @param labels List of character labels for keys
     * @param keyHeight Height of keys in pixels
     * @return A Keyboard with keys laid out in a single row
     *
     * <p>There is no page-navigation key. A former {@code includePageNav} flag appended one built
     * from an empty spec, which MoreKeySpec rejects, so it always threw; nothing in the current
     * aux bar pages or handles code -2 (the original APK's paging lived in its old AuxBarView).
     */
    public static Keyboard createFromLabels(Context context, List<CharSequence> labels,
            int keyHeight) {
        
        int keyCount = labels.size();
        if (keyCount == 0) return null;
        
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int keyWidth = screenWidth / keyCount;
        
        // Create keyboard params
        // Note: mOccupiedHeight (from mOccupiedHeight) and mOccupiedWidth (from mOccupiedWidth) are used in KeyboardView.onMeasure
        KeyboardParams params = new KeyboardParams();
        params.mBaseWidth = screenWidth;  // keyboard width
        params.mOccupiedHeight = keyHeight;    // mHeight - used by KeyboardView.onMeasure for height!
        params.mOccupiedWidth = screenWidth;  // mWidth - used by KeyboardView.onMeasure for width
        params.mBaseHeight = keyHeight;    // mBaseHeight
        params.mHorizontalGap = 0;            // horizontal gap
        params.mVerticalGap = 0;            // vertical gap
        params.mMaxMoreKeysKeyboardColumn = keyCount;     // max more keys column
        params.mGridWidth = keyWidth;     // grid width for ProximityGrid
        params.mGridHeight = keyHeight;    // grid height for ProximityGrid
        params.mMostCommonKeyHeight = keyHeight;    // key visual height (mMostCommonKeyHeight in Keyboard)
        params.mMostCommonKeyWidth = keyHeight;    // key visual height 2 (mMostCommonKeyWidth in Keyboard)
        
        // Create a minimal KeyboardId with required EditorInfo
        Params dVar = new Params();
        dVar.mKeyboardWidth = screenWidth;
        dVar.mKeyboardHeight = keyHeight;
        dVar.mEditorInfo = new EditorInfo(); // Required - KeyboardId accesses actionLabel
        params.mId = new KeyboardId(0, dVar);
        
        // Create keys
        int x = 0;
        Locale locale = Locale.getDefault();
        
        for (int i = 0; i < labels.size(); i++) {
            CharSequence label = labels.get(i);
            // Result unused, but the read stays: it is what rejects an empty label (throws).
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
