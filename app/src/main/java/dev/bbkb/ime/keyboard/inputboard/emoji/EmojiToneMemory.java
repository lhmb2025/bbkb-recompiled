package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The skin tone the user last chose for each emoji ({@code emoji_tone_memory}).
 *
 * <p>Written by both skin-tone popups (the strip's and the board's) and read wherever a base emoji
 * is offered: the strip's emoji slot and the board's keys show and insert the remembered variant,
 * while a long-press still offers every tone. Picking the plain emoji again forgets the base.
 *
 * <p>Keys are the {@linkplain #normalize(String) base form} of the emoji: skin-tone modifiers and
 * variation selectors removed, so a toned variant and the dataset's fully-qualified base meet.
 * The value is a JSON object written by a default-configured {@link Gson}. There is no global
 * default-tone setting; this is per emoji, and it is not written in incognito fields (callers check).
 */
public final class EmojiToneMemory {

    static final String PREF_KEY = "emoji_tone_memory";

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<HashMap<String, String>>() {}.getType();

    /** The last JSON string parsed and its map: the strip asks on every keystroke. */
    private static String cachedJson;
    private static Map<String, String> cachedMap = Collections.emptyMap();

    private EmojiToneMemory() {
    }

    /** The remembered variant of {@code baseEmoji}, or null when none was chosen. */
    public static String preferred(SharedPreferences prefs, String baseEmoji) {
        if (prefs == null || baseEmoji == null || baseEmoji.isEmpty()) {
            return null;
        }
        Map<String, String> map = read(prefs);
        return map.isEmpty() ? null : map.get(normalize(baseEmoji));
    }

    /**
     * Records a pick. A toned variant is remembered for its base; a pick of the plain emoji
     * forgets the base.
     *
     * @return whether the stored memory changed
     */
    public static boolean onEmojiPicked(SharedPreferences prefs, String emoji) {
        if (prefs == null || emoji == null || emoji.isEmpty()) {
            return false;
        }
        String base = normalize(emoji);
        if (base.isEmpty()) {
            return false;
        }
        Map<String, String> map = new HashMap<>(read(prefs));
        boolean changed;
        if (hasSkinTone(emoji)) {
            changed = !emoji.equals(map.put(base, emoji));
        } else {
            changed = map.remove(base) != null;
        }
        if (changed) {
            write(prefs, map);
        }
        return changed;
    }

    /**
     * The stored value as-is, for telling whether the memory changed since an earlier call: the
     * emoji board keeps its built pages until this changes. Every writer (both popups, the strip,
     * a backup restore or reset) goes through the preference, so comparing it catches them all.
     */
    static String snapshot(SharedPreferences prefs) {
        return prefs.getString(PREF_KEY, null);
    }

    public static void clear(SharedPreferences prefs) {
        prefs.edit().remove(PREF_KEY).apply();
    }

    /** Whether {@code emoji} carries a skin-tone modifier (U+1F3FB..U+1F3FF). */
    public static boolean hasSkinTone(String emoji) {
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            if (isSkinToneModifier(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** The base form: skin-tone modifiers and variation selectors removed, everything else kept. */
    public static String normalize(String emoji) {
        StringBuilder sb = new StringBuilder(emoji.length());
        for (int i = 0; i < emoji.length(); ) {
            int cp = emoji.codePointAt(i);
            if (!isSkinToneModifier(cp) && cp != 0xFE0F && cp != 0xFE0E) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static boolean isSkinToneModifier(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    private static synchronized Map<String, String> read(SharedPreferences prefs) {
        String json = prefs.getString(PREF_KEY, null);
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        if (json.equals(cachedJson)) {
            return cachedMap;
        }
        Map<String, String> parsed;
        try {
            parsed = GSON.fromJson(json, MAP_TYPE);
        } catch (JsonSyntaxException e) {
            parsed = null;
        }
        cachedJson = json;
        cachedMap = parsed == null ? Collections.<String, String>emptyMap() : parsed;
        return cachedMap;
    }

    private static synchronized void write(SharedPreferences prefs, Map<String, String> map) {
        String json = GSON.toJson(map);
        cachedJson = json;
        cachedMap = map;
        prefs.edit().putString(PREF_KEY, json).apply();
    }
}
