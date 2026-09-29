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
 * <p>Verified against Google's recognition service on the KEY2 (2026-09-28, its own
 * {@code IntentParsingUtil} log): an underscore form such as {@code en_US}, {@code de_CH} or
 * {@code zh_CN_pinyin} is "invalid" and is silently replaced by the phone's default language, with
 * no error to the client; a bare language code works, with {@code iw} and {@code in} mapped to
 * {@code he} and {@code id} by the service; a hyphenated BCP-47 tag is used as given. So every
 * region-bearing keyboard dictated in the wrong language until this normaliser existed.
 *
 * <p>Pure functions over strings so the rules can be tested without Android.
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

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern REGION_LETTERS = Pattern.compile("[A-Za-z]{2}");
    private static final Pattern REGION_DIGITS = Pattern.compile("[0-9]{3}");

    private VoiceLanguageTags() {
    }

    /**
     * The tag to send for a dictation session.
     *
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
     * {@code he}, {@code es_419} to {@code es-419}. A tag that already carries hyphens (one the
     * voice-language picker stored, possibly with a script such as {@code cmn-Hans-CN}) is passed
     * through as it is. Returns null for nothing usable: empty, {@code zz}, or no language part.
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
        // The second part is a region only when it looks like one; a layout name ("pinyin",
        // "stroke", "cangjie", "zhuyin") or anything else is not part of the language tag.
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

    /** "English (United States)" for {@code en-US}; the tag itself when the platform has no name. */
    public static String displayName(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        final String name = Locale.forLanguageTag(tag).getDisplayName();
        return name == null || name.isEmpty() ? tag : name;
    }
}
