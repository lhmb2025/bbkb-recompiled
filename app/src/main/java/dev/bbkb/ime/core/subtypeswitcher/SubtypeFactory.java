package dev.bbkb.ime.core.subtypeswitcher;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.R;
import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import dev.bbkb.ime.BuildConfig;

/**
 * Factory for creating InputMethodSubtype instances.
 * Renamed from HardwareDetector — this class has nothing to do with hardware detection.
 * It creates and serializes InputMethodSubtype objects for the IME framework.
 */
public final class SubtypeFactory {

    private static final String TAG = "SubtypeFactory";

    private static final InputMethodSubtype[] EMPTY_SUBTYPES = new InputMethodSubtype[0];

    private SubtypeFactory() {
    }


    public static boolean isAdditionalSubtype(InputMethodSubtype inputMethodSubtype) {
        return inputMethodSubtype.containsExtraValueKey("isAdditionalSubtype");
    }

    private static InputMethodSubtype createSubtype(String str, String str2, boolean z, boolean z2) {
        return InputMethodSubtypeCompat.newInputMethodSubtype(ResourceLocaleUtils.getSubtypeNameResId(str, str2), R.drawable.ic_ime_switcher, str, "keyboard", buildExtraValue(str, str2, z, z2), false, false, computeSubtypeHash(str, str2, z, z2));
    }

    public static InputMethodSubtype createSubtype(String str, String str2) {
        return createSubtype(str, str2, false, false);
    }

    public static InputMethodSubtype[] createSubtypesFromPref(String str) {
        if (TextUtils.isEmpty(str)) {
            return EMPTY_SUBTYPES;
        }
        String[] strArrSplit = str.split(";");
        ArrayList arrayList = new ArrayList(strArrSplit.length);
        for (String str2 : strArrSplit) {
            String[] strArrSplit2 = str2.split(":");
            if (strArrSplit2.length != 2 && strArrSplit2.length != 3) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Unknown additional subtype specified: " + str2 + " in " + str);
            } else {
                InputMethodSubtype inputMethodSubtype = createSubtype(strArrSplit2[0], strArrSplit2[1], strArrSplit2.length == 3 ? "AsciiCapable".equals(strArrSplit2[2]) : false, true);
                if (inputMethodSubtype.getNameResId() != R.string.subtype_generic) {
                    arrayList.add(inputMethodSubtype);
                }
            }
        }
        return (InputMethodSubtype[]) arrayList.toArray(new InputMethodSubtype[arrayList.size()]);
    }

    public static String createPrefSubtypes(String[] strArr) {
        if (strArr == null || strArr.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String str : strArr) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(str);
        }
        return sb.toString();
    }

    /**
     * FROZEN. Every token here feeds {@link #computeSubtypeHash}, which is the subtype's identity to
     * the framework -- change one character and the framework sees a different subtype, so the
     * user's enabled-language selection for the affected locales silently empties on upgrade.
     *
     * <p>That is why the known defect below is deliberately NOT fixed. Pinned by
     * {@code SubtypeFactoryExtraValueGoldenTest}, which asserts these strings character for
     * character: a failure there means a compatibility break, not a bug in the test.
     */
    private static String buildExtraValue(String str, String str2, boolean z, boolean z2) {
        ArrayList arrayList = new ArrayList();
        arrayList.add("KeyboardLayoutSet=" + str2);
        if (z) {
            arrayList.add("AsciiCapable");
        }
        if (ResourceLocaleUtils.isExceptionalLocale(str)) {
            // KNOWN DEFECT, FROZEN (Wave 2, defect 21). getKeyboardLayoutSetDisplayName() returns
            // null for the three exceptional locales whose layout is not a predefined one --
            // es_US (spanish), zh_CN_pinyin (pinyin), zh_HK_cangjie (cangjie) -- so this
            // concatenates the literal text "null" and bakes it into their subtype ids.
            // Correcting it would change those three hashes; product decision 2026-09-08 is to
            // leave it rather than drop those languages from users' enabled list on upgrade.
            // If it is ever fixed, it needs a subtype migration, not just a string change.
            arrayList.add("UntranslatableReplacementStringInSubtypeName=" + ResourceLocaleUtils.getKeyboardLayoutSetDisplayName(str2));
        }
        if (z2) {
            arrayList.add("EmojiCapable");
        }
        arrayList.add("isAdditionalSubtype");
        return TextUtils.join(",", arrayList);
    }

    /**
     * Must hash exactly the extra-value string {@link #buildExtraValue} produces: the two used to
     * be duplicated line for line, and any drift between them silently breaks InputMethodSubtype
     * identity (subtypes stop comparing equal across getAdditionalSubtypes/indexOf).
     */
    private static int computeSubtypeHash(String str, String str2, boolean z, boolean z2) {
        return Arrays.hashCode(new Object[]{str, "keyboard", buildExtraValue(str, str2, z, z2), false, false});
    }

    public static InputMethodSubtype createLanguageSubtype(String str, ArrayList<String> arrayList, String str2) {
        if (TextUtils.isEmpty(str) || arrayList.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(buildExtraValue(str, str2, true, true));
        if (str.equals("nl") || str.equals("fr_CA") || str.equals("bs") || str.equals("sr")) {
            sb.append(",");
            sb.append("collisionHack");
            sb.append("=");
            sb.append(Integer.toHexString(31));
        } else if (str.equals("tr") || str.equals("sl") || str.equals("en_CA") || str.equals("it")) {
            sb.append(",");
            sb.append("collisionHack");
            sb.append("=");
            sb.append(Integer.toHexString(17));
        } else if (str.equals("pl") || str.equals("is") || str.equals("en_ZA") || str.equals("jv") || str.equals("gl_ES") || str.equals("cs")) {
            sb.append(",");
            sb.append("collisionHack");
            sb.append("=");
            sb.append(Integer.toHexString(7));
        }
        sb.append(",");
        sb.append("AdditionalLocales");
        sb.append("=");
        StringBuilder sb2 = new StringBuilder();
        Iterator<String> it = arrayList.iterator();
        while (it.hasNext()) {
            String next = it.next();
            if (sb2.length() > 0) {
                sb2.append("/");
            }
            sb2.append(next);
        }
        sb.append(sb2.toString());
        return new InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeNameResId(0).setSubtypeIconResId(R.drawable.ic_ime_switcher).setSubtypeLocale(str).setSubtypeMode("keyboard").setSubtypeExtraValue(sb.toString()).setIsAuxiliary(false).build();
    }
}
