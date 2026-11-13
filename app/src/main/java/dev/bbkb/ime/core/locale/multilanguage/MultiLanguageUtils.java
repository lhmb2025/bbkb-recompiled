package dev.bbkb.ime.core.locale.multilanguage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;



public final class MultiLanguageUtils {

    private static final String TAG = "MultiLanguageUtils";

    private MultiLanguageUtils() {
    }

    private static ArrayList<InputMethodSubtype> createPredefinedSubtypes(Resources resources) {
        String strM5503a = SubtypeFactory.createPrefSubtypes(resources.getStringArray(R.array.predefined_subtypes));
        if (DeviceProfile.current().hasPhysicalKeyboard()) {
            String strM5503a2 = SubtypeFactory.createPrefSubtypes(resources.getStringArray(R.array.predefined_builtin_keyboard_subtypes));
            StringBuilder sb = new StringBuilder(strM5503a);
            if (!strM5503a.isEmpty()) {
                sb.append(';');
            }
            sb.append(strM5503a2);
            strM5503a = sb.toString();
        }
        return new ArrayList<>(Arrays.asList(SubtypeFactory.createSubtypesFromPref(strM5503a)));
    }

    /**
     * @param c0910y the input method manager to register with
     * @param arrayList the saved multi-language keyboards
     */
    public static void registerAdditionalSubtypes(RichInputMethodManager c0910y, Resources resources, ArrayList<MultiLanguageConfig> arrayList) {
        ArrayList<InputMethodSubtype> arrayListM4745a = toSubtypes(arrayList);
        arrayListM4745a.addAll(createPredefinedSubtypes(resources));
        c0910y.setAdditionalInputMethodSubtypes((InputMethodSubtype[]) arrayListM4745a.toArray(new InputMethodSubtype[arrayListM4745a.size()]));
    }

    public static ArrayList<InputMethodSubtype> getSubtypesFromPrefs(SharedPreferences sharedPreferences) {
        return toSubtypes(loadConfigs(sharedPreferences));
    }

    /** @param arrayList multi-language keyboards to convert */
    public static ArrayList<InputMethodSubtype> toSubtypes(ArrayList<MultiLanguageConfig> arrayList) {
        ArrayList<InputMethodSubtype> arrayList2 = new ArrayList<>();
        Iterator<MultiLanguageConfig> it = arrayList.iterator();
        while (it.hasNext()) {
            arrayList2.add(it.next().toSubtype());
        }
        return arrayList2;
    }


    public static ArrayList<MultiLanguageConfig> loadConfigs(SharedPreferences sharedPreferences) {
        ArrayList<MultiLanguageConfig> arrayList = new ArrayList<>();
        String string = sharedPreferences.getString("multi_lang_input_subtypes", "");
        if (!TextUtils.isEmpty(string)) {
            for (String str : string.split(";")) {
                // Unparseable entries are dropped: toSubtypes() dereferences every element.
                MultiLanguageConfig config = parseConfig(str);
                if (config != null) {
                    arrayList.add(config);
                }
            }
        }
        return arrayList;
    }

    /** @param collection every multi-language keyboard; replaces the stored list */
    public static void saveConfigs(SharedPreferences sharedPreferences, Collection<MultiLanguageConfig> collection) {
        StringBuilder sb = new StringBuilder();
        for (MultiLanguageConfig c0710e : collection) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(serializeConfig(c0710e));
        }
        sharedPreferences.edit().putString("multi_lang_input_subtypes", sb.toString()).apply();
    }

    /** @param c0710e the keyboard to append */
    public static boolean appendConfigToPrefs(SharedPreferences sharedPreferences, MultiLanguageConfig c0710e) {
        String string = sharedPreferences.getString("multi_lang_input_subtypes", "");
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(string)) {
            sb.append(string);
            sb.append(";");
        }
        sb.append(serializeConfig(c0710e));
        // apply(): reached on the main thread from the subtype-switch path; the caller ignores the result.
        sharedPreferences.edit().putString("multi_lang_input_subtypes", sb.toString()).apply();
        return true;
    }

    /** @param c0710e the keyboard to write as "primary/extra...:layoutSet" */
    public static String serializeConfig(MultiLanguageConfig c0710e) {
        if (c0710e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder((String) c0710e.getPrimaryLocale().first);
        Iterator<LocaleItem> it = c0710e.getSupportingLocales().iterator();
        while (it.hasNext()) {
            LocaleItem next = it.next();
            if (sb.length() > 0) {
                sb.append("/");
            }
            sb.append((String) next.first);
        }
        sb.append(":");
        sb.append(c0710e.getKeyboardLayoutSet());
        return sb.toString();
    }

    /** @param str one stored entry, "primary/extra...:layoutSet" */
    public static MultiLanguageConfig parseConfig(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String[] strArrSplit = str.split(":");
        if (strArrSplit.length > 1) {
            String str2 = strArrSplit[1];
            // "null": a primary with no Latin layout (e.g. Korean); it would type in the fallback
            // layout, not the language it names, so it is dropped.
            if (TextUtils.isEmpty(str2) || str2.equals("null")) {
                return null;
            }
            String[] strArrSplit2 = strArrSplit[0].split("/");
            int length = strArrSplit2.length;
            if (length > 1) {
                LocaleItem c0711f = new LocaleItem(strArrSplit2[0]);
                ArrayList arrayList = new ArrayList();
                for (int i = 1; i < length; i++) {
                    arrayList.add(new LocaleItem(strArrSplit2[i]));
                }
                return new MultiLanguageConfig(c0711f, arrayList, str2);
            }
        }
        return null;
    }

    public static boolean isUsingSystemLanguages(Context context) {
        // API 34+ restricts ENABLED_INPUT_METHODS; use InputMethodManager instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null) {
                    return false;
                }

                // Our own package (release or .debug), never the BlackBerry keyboard installed alongside.
                final String ourPackage = context.getPackageName();
                List<InputMethodInfo> enabledInputMethods = imm.getEnabledInputMethodList();
                for (InputMethodInfo imi : enabledInputMethods) {
                    if (imi.getPackageName().equals(ourPackage)) {
                        List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi, true);
                        return subtypes.size() < 2;
                    }
                }
                return false;
            } catch (Exception e) {
                // Treated as not using system languages.
                return false;
            }
        }

        String string = Settings.Secure.getString(context.getContentResolver(), "enabled_input_methods");
        if (string == null) {
            return false;
        }
        // Entries are "<package>/<class>;<subtypeId>;...". Match our own package exactly so the
        // BlackBerry keyboard installed alongside is not counted.
        final String ourPrefix = context.getPackageName() + "/";
        boolean z = false;
        for (String str : string.split(":")) {
            if (str.startsWith(ourPrefix)) {
                z = str.split(";").length < 2;
            }
        }
        return z;
    }
}
