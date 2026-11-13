package dev.bbkb.ime.keyboard.internal;

import android.text.TextUtils;


import java.util.ArrayList;
import java.util.HashSet;



public final class KeySpecParser {
    private static boolean hasIcon(String keySpec) {
        return keySpec.startsWith("!icon/");
    }

    private static boolean hasCode(String keySpec, int labelEnd) {
        int i2;
        if (labelEnd <= 0 || (i2 = labelEnd + 1) >= keySpec.length()) {
            return false;
        }
        return keySpec.startsWith("!code/", i2) || keySpec.startsWith("0x", i2);
    }

    private static String parseEscape(String text) {
        int i;
        if (text.indexOf(92) < 0) {
            return text;
        }
        int length = text.length();
        StringBuilder sb = new StringBuilder();
        int i2 = 0;
        while (i2 < length) {
            char cCharAt = text.charAt(i2);
            if (cCharAt == '\\' && (i = i2 + 1) < length) {
                sb.append(text.charAt(i));
                i2 = i;
            } else {
                sb.append(cCharAt);
            }
            i2++;
        }
        return sb.toString();
    }

    private static int indexOfLabelEnd(String keySpec) {
        int i;
        int length = keySpec.length();
        if (keySpec.indexOf(92) < 0) {
            int iIndexOf = keySpec.indexOf(124);
            if (iIndexOf != 0) {
                return iIndexOf;
            }
            if (length == 1) {
                return -1;
            }
            throw new KeySpecParserError("Empty label");
        }
        int i2 = 0;
        while (i2 < length) {
            char cCharAt = keySpec.charAt(i2);
            if (cCharAt == '\\' && (i = i2 + 1) < length) {
                i2 = i;
            } else if (cCharAt == '|') {
                return i2;
            }
            i2++;
        }
        return -1;
    }

    private static String getBeforeLabelEnd(String keySpec, int labelEnd) {
        return labelEnd < 0 ? keySpec : keySpec.substring(0, labelEnd);
    }

    private static String getAfterLabelEnd(String keySpec, int labelEnd) {
        return keySpec.substring(labelEnd + 1);
    }

    private static void checkDoubleLabelEnd(String keySpec, int labelEnd) {
        if (indexOfLabelEnd(getAfterLabelEnd(keySpec, labelEnd)) < 0) {
            return;
        }
        throw new KeySpecParserError("Multiple |: " + keySpec);
    }

    /** @param keySpec key spec */
    public static String getLabel(String keySpec) {
        if (keySpec == null || hasIcon(keySpec)) {
            return null;
        }
        String strM7460f = parseEscape(getBeforeLabelEnd(keySpec, indexOfLabelEnd(keySpec)));
        if (!strM7460f.isEmpty()) {
            return strM7460f;
        }
        throw new KeySpecParserError("Empty label: " + keySpec);
    }

    private static String parseOutputText(String keySpec, int labelEnd) {
        if (labelEnd <= 0) {
            return null;
        }
        checkDoubleLabelEnd(keySpec, labelEnd);
        return parseEscape(getAfterLabelEnd(keySpec, labelEnd));
    }

    /** @param keySpec key spec */
    public static String getOutputText(String keySpec) {
        if (keySpec == null) {
            return null;
        }
        int iM7462g = indexOfLabelEnd(keySpec);
        if (hasCode(keySpec, iM7462g)) {
            return null;
        }
        String strM7461f = parseOutputText(keySpec, iM7462g);
        if (strM7461f != null) {
            if ((strM7461f).codePointCount(0, (strM7461f).length()) == 1) {
                return null;
            }
            if (!strM7461f.isEmpty()) {
                return strM7461f;
            }
            throw new KeySpecParserError("Empty outputText: " + keySpec);
        }
        String strM7450a = getLabel(keySpec);
        if (strM7450a == null) {
            throw new KeySpecParserError("Empty label: " + keySpec);
        }
        if ((strM7450a).codePointCount(0, (strM7450a).length()) == 1) {
            return null;
        }
        return strM7450a;
    }

