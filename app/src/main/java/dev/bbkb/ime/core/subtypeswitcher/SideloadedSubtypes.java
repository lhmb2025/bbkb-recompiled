package dev.bbkb.ime.core.subtypeswitcher;

import android.content.Context;
import android.content.SharedPreferences;

import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.shared.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime subtypes for engine languages {@code res/xml/method.xml} does not declare (Android only
 * offers declared or added subtypes). Kept as {@code language:layout} pairs in their own preference,
 * merged by {@code RichInputMethodManager.getAdditionalSubtypes}. Not in {@code custom_input_styles}:
 * writing there would pin a snapshot of the {@code predefined_subtypes} defaults into user prefs.
 */
public final class SideloadedSubtypes {

    private static final String TAG = "SideloadedSubtypes";

    /** {@code ;}-separated {@code language:layout} pairs, in {@link SubtypeFactory} pref format. */
    static final String PREF_KEY = "sideloaded_subtypes";

    /**
     * Layout per engine language {@code method.xml} omits; each value ships in
     * {@code keyboard_layout_set_*.xml} and writes the language's script. A language whose script has
     * no layout stays out ({@code translit} is Cyrillic, not a transliteration keyboard).
     */
    private static final Map<String, String> LAYOUT_FOR_LANGUAGE;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        // Latin script: qwerty types these directly.
        for (String latin : new String[] {"ha", "ig", "ku", "ln", "mg", "st", "sw", "tk", "xh", "yo", "zu"}) {
            m.put(latin, "qwerty");
        }
        m.put("as", "bengali");             // Assamese uses the Bengali script
        m.put("kk", "east_slavic");         // Kazakh Cyrillic
        m.put("tg", "east_slavic");         // Tajik Cyrillic
        m.put("tt", "east_slavic");         // Tatar Cyrillic (pack *_TTlsUNcyrillic_*)
        m.put("ks", "hindi");               // Kashmiri (pack *_KSlsUNdevanagari_*)
        m.put("sa", "hindi");               // Sanskrit, Devanagari
        m.put("ne", "nepali_traditional");  // Nepali, Devanagari
        m.put("my", "myanmar");             // Burmese
        m.put("si", "sinhala");             // Sinhala
        m.put("ps", "farsi");               // Pashto, Perso-Arabic: closer to farsi than arabic
        m.put("ur", "farsi");               // Urdu, Perso-Arabic
        m.put("ug", "arabic");              // Uyghur, Perso-Arabic
        m.put("gu", "gujarati");            // Gujarati
        m.put("pa", "gurmukhi");            // Punjabi, Gurmukhi
        m.put("or", "oriya");               // Odia
        m.put("am", "ethiopic");            // Amharic, Ethiopic
        m.put("bo", "tibetan");             // Tibetan
        LAYOUT_FOR_LANGUAGE = Collections.unmodifiableMap(m);
    }

    private SideloadedSubtypes() {
        // Static utility, no instances.
    }

    /** Every language this app can offer a runtime subtype for, with its layout; in table order. */
    public static Map<String, String> layouts() {
        return LAYOUT_FOR_LANGUAGE;
    }

    /** {@code true} when this app can offer a runtime subtype for {@code language}. */
    public static boolean canOfferSubtypeFor(String language) {
        return LAYOUT_FOR_LANGUAGE.containsKey(language);
    }

    /** The layout this app would use for {@code language}, or {@code null} if it offers none. */
    public static String layoutFor(String language) {
        return LAYOUT_FOR_LANGUAGE.get(language);
    }

    /** Whether {@code layout} is one a side-loaded language is placed on (so it is a real layout). */
    public static boolean isSideloadLayout(String layout) {
        return layout != null && LAYOUT_FOR_LANGUAGE.containsValue(layout);
    }

    /** The stored {@code language:layout} pairs, in insertion order, never {@code null}. */
    public static List<String> read(SharedPreferences prefs) {
        final String raw = prefs.getString(PREF_KEY, "");
        final List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String entry : raw.split(";")) {
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The pref value {@link SubtypeFactory#createSubtypesFromPref} consumes. */
    public static String readRaw(SharedPreferences prefs) {
        final String raw = prefs.getString(PREF_KEY, "");
        return raw == null ? "" : raw;
    }

    /**
     * Records a runtime subtype for {@code language} if it has a layout; idempotent.
     *
     * @return {@code true} if the preference now contains an entry for {@code language}
     */
    public static synchronized boolean add(Context context, String language) {
        final String layout = LAYOUT_FOR_LANGUAGE.get(language);
        if (layout == null) {
            Logger.info(TAG, "No layout available for " + language + "; not offering a subtype");
            return false;
        }
        final SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs(context);
        final List<String> entries = read(prefs);
        for (String e : entries) {
            if (e.startsWith(language + ":")) {
                return true;
            }
        }
        entries.add(language + ":" + layout);
        write(prefs, entries);
        return true;
    }

    /** Forget the runtime subtype for {@code language}. Safe when there is none. */
    public static synchronized void remove(Context context, String language) {
        final SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs(context);
        final List<String> entries = read(prefs);
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).startsWith(language + ":")) {
                entries.remove(i);
                changed = true;
            }
        }
        if (changed) {
            write(prefs, entries);
        }
    }

    private static void write(SharedPreferences prefs, List<String> entries) {
        prefs.edit().putString(PREF_KEY, String.join(";", entries)).apply();
    }
}
