package dev.bbkb.ime.core.suggestion;

import android.content.Context;

import java.util.ArrayList;
import java.util.Locale;

import dev.bbkb.ime.core.engine.Dictionary;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiData;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiSearchScoring;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiToneMemory;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider;

/**
 * The strip's emoji suggestion, from emojibase instead of the XT9 engine.
 *
 * <p>Owner requirement, 2026-11-02: "Replace the XT9 emoji suggestions with the top emojibase
 * result for a given query word." The engine's own emoji entries (kind {@value #KIND_EMOJI}) are
 * dropped from every suggestion list, and, while the Emoji suggestions switch is on and a word of
 * two or more characters is being composed, the best emojibase match for that word is appended
 * with the remembered skin tone applied ({@link EmojiToneMemory}). The strip shows it in its emoji
 * slot and the period key's flick suggestion reads the same entry.
 *
 * <p>Runs on the suggestion thread. The first call pays for the emojibase parse unless
 * {@link EmojibaseDataProvider#preloadAsync} ran first, which the IME does when the switch is on.
 */
public final class EmojibaseStripSuggestion {

    /** The suggestion kind the engine used for its emoji predictions; kept for the strip and the period key. */
    public static final int KIND_EMOJI = 11;

    /** Shorter words match too much of the dictionary to be worth an emoji. */
    public static final int MIN_WORD_LENGTH = 2;

    private EmojibaseStripSuggestion() {
    }

    /**
     * Drops the engine's emoji entries from {@code suggestions} and appends the emojibase hit for
     * {@code composingText}, when the switch is on and there is one.
     *
     * @param locale the suggestion locale; no hit is added for Chinese or Japanese, whose pick path
     *               reads the engine's own word info, which an emojibase entry does not carry
     */
    public static void apply(Context context, ArrayList<SuggestedWords.SuggestedWordInfo> suggestions,
            String composingText, Locale locale) {
        dropEngineEmoji(suggestions);
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings == null || !settings.isEmojiPredictionsEnabled) {
            return;
        }
        String hit = topHit(context, composingText, locale);
        if (hit != null) {
            suggestions.add(emojiEntry(hit));
        }
    }

    /** Removes every kind-{@value #KIND_EMOJI} entry, in place. */
    public static void dropEngineEmoji(ArrayList<SuggestedWords.SuggestedWordInfo> suggestions) {
        for (int i = suggestions.size() - 1; i >= 0; i--) {
            if (suggestions.get(i).isKind(KIND_EMOJI)) {
                suggestions.remove(i);
            }
        }
    }

    /**
     * The emoji the strip offers for {@code composingText}, with the remembered tone applied, or
     * null when the word is too short, the locale is Chinese or Japanese, or nothing scores an exact
     * or prefix match on a description or tag ({@link EmojiSearchScoring#STRIP_MIN_RELEVANCE}).
     */
    public static String topHit(Context context, String composingText, Locale locale) {
        if (composingText == null || composingText.trim().length() < MIN_WORD_LENGTH) {
            return null;
        }
        if (locale != null && LocaleUtils.isChineseOrJapanese(locale)) {
            return null;
        }
        EmojibaseDataProvider provider = EmojibaseDataProvider.getSharedLoaded(context);
        EmojiData hit = provider.topHit(composingText, EmojiSearchScoring.STRIP_MIN_RELEVANCE);
        if (hit == null) {
            return null;
        }
        String preferred = EmojiToneMemory.preferred(PrefsManager.INSTANCE.getPrefs(context), hit.getEmoji());
        return preferred != null ? preferred : hit.getEmoji();
    }

    /** A kind-{@value #KIND_EMOJI} entry for {@code emoji}, with no engine word info behind it. */
    public static SuggestedWords.SuggestedWordInfo emojiEntry(String emoji) {
        return new SuggestedWords.SuggestedWordInfo(emoji, 0, KIND_EMOJI, Dictionary.DICTIONARY_HARDCODED, -1, -1, null);
    }
}
