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
     * @param localeString locale
     * @param keyboardLayoutSetName keyboard layout set name
     * @param asciiCapable true if ASCII capable
     * @param emojiCapable true if emoji capable
     */
    private static InputMethodSubtype createSubtype(String localeString, String keyboardLayoutSetName, boolean asciiCapable, boolean emojiCapable) {
        return InputMethodSubtypeCompat.newInputMethodSubtype(ResourceLocaleUtils.getSubtypeNameResId(localeString, keyboardLayoutSetName), R.drawable.ic_ime_switcher, localeString, "keyboard", buildExtraValue(localeString, keyboardLayoutSetName, asciiCapable, emojiCapable), false, false, computeSubtypeHash(localeString, keyboardLayoutSetName, asciiCapable, emojiCapable));
    }

    /**
     * @param localeString locale
     * @param keyboardLayoutSetName keyboard layout set name
     */
    public static InputMethodSubtype createSubtype(String localeString, String keyboardLayoutSetName) {
        return createSubtype(localeString, keyboardLayoutSetName, false, false);
    }

    /** @param prefSubtypes {@code ;}-separated {@code locale:layout[:AsciiCapable]} specs */
    public static InputMethodSubtype[] createSubtypesFromPref(String prefSubtypes) {
        if (TextUtils.isEmpty(prefSubtypes)) {
            return EMPTY_SUBTYPES;
        }
        String[] strArrSplit = prefSubtypes.split(";");
        ArrayList arrayList = new ArrayList(strArrSplit.length);
        for (String str2 : strArrSplit) {
            String[] strArrSplit2 = str2.split(":");
            if (strArrSplit2.length != 2 && strArrSplit2.length != 3) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Unknown additional subtype specified: " + str2 + " in " + prefSubtypes);
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

    /** @param subtypeSpecs subtype specs to join with {@code ;} */
    public static String createPrefSubtypes(String[] subtypeSpecs) {
        if (subtypeSpecs == null || subtypeSpecs.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String str : subtypeSpecs) {
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
     * @param localeString locale
     * @param keyboardLayoutSetName keyboard layout set name
     * @param asciiCapable true if ASCII capable
     * @param emojiCapable true if emoji capable
     */
    private static String buildExtraValue(String localeString, String keyboardLayoutSetName, boolean asciiCapable, boolean emojiCapable) {
        ArrayList arrayList = new ArrayList();
        arrayList.add("KeyboardLayoutSet=" + keyboardLayoutSetName);
        if (asciiCapable) {
            arrayList.add("AsciiCapable");
        }
        if (ResourceLocaleUtils.isExceptionalLocale(localeString)) {
            // Known defect, frozen: this is "null" for es_US, zh_CN_pinyin and zh_HK_cangjie and is
            // baked into their subtype ids. Fixing it needs a subtype migration.
            arrayList.add("UntranslatableReplacementStringInSubtypeName=" + ResourceLocaleUtils.getKeyboardLayoutSetDisplayName(keyboardLayoutSetName));
        }
        if (emojiCapable) {
            arrayList.add("EmojiCapable");
        }
        arrayList.add("isAdditionalSubtype");
        return TextUtils.join(",", arrayList);
    }

    /**
     * Must hash exactly what {@link #buildExtraValue} produces, or subtypes stop comparing equal.
     *
     * @param localeString locale
     * @param keyboardLayoutSetName keyboard layout set name
     * @param asciiCapable true if ASCII capable
     * @param emojiCapable true if emoji capable
     */
    private static int computeSubtypeHash(String localeString, String keyboardLayoutSetName, boolean asciiCapable, boolean emojiCapable) {
        return Arrays.hashCode(new Object[]{localeString, "keyboard", buildExtraValue(localeString, keyboardLayoutSetName, asciiCapable, emojiCapable), false, false});
    }

    /**
     * @param localeString primary locale
     * @param arrayList the combined keyboard's locales
     * @param keyboardLayoutSetName keyboard layout set name
     */
    public static InputMethodSubtype createLanguageSubtype(String localeString, ArrayList<String> arrayList, String keyboardLayoutSetName) {
        return createLanguageSubtype(localeString, arrayList, keyboardLayoutSetName, true);
    }

    /**
     * @param localeString primary locale
     * @param arrayList the combined keyboard's locales
     * @param keyboardLayoutSetName keyboard layout set name
     * @param asciiCapable whether the keyboard types on Latin keys; Latin keyboards keep {@code true}
     *     (their ids are frozen)
     */
    public static InputMethodSubtype createLanguageSubtype(String localeString, ArrayList<String> arrayList, String keyboardLayoutSetName, boolean asciiCapable) {
        if (TextUtils.isEmpty(localeString) || arrayList.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(buildExtraValue(localeString, keyboardLayoutSetName, asciiCapable, true));
        if (localeString.equals("nl") || localeString.equals("fr_CA") || localeString.equals("bs") || localeString.equals("sr")) {
            sb.append(",");
            sb.append("collisionHack");
            sb.append("=");
            sb.append(Integer.toHexString(31));
        } else if (localeString.equals("tr") || localeString.equals("sl") || localeString.equals("en_CA") || localeString.equals("it")) {
            sb.append(",");
            sb.append("collisionHack");
            sb.append("=");
            sb.append(Integer.toHexString(17));
        } else if (localeString.equals("pl") || localeString.equals("is") || localeString.equals("en_ZA") || localeString.equals("jv") || localeString.equals("gl_ES") || localeString.equals("cs")) {
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
        return new InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeNameResId(0).setSubtypeIconResId(R.drawable.ic_ime_switcher).setSubtypeLocale(localeString).setSubtypeMode("keyboard").setSubtypeExtraValue(sb.toString()).setIsAuxiliary(false).build();
    }
}
