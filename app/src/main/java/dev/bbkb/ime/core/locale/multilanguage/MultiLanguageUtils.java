package dev.bbkb.ime.core.locale.multilanguage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
import dev.bbkb.ime.core.settings.IntentUtils;

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

    public static void registerAdditionalSubtypes(RichInputMethodManager c0910y, Resources resources, ArrayList<MultiLanguageConfig> arrayList) {
        ArrayList<InputMethodSubtype> arrayListM4745a = toSubtypes(arrayList);
        arrayListM4745a.addAll(createPredefinedSubtypes(resources));
        c0910y.setAdditionalInputMethodSubtypes((InputMethodSubtype[]) arrayListM4745a.toArray(new InputMethodSubtype[arrayListM4745a.size()]));
    }

    public static ArrayList<InputMethodSubtype> getSubtypesFromPrefs(SharedPreferences sharedPreferences) {
        return toSubtypes(loadConfigs(sharedPreferences));
    }

    public static ArrayList<InputMethodSubtype> toSubtypes(ArrayList<MultiLanguageConfig> arrayList) {
        ArrayList<InputMethodSubtype> arrayList2 = new ArrayList<>();
        Iterator<MultiLanguageConfig> it = arrayList.iterator();
        while (it.hasNext()) {
            arrayList2.add(it.next().toSubtype());
        }
        return arrayList2;
    }

    /**
     * Flags for the "input language settings" activity. Spelled out: the two other call sites
     * used to pass View.MeasureSpec.EXACTLY (0x40000000 = FLAG_RECEIVER_REGISTERED_ONLY, a
     * broadcast-only flag) here, and IntentUtils uses setFlags(), which replaces rather than
     * adds - so those launches went out with no FLAG_ACTIVITY_NEW_TASK at all.
     */
    private static final int LANGUAGE_SETTINGS_INTENT_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;

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

    public static boolean appendConfigToPrefs(SharedPreferences sharedPreferences, MultiLanguageConfig c0710e) {
        String string = sharedPreferences.getString("multi_lang_input_subtypes", "");
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(string)) {
            sb.append(string);
            sb.append(";");
        }
        sb.append(serializeConfig(c0710e));
        // apply(): this is reached from the subtype-switch path on the main thread and the
        // caller ignores the result.
        sharedPreferences.edit().putString("multi_lang_input_subtypes", sb.toString()).apply();
        return true;
    }

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

    public static MultiLanguageConfig parseConfig(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String[] strArrSplit = str.split(":");
        if (strArrSplit.length > 1) {
            String str2 = strArrSplit[1];
            // "null": saved by the Compose wizard when its primary language defaulted to a system
            // language with no Latin layout (e.g. Korean). Such a keyboard types in the device's
            // fallback layout, not in the language it names, so it is dropped rather than kept.
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
        // Android 14+ (API 34+) restricts access to ENABLED_INPUT_METHODS setting
        // Use InputMethodManager API instead
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null) {
                    return false;
                }
                
                // Get enabled input methods
                // Our own package, whatever it is built as (dev.bbkb.ime, or .debug). This used
                // to be the literal "com.blackberry.keyboard", which is now a *different*
                // keyboard - the original BlackBerry app, which may well be installed alongside
                // this one. Matching it would have read that app's subtype count instead of ours.
                final String ourPackage = context.getPackageName();
                List<InputMethodInfo> enabledInputMethods = imm.getEnabledInputMethodList();
                for (InputMethodInfo imi : enabledInputMethods) {
                    if (imi.getPackageName().equals(ourPackage)) {
                        // Check if this IME has less than 2 subtypes enabled
                        List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi, true);
                        return subtypes.size() < 2;
                    }
                }
                return false;
            } catch (Exception e) {
                // Fallback to false if anything goes wrong
                return false;
            }
        }
        
        // Legacy method for API 33 and below
        String string = Settings.Secure.getString(context.getContentResolver(), "enabled_input_methods");
        if (string == null) {
            return false;
        }
        // Entries are "<package>/<class>;<subtypeId>;<subtypeId>...". The old code matched the
        // bare prefix "com.blackberry.keyboard", which matched both the release and the .debug
        // build - and now would also match the original BlackBerry Keyboard if it is installed
        // next to us. Match our own component exactly instead.
        final String ourPrefix = context.getPackageName() + "/";
        boolean z = false;
        for (String str : string.split(":")) {
            if (str.startsWith(ourPrefix)) {
                z = str.split(";").length < 2;
            }
        }
        return z;
    }

    // Note: Uses deprecated startActivityForResult on the passed Activity.
    // Caller's Activity should handle deprecation appropriately.
    @SuppressWarnings("deprecation")
    public static AlertDialog createEnableSubtypeDialog(final Activity activity) {
        int i = isUsingSystemLanguages((Context) activity) ? R.string.multi_language_input_enable_subtype_toggle_use_system_language_dialog_message : R.string.multi_language_input_enable_subtype_dialog_message;
        final Intent intentM5786a = IntentUtils.getInputLanguageSelectionIntent(RichInputMethodManager.getInstance().getInputMethodIdOfThisIme(), LANGUAGE_SETTINGS_INTENT_FLAGS);
        AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(activity, R.style.platformDialogTheme));
        builder.setTitle(R.string.multi_language_input_enable_subtype_dialog_title).setMessage(i).setNegativeButton(R.string.multi_language_input_enable_subtype_dialog_negative_button, (DialogInterface.OnClickListener) null).setPositiveButton(R.string.multi_language_input_enable_subtype_dialog_positive_button, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i2) {
                activity.startActivityForResult(intentM5786a, 0);
            }
        });
        return builder.create();
    }
}
