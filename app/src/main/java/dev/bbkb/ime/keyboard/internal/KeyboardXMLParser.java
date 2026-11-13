package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.core.locale.SubtypeDisplayNameHelper;
import dev.bbkb.ime.core.shared.XmlParseUtils;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import dev.bbkb.ime.BuildConfig;



public class KeyboardXMLParser<KP extends KeyboardParams> {

    protected final KP mParams;

    protected final Context mContext;

    protected final Resources mResources;

    private boolean mLeftEdge;

    private boolean mTopEdge;

    private SubtypeDisplayNameHelper mSubtypeDisplayNameHelper;

    private int mCurrentY = 0;

    private KeyboardRow mCurrentRow = null;

    private Key mRightEdgeKey = null;

    public KeyboardXMLParser(Context context, KP kp) {
        this.mContext = context;
        Resources resources = context.getResources();
        this.mResources = resources;
        this.mParams = kp;
        kp.mGridWidth = resources.getInteger(R.integer.config_keyboard_grid_width);
        kp.mGridHeight = resources.getInteger(R.integer.config_keyboard_grid_height);
        this.mSubtypeDisplayNameHelper = new SubtypeDisplayNameHelper(context);
    }

    /** @param uniqueKeysCache the key interning cache */
    public void setUniqueKeysCache(UniqueKeysCache uniqueKeysCache) {
        this.mParams.mTextTable = uniqueKeysCache;
    }

    private String getResourceName(int resId) {
        try {
            return this.mResources.getResourceName(resId);
        } catch (Resources.NotFoundException unused) {
            return "File not found";
        }
    }

    /**
     * @param xmlId keyboard XML resource id
     * @param keyboardId id of the keyboard being built
     */
    public KeyboardXMLParser<KP> load(int xmlId, KeyboardId keyboardId) {
        this.mParams.mId = keyboardId;

        int resolvedResourceId = DeviceProfile.resolveLayoutResource(this.mResources, xmlId);
        
        XmlResourceParser xml = this.mResources.getXml(resolvedResourceId);
        try {
            try {
                try {
                    parseKeyboard(xml);
                    return this;
                } catch (IOException e) {
                    if (BuildConfig.DEBUG) Log.w("Keyboard.Builder", "keyboard XML parse error. xmlId: " + Integer.toString(xmlId) + ", file: " + getResourceName(xmlId), e);
                    throw new RuntimeException(e.getMessage(), e);
                }
            } catch (XmlPullParserException e2) {
                if (BuildConfig.DEBUG) Log.w("Keyboard.Builder", "keyboard XML parse error. xmlId: " + Integer.toString(xmlId) + ", file: " + getResourceName(xmlId), e2);
                throw new IllegalArgumentException(e2.getMessage(), e2);
            }
        } finally {
            xml.close();
        }
    }

    /** @param enabled true if the keyboard allows gesture input on letter keys (read by Keyboard.allowsGestureForCode) */
    public void setAllowRedundantMoreKeys(boolean enabled) {
        this.mParams.mAllowRedundantMoreKeys = enabled;
    }

    public Keyboard build() {
        return new Keyboard(this.mParams);
    }

