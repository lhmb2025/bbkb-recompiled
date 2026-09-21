package dev.bbkb.ime.keyboard.internal;

import android.text.TextUtils;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;



public final class KeySpecParser {

    // Obfuscator residue: these two were code-point constructions of ";" and ",".
    // Both are single non-metacharacter delimiters, so String.split keeps its non-regex fast
    // path here -- do NOT turn them into precompiled Patterns, that would be slower.
    private static final String SPEC_SEPARATOR = ";";

    private static final String CODE_SEPARATOR = ",";

    private static final String LABEL_CODE_SEPARATOR = "\\|";

    private static String getLabelPart(String str) {
        String[] strArrSplit = str.split(LABEL_CODE_SEPARATOR, -1);
        return strArrSplit.length <= 1 ? str : strArrSplit[0];
    }

    public static String decodeHexCodePoints(String str) {
        String strM7349g = getLabelPart(str);
        StringBuilder sb = new StringBuilder();
        for (String str2 : strM7349g.split(CODE_SEPARATOR)) {
            sb.appendCodePoint(Integer.parseInt(str2, 16));
        }
        return sb.toString();
    }

    public static String getFirstSpec(String str) {
        return str.split(SPEC_SEPARATOR, -1)[0];
    }

    public static List<String> parseLabelList(String str) {
        String[] strArrSplit = str.split(SPEC_SEPARATOR, -1);
        ArrayList arrayList = new ArrayList();
        for (String str2 : strArrSplit) {
            arrayList.add(decodeHexCodePoints(str2));
        }
        return arrayList;
    }

    private static String getCodePart(String str) {
        String[] strArrSplit = str.split(LABEL_CODE_SEPARATOR, -1);
        return strArrSplit.length <= 1 ? str : TextUtils.isEmpty(strArrSplit[1]) ? strArrSplit[0] : strArrSplit[1];
    }

    public static int parseSpecFlags(String str) {
        String[] strArrSplit = str.split(LABEL_CODE_SEPARATOR, -1);
        if (strArrSplit.length <= 2) {
            return 0;
        }
        try {
            return Integer.parseInt(strArrSplit[2]);
        } catch (NumberFormatException unused) {
            return 0;
        }
    }

    public static int parseCode(String str) {
        String strM7350h = getCodePart(str);
        if (strM7350h.indexOf(44) < 0) {
            return Integer.parseInt(strM7350h, 16);
        }
        return -4;
    }

    public static String parseOutputText(String str) {
        String strM7350h = getCodePart(str);
        if (strM7350h.indexOf(44) < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String str2 : strM7350h.split(CODE_SEPARATOR)) {
            sb.appendCodePoint(Integer.parseInt(str2, 16));
        }
        return sb.toString();
    }


    // ------------------------------------------------------------------------------------
    // Key-spec parsing, merged back in from KeySpecParser (§5.9).
    //
    // AOSP has ONE KeySpecParser. This tree had it split in two: the label/code/spec splitting
    // above, and the escape/icon/code-reference parsing below. Neither half was AOSP's file, so
    // a diff against upstream could not line either of them up, which is exactly what C1 exists
    // to prevent. The halves are also each other's only real callers.
    // ------------------------------------------------------------------------------------

    private static boolean hasIcon(String str) {
        return str.startsWith("!icon/");
    }

    private static boolean hasCode(String str, int i) {
        int i2;
        if (i <= 0 || (i2 = i + 1) >= str.length()) {
            return false;
        }
        return str.startsWith("!code/", i2) || str.startsWith("0x", i2);
    }

    private static String parseEscape(String str) {
        int i;
        if (str.indexOf(92) < 0) {
            return str;
        }
        int length = str.length();
        StringBuilder sb = new StringBuilder();
        int i2 = 0;
        while (i2 < length) {
            char cCharAt = str.charAt(i2);
            if (cCharAt == '\\' && (i = i2 + 1) < length) {
                sb.append(str.charAt(i));
                i2 = i;
            } else {
                sb.append(cCharAt);
            }
            i2++;
        }
        return sb.toString();
    }

