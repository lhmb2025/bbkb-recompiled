package dev.bbkb.ime.keyboard.inputboard.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.bbkb.ime.core.settings.util.SettingsManager;

/**
 * Turns what the keyboard knows about a language into the tag a speech recogniser accepts.
 *
 * <p>Google's recognition service silently replaces an underscore form ({@code en_US},
 * {@code zh_CN_pinyin}) with the phone's default language; bare codes and hyphenated BCP-47 tags
 * work. Pure string functions, JVM-testable ({@link #likelyRegion} excepted).
 */
public final class VoiceLanguageTags {

    /** "No language" keyboard: nothing to dictate in, use the manual voice language. */
    public static final String NO_LANGUAGE = "zz";

    /** Deprecated ISO 639 codes the keyboard still uses (Java {@code Locale} keeps the old ones). */
    private static final Map<String, String> LANGUAGE_ALIASES = new HashMap<>();

    static {
        LANGUAGE_ALIASES.put("iw", "he");
        LANGUAGE_ALIASES.put("in", "id");
        LANGUAGE_ALIASES.put("ji", "yi");
        LANGUAGE_ALIASES.put("jw", "jv");
    }

    /**
     * Recogniser codes for languages the keyboard spells another way (Google's {@code cmn-Hans-CN},
     * {@code yue-Hant-HK} for {@code zh}). Used only to compare tags in {@link #bestMatch}.
     */
    private static final Map<String, String> SAME_LANGUAGE = new HashMap<>();

    static {
        SAME_LANGUAGE.put("cmn", "zh");
        SAME_LANGUAGE.put("yue", "zh");
        SAME_LANGUAGE.put("no", "nb");
        SAME_LANGUAGE.put("fil", "tl");
    }

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern REGION_LETTERS = Pattern.compile("[A-Za-z]{2}");
    private static final Pattern REGION_DIGITS = Pattern.compile("[0-9]{3}");

    private VoiceLanguageTags() {
    }

    /**
     * @param useKeyboardLanguage the "Use keyboard language" setting
     * @param keyboardLocale the current subtype's locale string ({@code en_US}, {@code zh_TW_stroke})
     * @param manualTag the "Voice input language" setting (already a hyphenated tag)
     */
    public static String effectiveTag(boolean useKeyboardLanguage, String keyboardLocale, String manualTag) {
        String fallback = normalise(manualTag);
        if (fallback == null) {
            fallback = SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE;
        }
        if (!useKeyboardLanguage) {
            return fallback;
        }
        final String fromKeyboard = normalise(keyboardLocale);
        return fromKeyboard != null ? fromKeyboard : fallback;
    }