    private void parseKeyboard(XmlPullParser xmlPullParser) throws XmlPullParserException, Resources.NotFoundException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            if (xmlPullParser.next() == 2) {
                String name = xmlPullParser.getName();
                if ("Keyboard".equals(name)) {
                    parseKeyboardAttributes(xmlPullParser);
                    startKeyboard();
                    parseKeyboardContent(xmlPullParser, false);
                    return;
                }
                throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "Keyboard");
            }
        }
    }

    private void parseKeyboardAttributes(XmlPullParser xmlPullParser) {
        AttributeSet attributeSetAsAttributeSet = Xml.asAttributeSet(xmlPullParser);
        TypedArray typedArrayObtainStyledAttributes = this.mContext.obtainStyledAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard);
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Key);
        try {
            KP kp = this.mParams;
            int i = kp.mId.mHeight;
            int i2 = kp.mId.mWidth;
            kp.mOccupiedHeight = i;
            kp.mOccupiedWidth = i2;
            kp.mTopPadding = (int) typedArrayObtainStyledAttributes.getFraction(R.styleable.Keyboard_keyboardTopPadding, i, i, 0.0f);
            kp.mBottomPadding = (int) typedArrayObtainStyledAttributes.getFraction(R.styleable.Keyboard_keyboardBottomPadding, i, i, 0.0f);
            kp.mLeftPadding = (int) typedArrayObtainStyledAttributes.getFraction(R.styleable.Keyboard_keyboardLeftPadding, i2, i2, 0.0f);
            kp.mRightPadding = (int) typedArrayObtainStyledAttributes.getFraction(R.styleable.Keyboard_keyboardRightPadding, i2, i2, 0.0f);
            int i3 = (kp.mOccupiedWidth - kp.mLeftPadding) - kp.mRightPadding;
            kp.mBaseWidth = i3;
            kp.mDefaultKeyWidth = (int) typedArrayObtainAttributes.getFraction(R.styleable.Keyboard_Key_keyWidth, i3, i3, i3 / 10);
            kp.mDefaultSpacerWidth = (int) typedArrayObtainAttributes.getFraction(R.styleable.Keyboard_Key_spacerWidth, i3, i3, -1.0f);
            kp.mHorizontalGap = (int) typedArrayObtainStyledAttributes.getFraction(R.styleable.Keyboard_horizontalGap, i3, i3, 0.0f);
            kp.mVerticalGap = (int) typedArrayObtainStyledAttributes.getFraction(R.styleable.Keyboard_verticalGap, i, i, 0.0f);
            int i4 = ((kp.mOccupiedHeight - kp.mTopPadding) - kp.mBottomPadding) + kp.mVerticalGap;
            kp.mBaseHeight = i4;
            kp.mDefaultRowHeight = (int) ResourceConfigManager.getFractionOrDimensionOrDefault(typedArrayObtainStyledAttributes, R.styleable.Keyboard_rowHeight, i4, i4 / 4);
            kp.mMoreKeySpec = KeyVisualAttributes.createIfHasStyleAttributes(typedArrayObtainAttributes);
            kp.mMoreKeysTemplate = typedArrayObtainStyledAttributes.getResourceId(R.styleable.Keyboard_moreKeysTemplate, 0);
            kp.mMaxMoreKeysKeyboardColumn = typedArrayObtainAttributes.getInt(R.styleable.Keyboard_Key_maxMoreKeysColumn, 5);
            kp.mIconsSet.loadIcons(typedArrayObtainStyledAttributes, this.mContext.getTheme());
            kp.mTextsSet.setLocales(kp.mId.mLocale, ResourceLocaleUtils.getAdditionalLocales(kp.mId.mSubtype), this.mContext);
        } finally {
            typedArrayObtainAttributes.recycle();
            typedArrayObtainStyledAttributes.recycle();
        }
    }

    private void parseKeyboardContent(XmlPullParser xmlPullParser, boolean skip) throws XmlPullParserException, Resources.NotFoundException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("Row".equals(name)) {
                    KeyboardRow c1027ajM7142c = parseRowAttributes(xmlPullParser);
                    if (!skip) {
                        startRow(c1027ajM7142c);
                    }
                    parseRowContent(xmlPullParser, c1027ajM7142c, skip);
                } else if ("include".equals(name)) {
                    parseIncludeKeyboardContent(xmlPullParser, skip);
                } else if ("switch".equals(name)) {
                    parseSwitchKeyboardContent(xmlPullParser, skip);
                } else if ("key-style".equals(name)) {
                    parseKeyStyle(xmlPullParser, skip);
                } else {
                    throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "Row");
                }
            } else if (next == 3) {
                String name2 = xmlPullParser.getName();
                if ("Keyboard".equals(name2)) {
                    endKeyboard();
                    return;
                } else {
                    if (!"case".equals(name2) && !"default".equals(name2) && !"merge".equals(name2)) {
                        throw new XmlParseUtils.IllegalEndTag(xmlPullParser, name2, "Row");
                    }
                    return;
                }
            }
        }
    }

    private KeyboardRow parseRowAttributes(XmlPullParser xmlPullParser) throws XmlParseUtils.IllegalAttribute {
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard);
        try {
            boolean hasHorizontalGap = typedArrayObtainAttributes.hasValue(R.styleable.Keyboard_horizontalGap);
            if (hasHorizontalGap) {
                throw new XmlParseUtils.IllegalAttribute(xmlPullParser, "Row", "horizontalGap");
            }
            boolean hasVerticalGap = typedArrayObtainAttributes.hasValue(R.styleable.Keyboard_verticalGap);
            if (hasVerticalGap) {
                throw new XmlParseUtils.IllegalAttribute(xmlPullParser, "Row", "verticalGap");
            }
            return new KeyboardRow(this.mResources, this.mParams, xmlPullParser, this.mCurrentY);
        } finally {
            typedArrayObtainAttributes.recycle();
        }
    }

    private void parseRowContent(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlPullParserException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("Key".equals(name)) {
                    parseKey(xmlPullParser, keyboardRow, skip);
                } else if ("Spacer".equals(name)) {
                    parseSpacer(xmlPullParser, keyboardRow, skip);
                } else if ("include".equals(name)) {
                    parseIncludeRowContent(xmlPullParser, keyboardRow, skip);
                } else if ("switch".equals(name)) {
                    parseSwitchRowContent(xmlPullParser, keyboardRow, skip);
                } else if ("key-style".equals(name)) {
                    parseKeyStyle(xmlPullParser, skip);
                } else {
                    throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "Row");
                }
            } else if (next == 3) {
                String name2 = xmlPullParser.getName();
                if ("Row".equals(name2)) {
                    if (skip) {
                        return;
                    }
                    endRow(keyboardRow);
                    return;
                } else {
                    if (!"case".equals(name2) && !"default".equals(name2) && !"merge".equals(name2)) {
                        throw new XmlParseUtils.IllegalEndTag(xmlPullParser, name2, "Row");
                    }
                    return;
                }
            }
        }
    }

    private void parseKey(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlParseUtils.ParseException {
        if (skip) {
            XmlParseUtils.checkEndTag("Key", xmlPullParser);
            return;
        }
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_Key);
        KeyStyle abstractC1067zM7091a = this.mParams.mKeyStyles.getKeyStyle(typedArrayObtainAttributes, xmlPullParser);
        String strMo7100b = abstractC1067zM7091a.getString(typedArrayObtainAttributes, R.styleable.Keyboard_Key_keySpec);
        if (TextUtils.isEmpty(strMo7100b)) {
            throw new XmlParseUtils.ParseException("Empty keySpec", xmlPullParser);
        }
        String strMo7100b2 = abstractC1067zM7091a.getString(typedArrayObtainAttributes, R.styleable.Keyboard_Key_secondaryKeySpec);
        KeyHintPosition enumC0963cM6586a = KeyHintPosition.fromValue(abstractC1067zM7091a.getInt(typedArrayObtainAttributes, R.styleable.Keyboard_Key_secondaryFunctionDisplayStyle, KeyHintPosition.HIDDEN.getValue()));
        String strM7450a = KeySpecParser.getLabel(strMo7100b);
        Key key = new Key((strM7450a == null || !strM7450a.toLowerCase(java.util.Locale.ROOT).equals("lang_code")) ? strMo7100b : strMo7100b.replace("lang_code", this.mSubtypeDisplayNameHelper.getSpaceKeyLabel(ResourceLocaleUtils.getSubtypeLocale(this.mParams.mId.mSubtype), ResourceLocaleUtils.getAdditionalLocales(this.mParams.mId.mSubtype))), strMo7100b2, enumC0963cM6586a, typedArrayObtainAttributes, abstractC1067zM7091a, this.mParams, keyboardRow);
        typedArrayObtainAttributes.recycle();
        XmlParseUtils.checkEndTag("Key", xmlPullParser);
        endKey(key);
    }

    private void parseSpacer(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlParseUtils.NonEmptyTag, XmlParseUtils.ParseException {
        if (skip) {
            XmlParseUtils.checkEndTag("Spacer", xmlPullParser);
            return;
        }
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_Key);
        Key.SpacerKey c0943c = new Key.SpacerKey(typedArrayObtainAttributes, this.mParams.mKeyStyles.getKeyStyle(typedArrayObtainAttributes, xmlPullParser), this.mParams, keyboardRow);
        typedArrayObtainAttributes.recycle();
        XmlParseUtils.checkEndTag("Spacer", xmlPullParser);
        endKey(c0943c);
    }

    private void parseIncludeKeyboardContent(XmlPullParser xmlPullParser, boolean skip) throws XmlParseUtils.NonEmptyTag, Resources.NotFoundException, IOException, XmlPullParserException {
        parseKeyStyleForKey(xmlPullParser, null, skip);
    }

    private void parseIncludeRowContent(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlParseUtils.NonEmptyTag, Resources.NotFoundException, IOException, XmlPullParserException {
        parseKeyStyleForKey(xmlPullParser, keyboardRow, skip);
    }

    private void parseKeyStyleForKey(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlParseUtils.NonEmptyTag, Resources.NotFoundException, IOException, XmlPullParserException {
        if (skip) {
            XmlParseUtils.checkEndTag("include", xmlPullParser);
            return;
        }
        AttributeSet attributeSetAsAttributeSet = Xml.asAttributeSet(xmlPullParser);
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Include);
        TypedArray typedArrayObtainAttributes2 = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Key);
        try {
            XmlParseUtils.checkAttributeExists(typedArrayObtainAttributes, 0, "keyboardLayout", "include", xmlPullParser);
            int resourceId = typedArrayObtainAttributes.getResourceId(R.styleable.Keyboard_Include_keyboardLayout, 0);
            if (keyboardRow != null) {
                keyboardRow.setXPos(keyboardRow.getKeyX(typedArrayObtainAttributes2));
                keyboardRow.pushRowAttributes(typedArrayObtainAttributes2);
            }
            XmlParseUtils.checkEndTag("include", xmlPullParser);

            int resolvedIncludeId = DeviceProfile.resolveLayoutResource(this.mResources, resourceId);
            
            XmlResourceParser xml = this.mResources.getXml(resolvedIncludeId);
            try {
                parseKeyContent(xml, keyboardRow, skip);
            } finally {
                if (keyboardRow != null) {
                    keyboardRow.popRowAttributes();
                }
                xml.close();
            }
        } finally {
            typedArrayObtainAttributes.recycle();
            typedArrayObtainAttributes2.recycle();
        }
    }

    private void parseKeyContent(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlPullParserException, Resources.NotFoundException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            if (xmlPullParser.next() == 2) {
                if (!"merge".equals(xmlPullParser.getName())) {
                    throw new XmlParseUtils.ParseException("Included keyboard layout must have <merge> root element", xmlPullParser);
                }
                if (keyboardRow == null) {
                    parseKeyboardContent(xmlPullParser, skip);
                    return;
                } else {
                    parseRowContent(xmlPullParser, keyboardRow, skip);
                    return;
                }
            }
        }
    }

    private void parseSwitchKeyboardContent(XmlPullParser xmlPullParser, boolean skip) throws XmlPullParserException, IOException {
        parseSwitchInternal(xmlPullParser, (KeyboardRow) null, skip);
    }

    private void parseSwitchRowContent(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlPullParserException, IOException {
        parseSwitchInternal(xmlPullParser, keyboardRow, skip);
    }

    private void parseSwitchInternal(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlPullParserException, IOException {
        boolean zM7141b = false;
        while (true) {
            if (xmlPullParser.getEventType() == 1) {
                return;
            }
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("case".equals(name)) {
                    zM7141b |= parseCase(xmlPullParser, keyboardRow, zM7141b ? true : skip);
                } else if ("default".equals(name)) {
                    zM7141b |= parseDefault(xmlPullParser, keyboardRow, zM7141b ? true : skip);
                } else {
                    throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "switch");
                }
            } else if (next == 3) {
                String name2 = xmlPullParser.getName();
                if (!"switch".equals(name2)) {
                    throw new XmlParseUtils.IllegalEndTag(xmlPullParser, name2, "switch");
                }
                return;
            }
        }
    }

    private boolean parseCase(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlPullParserException, Resources.NotFoundException, IOException {
        boolean zM7150d = matchCaseConditions(xmlPullParser);
        if (!zM7150d) {
            skip = true;
        }
        if (keyboardRow == null) {
            parseKeyboardContent(xmlPullParser, skip);
        } else {
            parseRowContent(xmlPullParser, keyboardRow, skip);
        }
        return zM7150d;
    }

    private boolean matchCaseConditions(XmlPullParser xmlPullParser) {
        KeyboardId c0977g = this.mParams.mId;
        if (c0977g == null) {
            return true;
        }
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_Case);
        try {
            // Keyboard_Case_isFormFillAllowed (used by kbd_unified_input_menu_pkb.xml) has no
            // check or backing state, so such a case always matches.
            return matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_keyboardLayoutSet, c0977g.keyboardLayoutSet())
                    && matchTypedValue(typedArrayObtainAttributes, R.styleable.Keyboard_Case_keyboardLayoutSetElement, c0977g.mElementId, KeyboardId.elementIdToName(c0977g.mElementId))
                    && matchTypedValue(typedArrayObtainAttributes, R.styleable.Keyboard_Case_mode, c0977g.mMode, KeyboardId.modeName(c0977g.mMode))
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_navigateNext, c0977g.navigateNext())
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_navigatePrevious, c0977g.navigatePrevious())
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_passwordInput, c0977g.passwordInput())
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_clobberSettingsKey, c0977g.mClobberSettingsKey)
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_voiceInputKeyEnabled, c0977g.mVoiceKeyEnabled)
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_languageSwitchKeyEnabled, c0977g.mLanguageSwitchKeyEnabled)
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_languageQuickSwitchKeyEnabled, c0977g.mLanguageQuickSwitchKeyEnabled)
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_inputBoardBarKeyEnabled, c0977g.mInputBoardBarEnabled)
                    && matchBoolean(typedArrayObtainAttributes, R.styleable.Keyboard_Case_isMultiLine, c0977g.isMultiLine())
                    && matchInteger(typedArrayObtainAttributes, R.styleable.Keyboard_Case_imeAction, c0977g.imeAction())
                    && matchIcon(typedArrayObtainAttributes, R.styleable.Keyboard_Case_isIconDefined, this.mParams.mIconsSet)
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_localeCode, LocaleUtils.localeString(c0977g.mLocale))
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_languageCode, LocaleUtils.languageCode(c0977g.mLocale))
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_countryCode, c0977g.mLocale.getCountry())
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_physicalKeypadVariant, DeviceProfile.current().getKeypadLayout())
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_physicalKeypadType, DeviceProfile.current().getEffectiveKeypadType());
        } finally {
            typedArrayObtainAttributes.recycle();
        }
    }

    private static boolean matchInteger(TypedArray typedArray, int index, int value) {
        return !typedArray.hasValue(index) || typedArray.getInt(index, 0) == value;
    }

    private static boolean matchBoolean(TypedArray typedArray, int index, boolean value) {
        return !typedArray.hasValue(index) || typedArray.getBoolean(index, false) == value;
    }

    /** Matches a "|"-separated attribute value; an absent attribute matches. */
    private static boolean matchString(TypedArray typedArray, int index, String value) {
        return !typedArray.hasValue(index) || splitContains(typedArray.getString(index), value);
    }

    /** Null-safe: a non-string attribute value makes {@code getString} return null. */
    private static boolean splitContains(String value, String target) {
        if (value == null) {
            return false;
        }
        for (String candidate : value.split("\\|")) {
            if (candidate.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchTypedValue(TypedArray typedArray, int index, int intValue, String strValue) {
        TypedValue typedValuePeekValue = typedArray.peekValue(index);
        if (typedValuePeekValue == null) {
            return true;
        }
        if (ResourceConfigManager.isIntegerType(typedValuePeekValue)) {
            return intValue == typedArray.getInt(index, 0);
        }
        // String values match by name against a "|"-separated list, like matchString.
        if (ResourceConfigManager.isStringType(typedValuePeekValue)) {
            return splitContains(typedArray.getString(index), strValue);
        }
        return false;
    }

    private static boolean matchIcon(TypedArray typedArray, int index, KeyboardIconSet keyboardIconSet) {
        return (typedArray.hasValue(index) && keyboardIconSet.getIconDrawable(KeyboardIconSet.getIconId(typedArray.getString(index))) == null) ? false : true;
    }

    private boolean parseDefault(XmlPullParser xmlPullParser, KeyboardRow keyboardRow, boolean skip) throws XmlPullParserException, Resources.NotFoundException, IOException {
        if (keyboardRow == null) {
            parseKeyboardContent(xmlPullParser, skip);
        } else {
            parseRowContent(xmlPullParser, keyboardRow, skip);
        }
        return true;
    }

    private void parseKeyStyle(XmlPullParser xmlPullParser, boolean skip) throws XmlParseUtils.NonEmptyTag, XmlParseUtils.ParseException {
        AttributeSet attributeSetAsAttributeSet = Xml.asAttributeSet(xmlPullParser);
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_KeyStyle);
        TypedArray typedArrayObtainAttributes2 = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Key);
        try {
            boolean hasStyleName = typedArrayObtainAttributes.hasValue(R.styleable.Keyboard_KeyStyle_styleName);
            if (!hasStyleName) {
                throw new XmlParseUtils.ParseException("<key-style/> needs styleName attribute", xmlPullParser);
            }
            if (!skip) {
                this.mParams.mKeyStyles.parseKeyStyleAttributes(typedArrayObtainAttributes, typedArrayObtainAttributes2, xmlPullParser);
            }
            typedArrayObtainAttributes.recycle();
            typedArrayObtainAttributes2.recycle();
            XmlParseUtils.checkEndTag("key-style", xmlPullParser);
        } catch (Throwable th) {
            typedArrayObtainAttributes.recycle();
            typedArrayObtainAttributes2.recycle();
            throw th;
        }
    }

    private void startKeyboard() {
        this.mCurrentY += this.mParams.mTopPadding;
        this.mTopEdge = true;
    }

    private void startRow(KeyboardRow keyboardRow) {
        addEdgeSpace(this.mParams.mLeftPadding, keyboardRow);
        this.mCurrentRow = keyboardRow;
        this.mLeftEdge = true;
        this.mRightEdgeKey = null;
    }

    private void endRow(KeyboardRow keyboardRow) {
        if (this.mCurrentRow == null) {
            throw new RuntimeException("orphan end row tag");
        }
        Key key = this.mRightEdgeKey;
        if (key != null) {
            key.setRightEdge(this.mParams);
            this.mRightEdgeKey = null;
        }
        addEdgeSpace(this.mParams.mRightPadding, keyboardRow);
        this.mCurrentY += keyboardRow.getRowHeight();
        this.mCurrentRow = null;
        this.mTopEdge = false;
    }

    private void endKey(Key key) {
        this.mParams.onAddKey(key);
        if (this.mLeftEdge) {
            key.setLeftEdge(this.mParams);
            this.mLeftEdge = false;
        }
        if (this.mTopEdge) {
            key.setTopEdge(this.mParams);
        }
        this.mRightEdgeKey = key;
    }

    private void endKeyboard() {
        int i = this.mCurrentY - this.mParams.mVerticalGap;
        if (i <= 0) {
            this.mParams.mOccupiedHeight = 0;
        } else {
            KP kp = this.mParams;
            kp.mOccupiedHeight = i + kp.mBottomPadding;
        }
    }

    private void addEdgeSpace(float width, KeyboardRow keyboardRow) {
        keyboardRow.advanceXPos(width);
        this.mLeftEdge = false;
        this.mRightEdgeKey = null;
    }
}