    private static int indexOfLabelEnd(String str) {
        int i;
        int length = str.length();
        if (str.indexOf(92) < 0) {
            int iIndexOf = str.indexOf(124);
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
            char cCharAt = str.charAt(i2);
            if (cCharAt == '\\' && (i = i2 + 1) < length) {
                i2 = i;
            } else if (cCharAt == '|') {
                return i2;
            }
            i2++;
        }
        return -1;
    }

    private static String getBeforeLabelEnd(String str, int i) {
        return i < 0 ? str : str.substring(0, i);
    }

    private static String getAfterLabelEnd(String str, int i) {
        return str.substring(i + 1);
    }

    private static void checkDoubleLabelEnd(String str, int i) {
        if (indexOfLabelEnd(getAfterLabelEnd(str, i)) < 0) {
            return;
        }
        throw new KeySpecParserError("Multiple |: " + str);
    }

    public static String getLabel(String str) {
        if (str == null || hasIcon(str)) {
            return null;
        }
        String strM7460f = parseEscape(getBeforeLabelEnd(str, indexOfLabelEnd(str)));
        if (!strM7460f.isEmpty()) {
            return strM7460f;
        }
        throw new KeySpecParserError("Empty label: " + str);
    }

    private static String parseOutputText(String str, int i) {
        if (i <= 0) {
            return null;
        }
        checkDoubleLabelEnd(str, i);
        return parseEscape(getAfterLabelEnd(str, i));
    }

    public static String getOutputText(String str) {
        if (str == null) {
            return null;
        }
        int iM7462g = indexOfLabelEnd(str);
        if (hasCode(str, iM7462g)) {
            return null;
        }
        String strM7461f = parseOutputText(str, iM7462g);
        if (strM7461f != null) {
            if ((strM7461f).codePointCount(0, (strM7461f).length()) == 1) {
                return null;
            }
            if (!strM7461f.isEmpty()) {
                return strM7461f;
            }
            throw new KeySpecParserError("Empty outputText: " + str);
        }
        String strM7450a = getLabel(str);
        if (strM7450a == null) {
            throw new KeySpecParserError("Empty label: " + str);
        }
        if ((strM7450a).codePointCount(0, (strM7450a).length()) == 1) {
            return null;
        }
        return strM7450a;
    }

    public static int getCode(String str) {
        if (str == null) {
            return -21;
        }
        int iM7462g = indexOfLabelEnd(str);
        if (hasCode(str, iM7462g)) {
            checkDoubleLabelEnd(str, iM7462g);
            return getCodeFromSpec(getAfterLabelEnd(str, iM7462g), -21);
        }
        String strM7461f = parseOutputText(str, iM7462g);
        if (strM7461f != null) {
            if ((strM7461f).codePointCount(0, (strM7461f).length()) == 1) {
                return strM7461f.codePointAt(0);
            }
            return -4;
        }
        String strM7450a = getLabel(str);
        if (strM7450a == null) {
            throw new KeySpecParserError("Empty label: " + str);
        }
        if ((strM7450a).codePointCount(0, (strM7450a).length()) == 1) {
            return strM7450a.codePointAt(0);
        }
        return -4;
    }

    public static int getCodeFromSpec(String str, int i) {
        if (str == null) {
            return i;
        }
        if (str.startsWith("!code/")) {
            return KeyboardCodesSet.getCode(str.substring(6));
        }
        return str.startsWith("0x") ? Integer.parseInt(str.substring(2), 16) : i;
    }

    /** Parse an array of icon specs into icon ids (the code parser is {@link #getCode(String)}). */
    public static int[] getIconIds(String[] strArr) {
        final int n = (strArr == null) ? 0 : strArr.length;
        int[] iArr = new int[n];
        for (int i = 0; i < n; i++) {
            iArr[i] = getIconId(strArr[i]);
        }
        return iArr;
    }

