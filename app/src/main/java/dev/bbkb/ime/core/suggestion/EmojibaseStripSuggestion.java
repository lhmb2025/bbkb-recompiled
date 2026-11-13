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
 * The strip's emoji suggestion, from emojibase instead of the engine. The engine's own emoji
 * entries are dropped; with the Emoji suggestions switch on, the top emojibase match for the
 * composing word (2+ chars) is appended with the remembered skin tone. The strip's emoji slot and
 * the period key's flick suggestion both read it. Runs on the suggestion thread; the first call
 * parses emojibase unless {@link EmojibaseDataProvider#preloadAsync} ran.
 */
public final class EmojibaseStripSuggestion {

    /** The suggestion kind the engine used for its emoji predictions; kept for the strip and the period key. */
    public static final int KIND_EMOJI = 11;

    /** Shorter words match too much of the dictionary to be worth an emoji. */
    public static final int MIN_WORD_LENGTH = 2;

    private EmojibaseStripSuggestion() {
    }

    /**
     * Drops the engine's emoji entries and appends the emojibase hit for {@code composingText}.
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
     * The toned emoji for {@code composingText}, or null for a short word, Chinese/Japanese, or no
     * match above {@link EmojiSearchScoring#STRIP_MIN_RELEVANCE}.
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