    /**
     * {@code en_US} to {@code en-US}, {@code zh_CN_pinyin} to {@code zh-CN}, {@code iw} to
     * {@code he}, {@code es_419} to {@code es-419}. Hyphenated tags pass through as they are. Null
     * for nothing usable: empty, {@code zz}, or no language part.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf('-') >= 0 && trimmed.indexOf('_') < 0) {
            final String language = trimmed.substring(0, trimmed.indexOf('-')).toLowerCase(Locale.ROOT);
            return NO_LANGUAGE.equals(language) ? null : trimmed;
        }
        final String[] parts = trimmed.split("[_-]");
        if (parts.length == 0) {
            return null;
        }
        String language = parts[0].toLowerCase(Locale.ROOT);
        if (!LANGUAGE.matcher(language).matches() || NO_LANGUAGE.equals(language)) {
            return null;
        }
        final String alias = LANGUAGE_ALIASES.get(language);
        if (alias != null) {
            language = alias;
        }
        // The second part is a region only when it looks like one, not a layout name ("pinyin").
        String region = null;
        if (parts.length > 1) {
            final String candidate = parts[1];
            if (REGION_LETTERS.matcher(candidate).matches()) {
                region = candidate.toUpperCase(Locale.ROOT);
            } else if (REGION_DIGITS.matcher(candidate).matches()) {
                region = candidate;
            }
        }
        return region == null ? language : language + "-" + region;
    }

    /**
     * The primary tag followed by the tags of a multi-language keyboard's extra languages, each
     * normalised, without duplicates. One entry means there is nothing to detect between.
     */
    public static ArrayList<String> allowedTags(String primary, Set<Locale> extras) {
        final ArrayList<String> tags = new ArrayList<>();
        if (primary != null) {
            tags.add(primary);
        }
        if (extras != null) {
            for (Locale extra : extras) {
                final String tag = normalise(extra == null ? null : extra.toString());
                if (tag != null && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
        }
        return tags;
    }

    /**
     * The tag in {@code candidates} to use for {@code wanted}, or null when none is in the same
     * language. In order: exact (ignoring case), same region, {@code likelyRegion}, any region.
     * Returns the candidate's own spelling.
     *
     * @param likelyRegion a two-letter or three-digit region, or null for none
     */
    public static String bestMatch(String wanted, String likelyRegion, java.util.List<String> candidates) {
        if (wanted == null || wanted.isEmpty() || candidates == null) {
            return null;
        }
        final String language = languageOf(wanted);
        final String region = regionOf(wanted);
        String sameRegion = null;
        String inLikelyRegion = null;
        String sameLanguage = null;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (candidate.equalsIgnoreCase(wanted)) {
                return candidate;
            }
            if (!language.equals(languageOf(candidate))) {
                continue;
            }
            final String candidateRegion = regionOf(candidate);
            if (sameRegion == null && region != null && region.equalsIgnoreCase(candidateRegion)) {
                sameRegion = candidate;
            }
            if (inLikelyRegion == null && likelyRegion != null && likelyRegion.equalsIgnoreCase(candidateRegion)) {
                inLikelyRegion = candidate;
            }
            if (sameLanguage == null) {
                sameLanguage = candidate;
            }
        }
        if (sameRegion != null) {
            return sameRegion;
        }
        if (inLikelyRegion != null) {
            return inLikelyRegion;
        }
        // Not for Chinese: mainland, Taiwan and Hong Kong differ in script (and, for HK, language).
        return "zh".equals(language) ? null : sameLanguage;
    }

    /**
     * "FR" for "fr", "BR" for "pt", "CN" for "zh": the region a language most likely means, from
     * Android's locale data; null when it knows none.
     */
    public static String likelyRegion(String tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        try {
            final String region = android.icu.util.ULocale.addLikelySubtags(
                    android.icu.util.ULocale.forLanguageTag(tag)).getCountry();
            return region == null || region.isEmpty() ? null : region;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The language part of a hyphenated tag, lower-cased, old and same-language codes mapped. */
    private static String languageOf(String tag) {
        final int hyphen = tag.indexOf('-');
        final String language = (hyphen < 0 ? tag : tag.substring(0, hyphen)).toLowerCase(Locale.ROOT);
        final String alias = LANGUAGE_ALIASES.get(language);
        final String current = alias != null ? alias : language;
        final String same = SAME_LANGUAGE.get(current);
        return same != null ? same : current;
    }

    /** The region part of a hyphenated tag ("CA" for "fr-CA", "RS" for "sr-Latn-RS"), or null. */
    private static String regionOf(String tag) {
        final String[] parts = tag.split("-");
        for (int i = 1; i < parts.length; i++) {
            if (REGION_LETTERS.matcher(parts[i]).matches() || REGION_DIGITS.matcher(parts[i]).matches()) {
                return parts[i];
            }
        }
        return null;
    }

    /** "English (United States)" for {@code en-US}; the tag itself when the platform has no name. */
    public static String displayName(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        final String name = Locale.forLanguageTag(tag).getDisplayName();
        return name == null || name.isEmpty() ? tag : name;
    }
}
