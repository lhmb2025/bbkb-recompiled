package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.SharedPreferences;
import android.util.JsonWriter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The skin tone last chosen per emoji ({@code emoji_tone_memory}): a JSON object from the
 * {@linkplain #normalize(String) base form} to the variant. Written by the strip's and the board's
 * popups; the strip slot and board keys show the remembered variant. Picking the plain emoji
 * forgets it. Callers must not write in incognito fields.
 */
public final class EmojiToneMemory {

    static final String PREF_KEY = "emoji_tone_memory";

    /** The last JSON string parsed and its map: the strip asks on every keystroke. */
    private static String cachedJson;
    private static Map<String, String> cachedMap = Collections.emptyMap();

    private EmojiToneMemory() {
        // Static helpers only.
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
     * The stored value as-is: the board keeps its built pages until this changes. Every writer
     * (popups, restore, reset) goes through the preference, so comparing it catches them all.
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
            parsed = parse(json);
        } catch (JSONException e) {
            parsed = null;
        }
        cachedJson = json;
        cachedMap = parsed == null ? Collections.<String, String>emptyMap() : parsed;
        return cachedMap;
    }

    /** Only string values are kept; a JSON null or any other value is not a remembered tone. */
    private static Map<String, String> parse(String json) throws JSONException {
        JSONObject obj = new JSONObject(json);
        Map<String, String> map = new HashMap<>();
        for (Iterator<String> keys = obj.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = obj.opt(key);
            if (value instanceof String) {
                map.put(key, (String) value);
            }
        }
        return map;
    }

    private static String toJson(Map<String, String> map) {
        StringWriter out = new StringWriter();
        try (JsonWriter w = new JsonWriter(out)) {
            w.beginObject();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                w.name(entry.getKey()).value(entry.getValue());
            }
            w.endObject();
        } catch (IOException e) {
            // A StringWriter does not fail.
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    private static synchronized void write(SharedPreferences prefs, Map<String, String> map) {
        String json = toJson(map);
        cachedJson = json;
        cachedMap = map;
        prefs.edit().putString(PREF_KEY, json).apply();
    }
}
