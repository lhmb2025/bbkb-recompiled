package dev.bbkb.ime.core.shared;

import android.text.Spanned;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StringHelper {
    
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    /** Compiled-pattern cache so {@link #splitPreservingSpans} does not re-compile per call. */
    private static final ConcurrentHashMap<String, Pattern> SPLIT_PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final Pattern TLD_PATTERN = Pattern.compile("^(com|org|edu|gov|uk|net|ca|de|jp|fr|au|us|ru|ch|it|nl|se|no|es|mil|fi|cn|br|be|at|info|pl|dk|cz|nz|hu|cl|il|ie|za|tw|mx|kr|gr|ar|hk|in|pt|sg|tr|sk|ro|tv|lv|biz|ua|ee|th|hr|lt|is|nu|vu|lu|my|fm|si|co|ni|ph|cc|li|bg|ae|yu|md|name|pk|ve|ma|pw|ws|eg|mk|ir|id|bz|sa|ba|pe|kz|uz|tc|ec|by|cy|vn|to|st|ge|tk|ms|am|cr|ac)(\\.|\\/|$)");
    
    public static int[] toCodePointArray(CharSequence charSequence) {
        return toCodePointArray(charSequence, 0, charSequence.length());
    }
    
    public static int[] toCodePointArray(CharSequence charSequence, int start, int end) {
        if (charSequence.length() <= 0) {
            return EMPTY_INT_ARRAY;
        }
        int[] codePoints = new int[Character.codePointCount(charSequence, start, end)];
        int index = 0;
        int i = start;
        while (i < end) {
            int codePoint = Character.codePointAt(charSequence, i);
            codePoints[index++] = codePoint;
            i = Character.offsetByCodePoints(charSequence, i, 1);
        }
        return codePoints;
    }
    
    public static int[] toSortedCodePointArray(String str) {
        int[] codePoints = toCodePointArray(str);
        Arrays.sort(codePoints);
        return codePoints;
    }
    
    public static String capitalizeFirstCodePoint(String str, Locale locale) {
        if (str.length() <= 1) {
            return str.toUpperCase(locale);
        }
        int offset = str.offsetByCodePoints(0, 1);
        return str.substring(0, offset).toUpperCase(locale) + str.substring(offset);
    }
    
    public static String lowercaseFirstCodePoint(String str, Locale locale) {
        if (str.length() <= 1) {
            return str.toLowerCase(locale);
        }
        int offset = str.offsetByCodePoints(0, 1);
        return str.substring(0, offset).toLowerCase(locale) + str.substring(offset);
    }
    
    public static String capitalizeFirstAndLowercaseRest(String str, Locale locale) {
        if (str.length() <= 1) {
            return str.toUpperCase(locale);
        }
        int offset = str.offsetByCodePoints(0, 1);
        return str.substring(0, offset).toUpperCase(locale) + str.substring(offset).toLowerCase(locale);
    }
    
    public static boolean containsCodePoint(String str, int codePoint) {
        if (str != null) {
            int length = str.length();
            int i = 0;
            while (i < length) {
                int cp = str.codePointAt(i);
                if (cp == codePoint) {
                    return true;
                }
                i += Character.charCount(cp);
            }
        }
        return false;
    }
    
    public static boolean isAllWhitespace(String str) {
        // Walk char indices, advancing by the code point's char count. Bounding a char index by
        // the *code point* count truncates the scan for any string containing a surrogate pair.
        for (int i = 0; i < str.length(); ) {
            int codePoint = str.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }
    
    public static int countTrailingApostrophes(CharSequence charSequence) {
        int length = charSequence.length() - 1;
        int i = length;
        while (i >= 0 && charSequence.charAt(i) == '\'') {
            i--;
        }
        return length - i;
    }
    
    public static boolean isSymbolType(int codePoint) {
        int type = Character.getType(codePoint);
        return type == 24 || type == 25 || type == 20 || type == 21 || type == 22 || 
               type == 23 || type == 26 || type == 27 || type == 28 || type == 29 || 
               type == 30 || type == 7 || type == 6;
    }
    
    public static boolean looksLikeURL(CharSequence charSequence) {
        int length = charSequence.length();
        if (length == 0) {
            return false;
        }
        int codePointBefore = 0;
        int slashCount = 0;
        int wCount = 0;
        boolean hasDot = false;
        boolean hasSlash = false;
        while (length > 0) {
            try {
                codePointBefore = Character.codePointBefore(charSequence, length);
                if ((codePointBefore < 45 || codePointBefore > 122) && codePointBefore != 38 && codePointBefore != 35) {
                    break;
                }
                if (46 == codePointBefore) {
                    if (TLD_PATTERN.matcher(charSequence.subSequence(length, charSequence.length())).matches()) {
                        return true;
                    }
                    hasDot = true;
                }
                if (47 == codePointBefore) {
                    slashCount++;
                    if (2 == slashCount) {
                        return true;
                    }
                    hasSlash = true;
                } else {
                    slashCount = 0;
                }
                wCount = 119 == codePointBefore ? wCount + 1 : 0;
                length = Character.offsetByCodePoints(charSequence, length, -1);
            } catch (IndexOutOfBoundsException unused) {
                return false;
            }
        }
        if (wCount >= 3 && hasDot) {
            return true;
        }
        if (1 == slashCount && (length == 0 || Character.isWhitespace(codePointBefore))) {
            return true;
        }
        return hasDot && hasSlash;
    }
    
    public static boolean looksLikeEmail(CharSequence charSequence) {
        int length = charSequence.length();
        if (length < 2) {
            return false;
        }
        int codePointBefore = 0;
        int atCount = 0;
        int charsSinceAt = 0;
        int dotsSinceAt = 0;
        while (length > 0) {
            codePointBefore = Character.codePointBefore(charSequence, length);
            if (atCount == 0) {
                if (codePointBefore == 64) {
                    atCount++;
                } else if ((codePointBefore < 48 || codePointBefore > 57) && 
                          ((codePointBefore < 65 || codePointBefore > 90) && 
                          ((codePointBefore < 97 || codePointBefore > 122) && 
                          codePointBefore != 46 && codePointBefore != 45))) {
                    return false;
                }
            } else {
                if (codePointBefore < 33 || codePointBefore > 126 || codePointBefore == 34 || 
                    codePointBefore == 40 || codePointBefore == 41 || codePointBefore == 44 || 
                    codePointBefore == 58 || codePointBefore == 59 || codePointBefore == 60 || 
                    codePointBefore == 62 || codePointBefore == 64 || codePointBefore == 91 || 
                    codePointBefore == 92 || codePointBefore == 93) {
                    return false;
                }
                if (codePointBefore != 46) {
                    dotsSinceAt = 0;
                } else if (charsSinceAt == 0 || (dotsSinceAt = dotsSinceAt + 1) > 1) {
                    return false;
                }
                charsSinceAt++;
            }
            length -= Character.charCount(codePointBefore);
        }
        return codePointBefore != 46 && charsSinceAt > 0;
    }
    
    public static boolean endsWithQuoteAfterDigit(CharSequence charSequence) {
        int length = charSequence.length();
        if (length == 0) {
            return false;
        }
        int codePointBefore = Character.codePointBefore(charSequence, length);
        if (Character.isDigit(codePointBefore)) {
            return true;
        }
        int prevCodePoint = 0;
        while (length > 0) {
            codePointBefore = Character.codePointBefore(charSequence, length);
            if (34 == codePointBefore && Character.isWhitespace(prevCodePoint)) {
                return false;
            }
            if (Character.isWhitespace(codePointBefore) && 34 == prevCodePoint) {
                return true;
            }
            length -= Character.charCount(codePointBefore);
            prevCodePoint = codePointBefore;
        }
        return 34 == codePointBefore;
    }
    
    public static boolean hasLineBreakCharacter(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        for (int i = str.length() - 1; i >= 0; i--) {
            char c = str.charAt(i);
            if (c != 133) {
                switch (c) {
                    case '\n':
                    case 11:
                    case '\f':
                    case '\r':
                        break;
                    default:
                        switch (c) {
                            case 8232:
                            case 8233:
                                break;
                            default:
                                continue;
                        }
                }
            }
            return true;
        }
        return false;
    }
    
    public static int getCapitalizationMode(String str) {
        int length = str.length();
        int offset = 0;
        while (offset < length && !Character.isLetter(str.codePointAt(offset))) {
            offset = str.offsetByCodePoints(offset, 1);
        }
        if (offset == length || !Character.isUpperCase(str.codePointAt(offset))) {
            return 0;
        }
        int nextOffset = str.offsetByCodePoints(offset, 1);
        int upperCount = 1;
        int letterCount = 1;
        while (nextOffset < length && (1 == upperCount || letterCount == upperCount)) {
            int codePoint = str.codePointAt(nextOffset);
            if (Character.isUpperCase(codePoint)) {
                upperCount++;
                letterCount++;
            } else if (Character.isLetter(codePoint)) {
                letterCount++;
            }
            nextOffset = str.offsetByCodePoints(nextOffset, 1);
        }
        if (1 == upperCount) {
            return 1;
        }
        return letterCount == upperCount ? 2 : 0;
    }
    
    public static String toUpperCaseWithGreek(String str, boolean shouldUppercase, Locale locale, boolean useGreek) {
        if (str == null || !shouldUppercase) {
            return str;
        }
        if (useGreek && locale.getLanguage().equals("el")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                int index = "άέήίόύώϊϋΐΰ".indexOf(c);
                if (index != -1) {
                    sb.append("ΆΈΉΊΌΎΏΪΫΙΥ".charAt(index));
                } else if ("ΆΈΉΊΌΎΏΪΫΙΥ".indexOf(c) != -1) {
                    sb.append(c);
                } else {
                    sb.append(Character.toString(c).toUpperCase(locale));
                }
            }
            return sb.toString();
        }
        return str.toUpperCase(locale);
    }
    
    public static int toUpperCaseCodePoint(int codePoint, boolean shouldUppercase, Locale locale) {
        return toUpperCaseCodePointWithGreek(codePoint, shouldUppercase, locale, false);
    }
    
    public static int toUpperCaseCodePointWithGreek(int codePoint, boolean shouldUppercase, Locale locale, boolean useGreek) {
        if (!dev.bbkb.ime.core.Constants.isLetterCode(codePoint) || !shouldUppercase) {
            return codePoint;
        }
        String upperStr = toUpperCaseWithGreek(new String(Character.toChars(codePoint)), shouldUppercase, locale, useGreek);
        if (upperStr.codePointCount(0, upperStr.length()) == 1) {
            return upperStr.codePointAt(0);
        }
        return -21;
    }
    
    
    
    
    public static void removeDuplicates(ArrayList<String> list) {
        if (list.size() < 2) {
            return;
        }
        int i = 1;
        while (i < list.size()) {
            String str = list.get(i);
            int j = 0;
            while (true) {
                if (j >= i) {
                    break;
                }
                if (TextUtils.equals(str, list.get(j))) {
                    list.remove(i);
                    i--;
                    break;
                }
                j++;
            }
            i++;
        }
    }
    
    public static String removeFromCommaSeparatedList(String item, String list) {
        if (TextUtils.isEmpty(list)) {
            return "";
        }
        String[] items = list.split(",");
        if (!java.util.Arrays.asList(items).contains(item)) {
            return list;
        }
        ArrayList<String> result = new ArrayList<>(items.length - 1);
        for (String s : items) {
            if (!item.equals(s)) {
                result.add(s);
            }
        }
        return TextUtils.join(",", result);
    }
    
    public static CharSequence[] splitPreservingSpans(CharSequence charSequence, String pattern, boolean keepEmpty) {
        // Map.computeIfAbsent is API 24 and minSdk is 23 with no desugaring: NoSuchMethodError on
        // an API-23 device. ConcurrentHashMap.putIfAbsent is safe. Compiling a Pattern twice under
        // a race is harmless; both are equivalent and one is discarded.
        Pattern compiled = SPLIT_PATTERN_CACHE.get(pattern);
        if (compiled == null) {
            final Pattern created = Pattern.compile(pattern);
            final Pattern prior = SPLIT_PATTERN_CACHE.putIfAbsent(pattern, created);
            compiled = prior != null ? prior : created;
        }
        if (!(charSequence instanceof Spanned)) {
            return compiled.split(charSequence.toString(), keepEmpty ? -1 : 0);
        }
        ArrayList<CharSequence> list = new ArrayList<>();
        Matcher matcher = compiled.matcher(charSequence);
        boolean found = false;
        int end = 0;
        while (matcher.find()) {
            list.add(charSequence.subSequence(end, matcher.start()));
            end = matcher.end();
            found = true;
        }
        if (!found) {
            return new CharSequence[]{charSequence};
        }
        list.add(charSequence.subSequence(end, charSequence.length()));
        if (!keepEmpty) {
            for (int i = list.size() - 1; i >= 0 && TextUtils.isEmpty(list.get(i)); i--) {
                list.remove(i);
            }
        }
        return list.toArray(new CharSequence[list.size()]);
    }
}
