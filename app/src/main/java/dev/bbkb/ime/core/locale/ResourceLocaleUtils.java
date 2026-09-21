package dev.bbkb.ime.core.locale;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.Context;
import android.content.res.Resources;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import dev.bbkb.ime.core.shared.RunInLocale;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.DebugLogUtils;



public final class ResourceLocaleUtils {

    private static final String TAG = "ResourceLocaleUtils";

    private static volatile boolean sInitialized = false;

    /**
     * Application context, not a {@code Resources} handle: the IME's {@code Resources} is
     * re-configured on every configuration change, so a cached instance would keep resolving
     * against the config in force at first init.
     */
    private static volatile Context sAppContext;

    private static String[] sPredefinedLayouts;

    private static final String[] sSortedRtlLanguages;

    private static final Object LOCK = new Object();

    private static final HashMap<String, String> sLayoutToDisplayName = new HashMap<>();

    private static final HashMap<String, Integer> sLayoutToNameId = new HashMap<>();

    private static final HashMap<String, Integer> sExceptionalLocaleToNameId = new HashMap<>();

    private static final HashMap<String, Integer> sExceptionalLocaleToWithLayoutNameId = new HashMap<>();

    private static final HashMap<String, String> sLocaleAndExtraToLayoutSet = new HashMap<>();

    private static final String[] sSortedNonLatinLanguages = {"ja", NuanceSDK.CHINESE_LOCALE};

    static {
        Arrays.sort(sSortedNonLatinLanguages);
        sSortedRtlLanguages = new String[]{"ar", "fa", "iw"};
        Arrays.sort(sSortedRtlLanguages);
    }

    private ResourceLocaleUtils() {
    }

    public static void init(Context context) {
        synchronized (LOCK) {
            if (!sInitialized) {
                initLocked(context);
                sInitialized = true;
            }
        }
    }

    /** Re-reads the resource-backed tables after a configuration change. */
    public static void reinit(Context context) {
        synchronized (LOCK) {
            initLocked(context);
            sInitialized = true;
        }
    }

    /** @return the live application {@code Resources}, or {@code null} before {@link #init}. */
    private static Resources resources() {
        final Context context = sAppContext;
        return context != null ? context.getResources() : null;
    }

    /**
     * @return the locale the UI is currently displayed in, falling back to the default locale
     *         before {@link #init} has run. Single source for what used to be five copies of
     *         {@code sResources.getConfiguration().locale}.
     */
    private static Locale displayLocale() {
        final Resources resources = resources();
        return resources != null ? LocaleUtils.getConfigurationLocale(resources) : Locale.getDefault();
    }

    private static void initLocked(Context context) throws Resources.NotFoundException {
        sAppContext = context.getApplicationContext();
        Resources resources = context.getResources();
        // getIdentifier()'s defPackage is the *installed* package, not the R-class package, so it
        // cannot be a literal: it used to read "com.blackberry.keyboard", which stopped being this
        // app's id when it moved to dev.bbkb.ime (and had never matched the .debug build either).
        final String resPackage = context.getPackageName();
        String[] stringArray = resources.getStringArray(R.array.predefined_layouts);
        sPredefinedLayouts = stringArray;
        String[] stringArray2 = resources.getStringArray(R.array.predefined_layout_display_names);
        int i = 0;
        for (int i2 = 0; i2 < stringArray.length; i2++) {
            String str = stringArray[i2];
            sLayoutToDisplayName.put(str, stringArray2[i2]);
            sLayoutToNameId.put(str, Integer.valueOf(resources.getIdentifier("string/subtype_generic_" + str, null, resPackage)));
            sLayoutToNameId.put(getNoLanguageLayoutKey(str), Integer.valueOf(resources.getIdentifier("string/subtype_no_language_" + str, null, resPackage)));
        }
        sLayoutToNameId.put("translit", Integer.valueOf(R.string.subtype_generic_translit));
        for (String str2 : resources.getStringArray(R.array.subtype_locale_exception_keys)) {
            sExceptionalLocaleToNameId.put(str2, Integer.valueOf(resources.getIdentifier("string/subtype_" + str2, null, resPackage)));
            sExceptionalLocaleToWithLayoutNameId.put(str2, Integer.valueOf(resources.getIdentifier("string/subtype_with_layout_" + str2, null, resPackage)));
        }
        String[] stringArray3 = resources.getStringArray(R.array.locale_and_extra_value_to_keyboard_layout_set_map);
        while (true) {
            int i3 = i + 1;
            if (i3 >= stringArray3.length) {
                return;
            }
            sLocaleAndExtraToLayoutSet.put(stringArray3[i], stringArray3[i3]);
            i += 2;
        }
    }