    /** @param keySpec key spec */
    public static int getCode(String keySpec) {
        if (keySpec == null) {
            return -21;
        }
        int iM7462g = indexOfLabelEnd(keySpec);
        if (hasCode(keySpec, iM7462g)) {
            checkDoubleLabelEnd(keySpec, iM7462g);
            return getCodeFromSpec(getAfterLabelEnd(keySpec, iM7462g), -21);
        }
        String strM7461f = parseOutputText(keySpec, iM7462g);
        if (strM7461f != null) {
            if ((strM7461f).codePointCount(0, (strM7461f).length()) == 1) {
                return strM7461f.codePointAt(0);
            }
            return -4;
        }
        String strM7450a = getLabel(keySpec);
        if (strM7450a == null) {
            throw new KeySpecParserError("Empty label: " + keySpec);
        }
        if ((strM7450a).codePointCount(0, (strM7450a).length()) == 1) {
            return strM7450a.codePointAt(0);
        }
        return -4;
    }

    /**
     * @param codeSpec "!code/name" or "0x" hex code
     * @param defaultCode default code when codeSpec is neither
     */
    public static int getCodeFromSpec(String codeSpec, int defaultCode) {
        if (codeSpec == null) {
            return defaultCode;
        }
        if (codeSpec.startsWith("!code/")) {
            return KeyboardCodesSet.getCode(codeSpec.substring(6));
        }
        return codeSpec.startsWith("0x") ? Integer.parseInt(codeSpec.substring(2), 16) : defaultCode;
    }

    /** Parses icon specs into icon ids. */
    public static int[] getIconIds(String[] iconSpecs) {
        final int n = (iconSpecs == null) ? 0 : iconSpecs.length;
        int[] iArr = new int[n];
        for (int i = 0; i < n; i++) {
            iArr[i] = getIconId(iconSpecs[i]);
        }
        return iArr;
    }

    /** @param keySpec key spec */
    public static int getIconId(String keySpec) {
        if (keySpec != null && hasIcon(keySpec)) {
            return KeyboardIconSet.getIconId(getBeforeLabelEnd(keySpec, indexOfLabelEnd(keySpec)).substring(6));
        }
        return 0;
    }

    
    public static final class KeySpecParserError extends RuntimeException {
        public KeySpecParserError(String message) {
            super(message);
        }
    }

    /** Placeholder in a more-keys list where additional more-keys are inserted. */
    private static final String PERCENT_MARKER = "%";

    private static final String[] EMPTY_KEY_SPECS = new String[0];

    /** @param text comma-separated key specs, backslash escapes allowed */
    public static String[] splitKeySpecs(String text) {
        return splitKeySpecsWithFlag(text, false);
    }

