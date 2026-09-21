package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.core.locale.SubtypeDisplayNameHelper;
import dev.bbkb.ime.core.shared.XmlParseUtils;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    public void setUniqueKeysCache(UniqueKeysCache c1031an) {
        this.mParams.mTextTable = c1031an;
    }

    private String getResourceName(int i) {
        try {
            return this.mResources.getResourceName(i);
        } catch (Resources.NotFoundException unused) {
            return "File not found";
        }
    }

    public KeyboardXMLParser<KP> load(int i, KeyboardId c0977g) {
        this.mParams.mId = c0977g;
        
        // Apply device-specific layout overrides if configured
        int resolvedResourceId = DeviceProfile.resolveLayoutResource(this.mResources, i);
        
        XmlResourceParser xml = this.mResources.getXml(resolvedResourceId);
        try {
            try {
                try {
                    parseKeyboard(xml);
                    return this;
                } catch (IOException e) {
                    if (BuildConfig.DEBUG) Log.w("Keyboard.Builder", "keyboard XML parse error. xmlId: " + Integer.toString(i) + ", file: " + getResourceName(i), e);
                    throw new RuntimeException(e.getMessage(), e);
                }
            } catch (XmlPullParserException e2) {
                if (BuildConfig.DEBUG) Log.w("Keyboard.Builder", "keyboard XML parse error. xmlId: " + Integer.toString(i) + ", file: " + getResourceName(i), e2);
                throw new IllegalArgumentException(e2.getMessage(), e2);
            }
        } finally {
            xml.close();
        }
    }


    public void setAllowRedundantMoreKeys(boolean z) {
        this.mParams.mAllowRedundantMoreKeys = z;
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
            kp.mIconsSet.loadIcons(typedArrayObtainStyledAttributes);
            kp.mTextsSet.setLocales(kp.mId.mLocale, ResourceLocaleUtils.getAdditionalLocales(kp.mId.mSubtype), this.mContext);
        } finally {
            typedArrayObtainAttributes.recycle();
            typedArrayObtainStyledAttributes.recycle();
        }
    }

    private void parseKeyboardContent(XmlPullParser xmlPullParser, boolean z) throws XmlPullParserException, Resources.NotFoundException, IOException {
        boolean z2 = false;
        while (xmlPullParser.getEventType() != 1) {
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("Row".equals(name)) {
                    KeyboardRow c1027ajM7142c = parseRowAttributes(xmlPullParser);
                    if (!z) {
                        startRow(c1027ajM7142c);
                    }
                    parseRowContent(xmlPullParser, c1027ajM7142c, z);
                } else if ("GridRows".equals(name)) {
                    parseGridRows(xmlPullParser, z);
                    z2 = true;
                } else if ("include".equals(name)) {
                    parseIncludeKeyboardContent(xmlPullParser, z);
                } else if ("switch".equals(name)) {
                    parseSwitchKeyboardContent(xmlPullParser, z);
                } else if ("key-style".equals(name)) {
                    parseKeyStyle(xmlPullParser, z);
                } else if ("PhysicalKeyboardExtension".equals(name)) {
                    KP kp = this.mParams;
                    kp.mPhysicalKeyboardRow = new PhysicalKeySpecTable(kp);
                    parsePhysicalKeyboardExtensionContent(xmlPullParser, this.mParams.mPhysicalKeyboardRow, z);
                } else {
                    throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "Row");
                }
            } else if (next == 3) {
                String name2 = xmlPullParser.getName();
                if ("Keyboard".equals(name2)) {
                    if (z2) {
                        return;
                    }
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
            // Check for deprecated attributes that should not be used
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

    private void parseRowContent(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlPullParserException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("Key".equals(name)) {
                    parseKey(xmlPullParser, c1027aj, z);
                } else if ("Spacer".equals(name)) {
                    parseSpacer(xmlPullParser, c1027aj, z);
                } else if ("include".equals(name)) {
                    parseIncludeRowContent(xmlPullParser, c1027aj, z);
                } else if ("switch".equals(name)) {
                    parseSwitchRowContent(xmlPullParser, c1027aj, z);
                } else if ("key-style".equals(name)) {
                    parseKeyStyle(xmlPullParser, z);
                } else {
                    throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "Row");
                }
            } else if (next == 3) {
                String name2 = xmlPullParser.getName();
                if ("Row".equals(name2)) {
                    if (z) {
                        return;
                    }
                    endRow(c1027aj);
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

    private void parseGridRows(XmlPullParser xmlPullParser, boolean z) throws XmlParseUtils.ParseException, Resources.NotFoundException {
        String str;
        String[] strArr;
        int i;
        int i2;
        int i3;
        String string;
        int iM7346d;
        int i4;
        int i5;
        XmlPullParser xmlPullParser2 = xmlPullParser;
        if (z) {
            XmlParseUtils.checkEndTag("GridRows", xmlPullParser2);
            return;
        }
        KeyboardRow c1027aj = new KeyboardRow(this.mResources, this.mParams, xmlPullParser2, this.mCurrentY);
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_GridRows);
        int resourceId = typedArrayObtainAttributes.getResourceId(R.styleable.Keyboard_GridRows_codesArray, 0);
        int resourceId2 = typedArrayObtainAttributes.getResourceId(R.styleable.Keyboard_GridRows_textsArray, 0);
        int resourceId3 = typedArrayObtainAttributes.getResourceId(R.styleable.Keyboard_GridRows_moreKeysGrid, 0);
        typedArrayObtainAttributes.recycle();
        if (resourceId == 0 && resourceId2 == 0) {
            throw new XmlParseUtils.ParseException("Missing codesArray or textsArray attributes", xmlPullParser2);
        }
        if (resourceId != 0 && resourceId2 != 0) {
            throw new XmlParseUtils.ParseException("Both codesArray and textsArray attributes specifed", xmlPullParser2);
        }
        if (resourceId3 == 0) {
            throw new XmlParseUtils.ParseException("Missing moreKeysGrid attributes", xmlPullParser2);
        }
        String[] stringArray = this.mResources.getStringArray(resourceId != 0 ? resourceId : resourceId2);
        String[] stringArray2 = this.mResources.getStringArray(resourceId3);
        int length = stringArray.length;
        float fM7176a = c1027aj.getKeyWidth(null, 0.0f);
        int i6 = (int) (this.mParams.mOccupiedWidth / fM7176a);
        int i7 = 0;
        while (i7 < length) {
            KeyboardRow c1027aj2 = new KeyboardRow(this.mResources, this.mParams, xmlPullParser2, this.mCurrentY);
            startRow(c1027aj2);
            int i8 = 0;
            while (i8 < i6) {
                int i9 = i7 + i8;
                if (i9 >= length || i9 >= stringArray.length) {
                    break;
                }
                List arrayList = new ArrayList();
                if (resourceId != 0) {
                    String arrayElement = stringArray[i9];
                    if (arrayElement == null) {
                        i8++;
                        continue;
                    }
                    String strM7344b = KeySpecParser.getFirstSpec(arrayElement);
                    String strM7343a = KeySpecParser.decodeHexCodePoints(strM7344b);
                    int iM7347e = KeySpecParser.parseCode(strM7344b);
                    String strM7348f = KeySpecParser.parseOutputText(strM7344b);
                    str = strM7343a;
                    strArr = stringArray;
                    iM7346d = KeySpecParser.parseSpecFlags(strM7344b);
                    i = resourceId;
                    i3 = iM7347e;
                    i2 = length;
                    string = strM7348f;
                } else {
                    str = stringArray[i9];
                    strArr = stringArray;
                    StringBuilder sb = new StringBuilder();
                    sb.append(str);
                    i = resourceId;
                    sb.append(' ');
                    i2 = length;
                    i3 = -4;
                    string = sb.toString();
                    iM7346d = 0;
                }
                if (Build.VERSION.SDK_INT < iM7346d) {
                    i4 = resourceId2;
                    i5 = resourceId3;
                } else {
                    if (resourceId3 != 0) {
                        String str2 = stringArray2[i9];
                        if (resourceId2 == 0) {
                            arrayList = KeySpecParser.parseLabelList(str2);
                        } else {
                            arrayList.add(str2);
                        }
                    }
                    int iM7186e = c1027aj2.getDefaultKeyLabelFlags();
                    int iM7187f = c1027aj2.getDefaultBackgroundType();
                    int iM7180b = (int) c1027aj2.getKeyX((TypedArray) null);
                    int iM7188g = c1027aj2.getY();
                    int i10 = (int) fM7176a;
                    int iM7177a = c1027aj2.getRowHeight();
                    i4 = resourceId2;
                    MoreKeySpec c1065x = new MoreKeySpec(str, 0, i3, string);
                    MoreKeySpec[] c1034aqArr = new MoreKeySpec[arrayList.size()];
                    String[] strArr2 = (String[]) arrayList.toArray(new String[arrayList.size()]);
                    int i11 = 0;
                    while (i11 < strArr2.length) {
                        c1034aqArr[i11] = new MoreKeySpec(strArr2[i11], false, this.mParams.mId.mLocale);
                        i11++;
                    }
                    i5 = resourceId3;
                    endKey(new Key(c1065x, MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, iM7186e, iM7187f, iM7180b, iM7188g, i10, iM7177a, this.mParams.mHorizontalGap, this.mParams.mVerticalGap, null, c1034aqArr, null, this.mResources.getInteger(R.integer.more_key_flag_value_emoji), 2, -1));
                    c1027aj2.advanceXPos(fM7176a);
                }
                i8++;
                stringArray = strArr;
                length = i2;
                resourceId = i;
                resourceId2 = i4;
                resourceId3 = i5;
            }
            endRow(c1027aj2);
            i7 += i6;
            xmlPullParser2 = xmlPullParser;
        }
        XmlParseUtils.checkEndTag("GridRows", xmlPullParser);
    }

    private void parseKey(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlParseUtils.ParseException {
        if (z) {
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
        Key key = new Key((strM7450a == null || !strM7450a.toLowerCase(java.util.Locale.ROOT).equals("lang_code")) ? strMo7100b : strMo7100b.replace("lang_code", this.mSubtypeDisplayNameHelper.getSpaceKeyLabel(ResourceLocaleUtils.getSubtypeLocale(this.mParams.mId.mSubtype), ResourceLocaleUtils.getAdditionalLocales(this.mParams.mId.mSubtype))), strMo7100b2, enumC0963cM6586a, typedArrayObtainAttributes, abstractC1067zM7091a, this.mParams, c1027aj);
        typedArrayObtainAttributes.recycle();
        XmlParseUtils.checkEndTag("Key", xmlPullParser);
        endKey(key);
    }

    private void parseSpacer(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlParseUtils.NonEmptyTag, XmlParseUtils.ParseException {
        if (z) {
            XmlParseUtils.checkEndTag("Spacer", xmlPullParser);
            return;
        }
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_Key);
        Key.SpacerKey c0943c = new Key.SpacerKey(typedArrayObtainAttributes, this.mParams.mKeyStyles.getKeyStyle(typedArrayObtainAttributes, xmlPullParser), this.mParams, c1027aj);
        typedArrayObtainAttributes.recycle();
        XmlParseUtils.checkEndTag("Spacer", xmlPullParser);
        endKey(c0943c);
    }

    private void parseIncludeKeyboardContent(XmlPullParser xmlPullParser, boolean z) throws XmlParseUtils.NonEmptyTag, Resources.NotFoundException, IOException, XmlPullParserException {
        parseKeyStyleForKey(xmlPullParser, null, z);
    }

    private void parseIncludeRowContent(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlParseUtils.NonEmptyTag, Resources.NotFoundException, IOException, XmlPullParserException {
        parseKeyStyleForKey(xmlPullParser, c1027aj, z);
    }

    private void parseKeyStyleForKey(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlParseUtils.NonEmptyTag, Resources.NotFoundException, IOException, XmlPullParserException {
        if (z) {
            XmlParseUtils.checkEndTag("include", xmlPullParser);
            return;
        }
        AttributeSet attributeSetAsAttributeSet = Xml.asAttributeSet(xmlPullParser);
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Include);
        TypedArray typedArrayObtainAttributes2 = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Key);
        try {
            XmlParseUtils.checkAttributeExists(typedArrayObtainAttributes, 0, "keyboardLayout", "include", xmlPullParser);
            int resourceId = typedArrayObtainAttributes.getResourceId(R.styleable.Keyboard_Include_keyboardLayout, 0);
            if (c1027aj != null) {
                c1027aj.setXPos(c1027aj.getKeyX(typedArrayObtainAttributes2));
                c1027aj.pushRowAttributes(typedArrayObtainAttributes2);
            }
            XmlParseUtils.checkEndTag("include", xmlPullParser);
            
            // Apply device-specific layout overrides if configured
            int resolvedIncludeId = DeviceProfile.resolveLayoutResource(this.mResources, resourceId);
            
            XmlResourceParser xml = this.mResources.getXml(resolvedIncludeId);
            try {
                parseKeyContent(xml, c1027aj, z);
            } finally {
                if (c1027aj != null) {
                    c1027aj.popRowAttributes();
                }
                xml.close();
            }
        } finally {
            // Always recycle TypedArrays exactly once in finally block
            typedArrayObtainAttributes.recycle();
            typedArrayObtainAttributes2.recycle();
        }
    }

    private void parseKeyContent(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlPullParserException, Resources.NotFoundException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            if (xmlPullParser.next() == 2) {
                if (!"merge".equals(xmlPullParser.getName())) {
                    throw new XmlParseUtils.ParseException("Included keyboard layout must have <merge> root element", xmlPullParser);
                }
                if (c1027aj == null) {
                    parseKeyboardContent(xmlPullParser, z);
                    return;
                } else {
                    parseRowContent(xmlPullParser, c1027aj, z);
                    return;
                }
            }
        }
    }

    private void parseSwitchKeyboardContent(XmlPullParser xmlPullParser, boolean z) throws XmlPullParserException, IOException {
        parseSwitchInternal(xmlPullParser, (KeyboardRow) null, (PhysicalKeySpecTable) null, z);
    }

    private void parseSwitchRowContent(XmlPullParser xmlPullParser, KeyboardRow c1027aj, boolean z) throws XmlPullParserException, IOException {
        parseSwitchInternal(xmlPullParser, c1027aj, (PhysicalKeySpecTable) null, z);
    }

    private void parseSwitchInternal(XmlPullParser xmlPullParser, KeyboardRow c1027aj, PhysicalKeySpecTable c1026ai, boolean z) throws XmlPullParserException, IOException {
        boolean zM7141b = false;
        while (true) {
            if (xmlPullParser.getEventType() == 1) {
                return;
            }
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("case".equals(name)) {
                    zM7141b |= parseCase(xmlPullParser, c1027aj, c1026ai, zM7141b ? true : z);
                } else if ("default".equals(name)) {
                    zM7141b |= parseDefault(xmlPullParser, c1027aj, c1026ai, zM7141b ? true : z);
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

    private boolean parseCase(XmlPullParser xmlPullParser, KeyboardRow c1027aj, PhysicalKeySpecTable c1026ai, boolean z) throws XmlPullParserException, Resources.NotFoundException, IOException {
        boolean zM7150d = matchCaseConditions(xmlPullParser);
        if (c1027aj == null && c1026ai == null) {
            if (!zM7150d) {
                z = true;
            }
            parseKeyboardContent(xmlPullParser, z);
        } else if (c1026ai == null) {
            if (!zM7150d) {
                z = true;
            }
            parseRowContent(xmlPullParser, c1027aj, z);
        } else {
            if (!zM7150d) {
                z = true;
            }
            parsePhysicalKeyboardExtensionContent(xmlPullParser, c1026ai, z);
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
            // NOTE: Keyboard_Case_isFormFillAllowed (index 4) is declared and used by
            // kbd_unified_input_menu_pkb.xml, but no check for it survived decompilation
            // and no backing state exists -- such a case currently always matches.
            return matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_keyboardLayoutSet, ResourceLocaleUtils.getKeyboardLayoutSetName(c0977g.mSubtype))
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
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_localeCode, c0977g.mLocale.toString())
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_languageCode, c0977g.mLocale.getLanguage())
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_countryCode, c0977g.mLocale.getCountry())
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_physicalKeypadVariant, DeviceProfile.current().getKeypadLayout())
                    && matchString(typedArrayObtainAttributes, R.styleable.Keyboard_Case_physicalKeypadType, DeviceProfile.current().getEffectiveKeypadType());
        } finally {
            typedArrayObtainAttributes.recycle();
        }
    }

    private static boolean matchInteger(TypedArray typedArray, int i, int i2) {
        return !typedArray.hasValue(i) || typedArray.getInt(i, 0) == i2;
    }

    private static boolean matchBoolean(TypedArray typedArray, int i, boolean z) {
        return !typedArray.hasValue(i) || typedArray.getBoolean(i, false) == z;
    }


    /** Matches a "|"-separated attribute value. Note {@code String.split} on a two-char
     *  escaped delimiter keeps its non-regex fast path, so no Pattern is needed; what did
     *  cost was boxing the split array into an Arrays$ArrayList for a linear contains, up
     *  to eight times per &lt;case&gt; element on every keyboard build. */
    private static boolean matchString(TypedArray typedArray, int i, String str) {
        return !typedArray.hasValue(i) || splitContains(typedArray.getString(i), str);
    }

    /** Null-safe: a non-string attribute value makes {@code getString} return null. */
    private static boolean splitContains(String value, String str) {
        if (value == null) {
            return false;
        }
        for (String candidate : value.split("\\|")) {
            if (candidate.equals(str)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchTypedValue(TypedArray typedArray, int i, int i2, String str) {
        TypedValue typedValuePeekValue = typedArray.peekValue(i);
        if (typedValuePeekValue == null) {
            return true;
        }
        if (ResourceConfigManager.isIntegerType(typedValuePeekValue)) {
            return i2 == typedArray.getInt(i, 0);
        }
        // String values match by name against a "|"-separated list, like matchString.
        // This branch was lost in decompilation, which made every string-valued
        // keyboardLayoutSetElement/mode <case> fail — e.g. the shifted/locked shift-key
        // styles never applied, so the shift icon never changed state.
        if (ResourceConfigManager.isStringType(typedValuePeekValue)) {
            return splitContains(typedArray.getString(i), str);
        }
        return false;
    }

    private static boolean matchIcon(TypedArray typedArray, int i, KeyboardIconSet c1024ag) {
        return (typedArray.hasValue(i) && c1024ag.getIconDrawable(KeyboardIconSet.getIconId(typedArray.getString(i))) == null) ? false : true;
    }

    private boolean parseDefault(XmlPullParser xmlPullParser, KeyboardRow c1027aj, PhysicalKeySpecTable c1026ai, boolean z) throws XmlPullParserException, Resources.NotFoundException, IOException {
        if (c1027aj == null && c1026ai == null) {
            parseKeyboardContent(xmlPullParser, z);
            return true;
        }
        if (c1026ai == null) {
            parseRowContent(xmlPullParser, c1027aj, z);
            return true;
        }
        parsePhysicalKeyboardExtensionContent(xmlPullParser, c1026ai, z);
        return true;
    }

    private void parseKeyStyle(XmlPullParser xmlPullParser, boolean z) throws XmlParseUtils.NonEmptyTag, XmlParseUtils.ParseException {
        AttributeSet attributeSetAsAttributeSet = Xml.asAttributeSet(xmlPullParser);
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_KeyStyle);
        TypedArray typedArrayObtainAttributes2 = this.mResources.obtainAttributes(attributeSetAsAttributeSet, R.styleable.Keyboard_Key);
        try {
            boolean hasStyleName = typedArrayObtainAttributes.hasValue(R.styleable.Keyboard_KeyStyle_styleName);
            if (!hasStyleName) {
                throw new XmlParseUtils.ParseException("<key-style/> needs styleName attribute", xmlPullParser);
            }
            if (!z) {
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

    private void parsePhysicalKeyboardExtensionContent(XmlPullParser xmlPullParser, PhysicalKeySpecTable c1026ai, boolean z) throws XmlPullParserException, IOException {
        while (xmlPullParser.getEventType() != 1) {
            int next = xmlPullParser.next();
            if (next == 2) {
                String name = xmlPullParser.getName();
                if ("PhysicalKey".equals(name)) {
                    parsePhysicalKey(xmlPullParser, c1026ai, z);
                } else if ("switch".equals(name)) {
                    parseSwitchPhysicalKeyboardContent(xmlPullParser, c1026ai, z);
                } else {
                    throw new XmlParseUtils.IllegalStartTag(xmlPullParser, name, "Row");
                }
            } else if (next == 3) {
                String name2 = xmlPullParser.getName();
                if (!"PhysicalKeyboardExtension".equals(name2) && !"case".equals(name2) && !"default".equals(name2)) {
                    throw new XmlParseUtils.IllegalEndTag(xmlPullParser, name2, "Row");
                }
                return;
            }
        }
    }

    private void parseSwitchPhysicalKeyboardContent(XmlPullParser xmlPullParser, PhysicalKeySpecTable c1026ai, boolean z) throws XmlPullParserException, IOException {
        parseSwitchInternal(xmlPullParser, (KeyboardRow) null, c1026ai, z);
    }

    private void parsePhysicalKey(XmlPullParser xmlPullParser, PhysicalKeySpecTable c1026ai, boolean z) throws XmlParseUtils.ParseException {
        if (z) {
            XmlParseUtils.checkEndTag("PhysicalKey", xmlPullParser);
            return;
        }
        TypedArray typedArrayObtainAttributes = this.mResources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_PhysicalKey);
        KeyStyle abstractC1067zM7090a = this.mParams.mKeyStyles.getEmptyKeyStyle();
        String strMo7100b = abstractC1067zM7090a.getString(typedArrayObtainAttributes, R.styleable.Keyboard_PhysicalKey_keySpec);
        if (TextUtils.isEmpty(strMo7100b)) {
            throw new XmlParseUtils.ParseException("Empty keySpec", xmlPullParser);
        }
        PhysicalKeySpec keySpec = new PhysicalKeySpec(strMo7100b, typedArrayObtainAttributes, abstractC1067zM7090a);
        typedArrayObtainAttributes.recycle();
        XmlParseUtils.checkEndTag("PhysicalKey", xmlPullParser);
        c1026ai.addPhysicalKeySpec(keySpec);
    }

    private void startKeyboard() {
        this.mCurrentY += this.mParams.mTopPadding;
        this.mTopEdge = true;
    }

    private void startRow(KeyboardRow c1027aj) {
        addEdgeSpace(this.mParams.mLeftPadding, c1027aj);
        this.mCurrentRow = c1027aj;
        this.mLeftEdge = true;
        this.mRightEdgeKey = null;
    }

    private void endRow(KeyboardRow c1027aj) {
        if (this.mCurrentRow == null) {
            throw new RuntimeException("orphan end row tag");
        }
        Key key = this.mRightEdgeKey;
        if (key != null) {
            key.setRightEdge(this.mParams);
            this.mRightEdgeKey = null;
        }
        addEdgeSpace(this.mParams.mRightPadding, c1027aj);
        this.mCurrentY += c1027aj.getRowHeight();
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

    private void addEdgeSpace(float f, KeyboardRow c1027aj) {
        c1027aj.advanceXPos(f);
        this.mLeftEdge = false;
        this.mRightEdgeKey = null;
    }
}