    public static boolean isExceptionalLocale(String str) {
        return sExceptionalLocaleToNameId.containsKey(str);
    }

    private static final String getNoLanguageLayoutKey(String str) {
        return "zz_" + str;
    }

    public static int getSubtypeNameResId(String str, String str2) {
        if (isExceptionalLocale(str)) {
            return sExceptionalLocaleToWithLayoutNameId.get(str).intValue();
        }
        if ("zz".equals(str)) {
            str2 = getNoLanguageLayoutKey(str2);
        }
        Integer num = sLayoutToNameId.get(str2);
        return num == null ? R.string.subtype_generic : num.intValue();
    }

    private static Locale getDisplayLocale(String str) {
        if ("zz".equals(str)) {
            return displayLocale();
        }
        return LocaleUtils.constructLocaleFromString(str);
    }

    public static String getSubtypeLocaleDisplayName(String str) {
        return getSubtypeLocaleDisplayNameInternal(str, displayLocale());
    }

    public static String getSubtypeLocaleDisplayNameInLocale(String str) {
        return getSubtypeLocaleDisplayNameInternal(str, getDisplayLocale(str));
    }

    public static String getSubtypeLanguageDisplayName(String str) {
        Locale localeM5507a = LocaleUtils.constructLocaleFromString(str);
        return getSubtypeLocaleDisplayNameInternal(localeM5507a.getLanguage(), getDisplayLocale(str));
    }

    private static String getSubtypeLocaleDisplayNameInternal(String str, Locale locale) {
        String displayName;
        if ("zz".equals(str)) {
            final Resources resources = resources();
            return resources != null ? resources.getString(R.string.subtype_no_language) : "";
        }
        final Integer num = sExceptionalLocaleToNameId.get(str);
        if (num != null && num.intValue() != 0) {
            displayName = new RunInLocale<String>() {
                @Override
                public String job(Resources resources) {
                    return resources.getString(num.intValue());
                }
            }.runInLocale(sAppContext, locale);
        } else {
            displayName = LocaleUtils.constructLocaleFromString(str).getDisplayName(locale);
        }
        return capitalizeFirstCodePoint(displayName, locale);
    }

    private static String getReplacementString(InputMethodSubtype inputMethodSubtype, Locale locale) {
        if (inputMethodSubtype.containsExtraValueKey("UntranslatableReplacementStringInSubtypeName")) {
            return inputMethodSubtype.getExtraValueOf("UntranslatableReplacementStringInSubtypeName");
        }
        return getSubtypeLocaleDisplayNameInternal(inputMethodSubtype.getLocale(), locale);
    }

    public static String getSubtypeDisplayName(InputMethodSubtype inputMethodSubtype) {
        return getSubtypeDisplayNameInternal(inputMethodSubtype, displayLocale());
    }

