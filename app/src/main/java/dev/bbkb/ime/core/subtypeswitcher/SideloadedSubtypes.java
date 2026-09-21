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
 * Subtypes for languages the engine supports but {@code res/xml/method.xml} does not offer.
 *
 * <p><b>The problem.</b> The ET9 core's locale table (105 entries, mirrored by
 * {@code assets/ldb/manifest.json}) covers 94 languages. {@code method.xml} declares subtypes for
 * 66 of them. The other 28 can be installed - the dictionary loads, the engine knows the language -
 * and then cannot be SELECTED, because Android only offers the subtypes an IME declares. Before
 * 2026-09-16 the hourly cleanup then deleted those packs for never being "in use", which hid the
 * gap behind a bigger bug.
 *
 * <p><b>The mechanism.</b> Android lets an IME add subtypes at runtime
 * ({@code InputMethodManager.setAdditionalInputMethodSubtypes}), which this app already uses for
 * multi-language keyboards. This class keeps a second list of {@code language:layout} pairs in its
 * own preference, which {@code RichInputMethodManager.getAdditionalSubtypes} merges alongside the
 * existing two sources.
 *
 * <p>Deliberately NOT stored in {@code custom_input_styles}: that preference has no setter today
 * and falls back to the {@code predefined_subtypes} resource arrays, so writing to it would pin a
 * snapshot of those arrays into every affected user's preferences and silently stop future changes
 * to the shipped defaults from reaching them.
 *
 * <p><b>Five languages are deliberately excluded</b> - see {@link #LAYOUT_FOR_LANGUAGE}.
 */
public final class SideloadedSubtypes {

    private static final String TAG = "SideloadedSubtypes";

    /** {@code ;}-separated {@code language:layout} pairs, in {@link SubtypeFactory} pref format. */
    static final String PREF_KEY = "sideloaded_subtypes";

    /**
     * Keyboard layout to give each engine-supported language {@code method.xml} omits.
     *
     * <p>Every value is a layout that actually ships in {@code res/xml/keyboard_layout_set_*.xml}
     * and writes the language's own script. The 11 Latin-script entries take plain {@code qwerty};
     * the rest reuse the script layout their writing system needs.
     *
     * <p><b>Amharic, Tibetan, Gujarati, Odia and Punjabi are absent on purpose.</b> There is no
     * layout in the tree for Ethiopic, Tibetan, Gujarati, Odia or Gurmukhi, and there is no honest
     * substitute: the obvious-looking {@code translit} is not a transliteration keyboard at all but
     * a Cyrillic one ({@code keyboard_layout_set_translit.xml} → {@code kbd_east_slavic}), and
     * handing these languages {@code qwerty} would offer a keyboard that cannot type a single
     * character of the dictionary behind it. They need a layout authored before they can have a
     * subtype; until then their packs install and sit unused rather than pretending to work.
     */
    private static final Map<String, String> LAYOUT_FOR_LANGUAGE;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        // Latin script - the shipped qwerty types these languages directly.
        for (String latin : new String[] {"ha", "ig", "ku", "ln", "mg", "st", "sw", "tk", "xh", "yo", "zu"}) {
            m.put(latin, "qwerty");
        }
        m.put("as", "bengali");             // Assamese uses the Bengali script
        m.put("kk", "east_slavic");         // Kazakh Cyrillic
        m.put("tg", "east_slavic");         // Tajik Cyrillic
        m.put("tt", "east_slavic");         // Tatar Cyrillic - the shipped pack is *_TTlsUNcyrillic_*
        m.put("ks", "hindi");               // Kashmiri - the shipped pack is *_KSlsUNdevanagari_*
        m.put("sa", "hindi");               // Sanskrit, Devanagari
        m.put("ne", "nepali_traditional");  // Nepali, Devanagari
        m.put("my", "myanmar");             // Burmese
        m.put("si", "sinhala");             // Sinhala
        m.put("ps", "farsi");               // Pashto - Perso-Arabic, closer to farsi than arabic
        m.put("ur", "farsi");               // Urdu - Perso-Arabic
        m.put("ug", "arabic");              // Uyghur - Perso-Arabic
        LAYOUT_FOR_LANGUAGE = Collections.unmodifiableMap(m);
    }

    private SideloadedSubtypes() {
    }

    /** {@code true} when this app can offer a runtime subtype for {@code language}. */
    public static boolean canOfferSubtypeFor(String language) {
        return LAYOUT_FOR_LANGUAGE.containsKey(language);
    }

    /** The layout this app would use for {@code language}, or {@code null} if it offers none. */
    public static String layoutFor(String language) {
        return LAYOUT_FOR_LANGUAGE.get(language);
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
     * Record a runtime subtype for {@code language}, if this app has a layout for it.
     *
     * <p>Idempotent, and does NOT check whether {@code method.xml} already declares the language -
     * callers know that, and {@link #LAYOUT_FOR_LANGUAGE} only contains languages it does not.
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