    /**
     * @param text comma-separated key specs, backslash escapes allowed
     * @param removeDuplicates true to drop duplicate specs
     */
    public static String[] splitKeySpecsWithFlag(String text, boolean removeDuplicates) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        int length = text.length();
        if (length == 1) {
            return new String[]{text};
        }
        ArrayList arrayList = null;
        HashSet hashSet = null;
        int i = 0;
        int i2 = 0;
        while (i < length) {
            char cCharAt = text.charAt(i);
            if (cCharAt == ',') {
                if (i - i2 > 0) {
                    if (arrayList == null) {
                        arrayList = new ArrayList();
                        if (removeDuplicates) {
                            hashSet = new HashSet();
                        }
                    }
                    String strSubstring = text.substring(i2, i);
                    if (hashSet == null || !hashSet.contains(strSubstring)) {
                        arrayList.add(strSubstring);
                        if (hashSet != null) {
                            hashSet.add(strSubstring);
                        }
                    }
                }
                i2 = i + 1;
            } else if (cCharAt == '\\') {
                i++;
            }
            i++;
        }
        String strSubstring2 = length - i2 > 0 ? text.substring(i2) : null;
        if (arrayList == null) {
            if (strSubstring2 != null) {
                return new String[]{strSubstring2};
            }
            return null;
        }
        if (strSubstring2 != null && (hashSet == null || !hashSet.contains(strSubstring2))) {
            arrayList.add(strSubstring2);
        }
        return (String[]) arrayList.toArray(new String[arrayList.size()]);
    }

    private static String[] filterOutEmptyString(String[] array) {
        if (array == null) {
            return EMPTY_KEY_SPECS;
        }
        ArrayList arrayListM5681a = null;
        for (int i = 0; i < array.length; i++) {
            String str = array[i];
            if (TextUtils.isEmpty(str)) {
                if (arrayListM5681a == null) {
                    arrayListM5681a = new ArrayList<>(java.util.Arrays.asList(array).subList(0, i));
                }
            } else if (arrayListM5681a != null) {
                arrayListM5681a.add(str);
            }
        }
        return arrayListM5681a == null ? array : (String[]) arrayListM5681a.toArray(new String[arrayListM5681a.size()]);
    }

    public static String[] insertAdditionalMoreKeys(String[] moreKeySpecs, String[] additionalMoreKeySpecs) {
        String[] strArrM7282a = filterOutEmptyString(moreKeySpecs);
        String[] strArrM7282a2 = filterOutEmptyString(additionalMoreKeySpecs);
        int length = strArrM7282a.length;
        int length2 = strArrM7282a2.length;
        ArrayList arrayListM5681a = null;
        int i = 0;
        for (int i2 = 0; i2 < length; i2++) {
            String str = strArrM7282a[i2];
            if (str.equals(PERCENT_MARKER)) {
                if (i < length2) {
                    String str2 = strArrM7282a2[i];
                    if (arrayListM5681a != null) {
                        arrayListM5681a.add(str2);
                    } else {
                        strArrM7282a[i2] = str2;
                    }
                    i++;
                } else if (arrayListM5681a == null) {
                    arrayListM5681a = new ArrayList<>(java.util.Arrays.asList(strArrM7282a).subList(0, i2));
                }
            } else if (arrayListM5681a != null) {
                arrayListM5681a.add(str);
            }
        }
        if (length2 > 0 && i == 0) {
            arrayListM5681a = new ArrayList<>(java.util.Arrays.asList(strArrM7282a2).subList(i, length2));
            for (String str3 : strArrM7282a) {
                arrayListM5681a.add(str3);
            }
        } else if (i < length2) {
            arrayListM5681a = new ArrayList<>(java.util.Arrays.asList(strArrM7282a).subList(0, length));
            // Additional more-keys beyond the '%' markers, appended in order.
            for (int i3 = i; i3 < length2; i3++) {
                arrayListM5681a.add(strArrM7282a2[i3]);
            }
        }
        if (arrayListM5681a == null && length > 0) {
            return strArrM7282a;
        }
        if (arrayListM5681a == null || arrayListM5681a.size() <= 0) {
            return null;
        }
        return (String[]) arrayListM5681a.toArray(new String[arrayListM5681a.size()]);
    }

    /**
     * Reads the int after {@code key} from the first matching spec; matched specs are nulled out.
     * @param moreKeys more-keys specs
     * @param key prefix to look for
     * @param defaultValue default value
     */
    public static int getIntValue(String[] moreKeys, String key, int defaultValue) throws NumberFormatException {
        if (moreKeys == null) {
            return defaultValue;
        }
        int length = key.length();
        boolean z = false;
        for (int i2 = 0; i2 < moreKeys.length; i2++) {
            String str2 = moreKeys[i2];
            if (str2 != null && str2.startsWith(key)) {
                moreKeys[i2] = null;
                if (z) {
                    continue;
                } else {
                    try {
                        defaultValue = Integer.parseInt(str2.substring(length));
                        z = true;
                    } catch (NumberFormatException unused) {
                        throw new RuntimeException("integer should follow after " + key + ": " + str2);
                    }
                }
            }
        }
        return defaultValue;
    }

    /**
     * True if {@code key} is present; matching specs are nulled out.
     * @param moreKeys more-keys specs
     * @param key flag to look for
     */
    public static boolean getBooleanValue(String[] moreKeys, String key) {
        if (moreKeys == null) {
            return false;
        }
        boolean z = false;
        for (int i = 0; i < moreKeys.length; i++) {
            String str2 = moreKeys[i];
            if (str2 != null && str2.equals(key)) {
                moreKeys[i] = null;
                z = true;
            }
        }
        return z;
    }
}