    public static int getIconId(String str) {
        if (str != null && hasIcon(str)) {
            return KeyboardIconSet.getIconId(getBeforeLabelEnd(str, indexOfLabelEnd(str)).substring(6));
        }
        return 0;
    }

    
    public static final class KeySpecParserError extends RuntimeException {
        public KeySpecParserError(String str) {
            super(str);
        }
    }


    // ------------------------------------------------------------------------------------
    // More-keys spec parsing, moved here from MoreKeySpec (§5.9).
    //
    // AOSP keeps these on KeySpecParser. They had been folded into the subclass of the
    // MoreKeySpec value type, which is why restoring the MoreKeySpec name needed this move
    // first: the statics have nothing to do with a key's value, they parse the spec strings a
    // key is built from, and they were the only reason the pair could not simply collapse.
    // ------------------------------------------------------------------------------------

    /** Obfuscator residue: was a code-point construction of "%". */
    private static final String PERCENT_MARKER = "%";

    private static final String[] EMPTY_KEY_SPECS = new String[0];

    public static String[] splitKeySpecs(String str) {
        return splitKeySpecsWithFlag(str, false);
    }

    public static String[] splitKeySpecsWithFlag(String str, boolean z) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        int length = str.length();
        if (length == 1) {
            return new String[]{str};
        }
        ArrayList arrayList = null;
        HashSet hashSet = null;
        int i = 0;
        int i2 = 0;
        while (i < length) {
            char cCharAt = str.charAt(i);
            if (cCharAt == ',') {
                if (i - i2 > 0) {
                    if (arrayList == null) {
                        arrayList = new ArrayList();
                        if (z) {
                            hashSet = new HashSet();
                        }
                    }
                    String strSubstring = str.substring(i2, i);
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
        String strSubstring2 = length - i2 > 0 ? str.substring(i2) : null;
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

    private static String[] filterOutEmptyString(String[] strArr) {
        if (strArr == null) {
            return EMPTY_KEY_SPECS;
        }
        ArrayList arrayListM5681a = null;
        for (int i = 0; i < strArr.length; i++) {
            String str = strArr[i];
            if (TextUtils.isEmpty(str)) {
                if (arrayListM5681a == null) {
                    arrayListM5681a = new ArrayList<>(java.util.Arrays.asList(strArr).subList(0, i));
                }
            } else if (arrayListM5681a != null) {
                arrayListM5681a.add(str);
            }
        }
        return arrayListM5681a == null ? strArr : (String[]) arrayListM5681a.toArray(new String[arrayListM5681a.size()]);
    }

    public static String[] insertAdditionalMoreKeys(String[] strArr, String[] strArr2) {
        String[] strArrM7282a = filterOutEmptyString(strArr);
        String[] strArrM7282a2 = filterOutEmptyString(strArr2);
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
            // The remaining additional more-keys, appended in order. The decompiled source
            // indexed with the loop-invariant `i`, so a locale with more additional more-keys
            // than '%' markers got the same character repeated instead of the distinct tail.
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

    public static int getIntValue(String[] strArr, String str, int i) throws NumberFormatException {
        if (strArr == null) {
            return i;
        }
        int length = str.length();
        boolean z = false;
        for (int i2 = 0; i2 < strArr.length; i2++) {
            String str2 = strArr[i2];
            if (str2 != null && str2.startsWith(str)) {
                strArr[i2] = null;
                if (z) {
                    continue;
                } else {
                    try {
                        i = Integer.parseInt(str2.substring(length));
                        z = true;
                    } catch (NumberFormatException unused) {
                        throw new RuntimeException("integer should follow after " + str + ": " + str2);
                    }
                }
            }
        }
        return i;
    }

    public static boolean getBooleanValue(String[] strArr, String str) {
        if (strArr == null) {
            return false;
        }
        boolean z = false;
        for (int i = 0; i < strArr.length; i++) {
            String str2 = strArr[i];
            if (str2 != null && str2.equals(str)) {
                strArr[i] = null;
                z = true;
            }
        }
        return z;
    }
}
