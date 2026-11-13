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

/** Creates and serializes InputMethodSubtype objects for the IME framework. */
public final class SubtypeFactory {

    private static final String TAG = "SubtypeFactory";

    private static final InputMethodSubtype[] EMPTY_SUBTYPES = new InputMethodSubtype[0];

    private SubtypeFactory() {
        // Static utility, no instances.
    }


    public static boolean isAdditionalSubtype(InputMethodSubtype inputMethodSubtype) {
        return inputMethodSubtype.containsExtraValueKey("isAdditionalSubtype");
    }

    /**
     * @param str locale
     * @param str2 keyboard layout set name
     * @param z true if ASCII capable
     * @param z2 true if emoji capable
     */
    private static InputMethodSubtype createSubtype(String str, String str2, boolean z, boolean z2) {
        return InputMethodSubtypeCompat.newInputMethodSubtype(ResourceLocaleUtils.getSubtypeNameResId(str, str2), R.drawable.ic_ime_switcher, str, "keyboard", buildExtraValue(str, str2, z, z2), false, false, computeSubtypeHash(str, str2, z, z2));
    }

    /**
     * @param str locale
     * @param str2 keyboard layout set name
     */
    public static InputMethodSubtype createSubtype(String str, String str2) {
        return createSubtype(str, str2, false, false);
    }

    /** @param str {@code ;}-separated {@code locale:layout[:AsciiCapable]} specs */
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
                // Plain subtype_generic means no subtype_generic_<layout> string: drop a corrupt
                // spec, but keep the side-loaded non-Latin layouts, which are named that way.
                if (inputMethodSubtype.getNameResId() != R.string.subtype_generic || SideloadedSubtypes.isSideloadLayout(strArrSplit2[1])) {
                    arrayList.add(inputMethodSubtype);
                }
            }
        }
        return (InputMethodSubtype[]) arrayList.toArray(new InputMethodSubtype[arrayList.size()]);
    }

    /** @param strArr subtype specs to join with {@code ;} */
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
     * Frozen: this feeds {@link #computeSubtypeHash}, the subtype's identity, so any change empties
     * users' enabled languages on upgrade. {@code SubtypeFactoryExtraValueGoldenTest} pins it.
     *
     * @param str locale
     * @param str2 keyboard layout set name
     * @param z true if ASCII capable
     * @param z2 true if emoji capable
     */
    private static String buildExtraValue(String str, String str2, boolean z, boolean z2) {
        ArrayList arrayList = new ArrayList();
        arrayList.add("KeyboardLayoutSet=" + str2);
        if (z) {
            arrayList.add("AsciiCapable");
        }
        if (ResourceLocaleUtils.isExceptionalLocale(str)) {
            // Known defect, frozen: this is "null" for es_US, zh_CN_pinyin and zh_HK_cangjie and is
            // baked into their subtype ids. Fixing it needs a subtype migration.
            arrayList.add("UntranslatableReplacementStringInSubtypeName=" + ResourceLocaleUtils.getKeyboardLayoutSetDisplayName(str2));
        }
        if (z2) {
            arrayList.add("EmojiCapable");
        }
        arrayList.add("isAdditionalSubtype");
        return TextUtils.join(",", arrayList);
    }

    /**
     * Must hash exactly what {@link #buildExtraValue} produces, or subtypes stop comparing equal.
     *
     * @param str locale
     * @param str2 keyboard layout set name
     * @param z true if ASCII capable
     * @param z2 true if emoji capable
     */
    private static int computeSubtypeHash(String str, String str2, boolean z, boolean z2) {
        return Arrays.hashCode(new Object[]{str, "keyboard", buildExtraValue(str, str2, z, z2), false, false});
    }

    /**
     * @param str primary locale
     * @param arrayList the combined keyboard's locales
     * @param str2 keyboard layout set name
     */
    public static InputMethodSubtype createLanguageSubtype(String str, ArrayList<String> arrayList, String str2) {
        return createLanguageSubtype(str, arrayList, str2, true);
    }

    /**
     * @param str primary locale
     * @param arrayList the combined keyboard's locales
     * @param str2 keyboard layout set name
     * @param asciiCapable whether the keyboard types on Latin keys; Latin keyboards keep {@code true}
     *     (their ids are frozen)
     */
    public static InputMethodSubtype createLanguageSubtype(String str, ArrayList<String> arrayList, String str2, boolean asciiCapable) {
        if (TextUtils.isEmpty(str) || arrayList.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(buildExtraValue(str, str2, asciiCapable, true));
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