    private static String getSubtypeDisplayNameInternal(final InputMethodSubtype inputMethodSubtype, Locale locale) {
        final String strM5614a = getReplacementString(inputMethodSubtype, locale);
        final int nameResId = inputMethodSubtype.getNameResId();
        return capitalizeFirstCodePoint(new RunInLocale<String>() {
            @Override
            public String job(Resources resources) {
                try {
                    return resources.getString(nameResId, strM5614a);
                } catch (Resources.NotFoundException unused) {
                    if (BuildConfig.DEBUG) {
                        Logger.warn(ResourceLocaleUtils.TAG, "Unknown subtype: mode=" + inputMethodSubtype.getMode() + " nameResId=" + inputMethodSubtype.getNameResId() + " locale=" + inputMethodSubtype.getLocale() + " extra=" + inputMethodSubtype.getExtraValue() + "\n" + DebugLogUtils.getStackTrace());
                    }
                    return "";
                }
            }
        }.runInLocale(sAppContext, locale), locale);
    }

    public static boolean isNoLanguage(InputMethodSubtype inputMethodSubtype) {
        return "zz".equals(inputMethodSubtype.getLocale());
    }

    public static boolean isNoLanguage(Locale locale) {
        if (locale == null) {
            return false;
        }
        return "zz".equals(locale.getLanguage());
    }

    public static Locale getSubtypeLocale(InputMethodSubtype inputMethodSubtype) {
        if (inputMethodSubtype == null) {
            // Fallback to current configuration locale if subtype is unavailable
            return displayLocale();
        }
        String localeStr = inputMethodSubtype.getLocale();
        if (localeStr == null) {
            return displayLocale();
        }
        return LocaleUtils.constructLocaleFromString(localeStr);
    }

    public static String getKeyboardLayoutSetDisplayName(InputMethodSubtype inputMethodSubtype) {
        return getKeyboardLayoutSetDisplayName(getKeyboardLayoutSetName(inputMethodSubtype));
    }

    public static String getKeyboardLayoutSetDisplayName(String str) {
        return sLayoutToDisplayName.get(str);
    }

    public static String getKeyboardLayoutSetName(InputMethodSubtype inputMethodSubtype) {
        String extraValueOf = inputMethodSubtype.getExtraValueOf("KeyboardLayoutSet");
        if (extraValueOf == null) {
            extraValueOf = sLocaleAndExtraToLayoutSet.get(inputMethodSubtype.getLocale() + ":" + inputMethodSubtype.getExtraValue());
        }
        if (extraValueOf != null) {
            return extraValueOf;
        }
        Logger.debug(TAG, "KeyboardLayoutSet not found, use keypadVariant: locale=" + inputMethodSubtype.getLocale() + " extraValue=" + inputMethodSubtype.getExtraValue());
        return DeviceProfile.current().getKeypadLayout();
    }

    public static boolean isRtlLanguage(Locale locale) {
        return Arrays.binarySearch(sSortedRtlLanguages, locale.getLanguage()) >= 0;
    }

    public static boolean isRtlLanguage(InputMethodSubtype inputMethodSubtype) {
        return isRtlLanguage(getSubtypeLocale(inputMethodSubtype));
    }

    public static String getConverterDescriptor(InputMethodSubtype inputMethodSubtype) {
        return inputMethodSubtype.getExtraValueOf("ConverterDescriptor");
    }

    public static Set<Locale> getAdditionalLocales(InputMethodSubtype inputMethodSubtype) {
        String extraValueOf;
        if (inputMethodSubtype == null || (extraValueOf = inputMethodSubtype.getExtraValueOf("AdditionalLocales")) == null) {
            return null;
        }
        TreeSet treeSet = new TreeSet(new Comparator<Locale>() {
            @Override
            public int compare(Locale locale, Locale locale2) {
                return locale.toString().compareTo(locale2.toString());
            }
        });
        for (String str : extraValueOf.split("/")) {
            treeSet.add(LocaleUtils.constructLocaleFromString(str));
        }
        return treeSet;
    }

    public static boolean isNonCjkLanguage(InputMethodSubtype inputMethodSubtype) {
        if (inputMethodSubtype == null) {
            return false;
        }
        return Arrays.binarySearch(sSortedNonLatinLanguages, getSubtypeLocale(inputMethodSubtype).getLanguage()) < 0;
    }
}
