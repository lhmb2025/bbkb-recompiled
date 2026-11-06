package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.SharedPreferences;
import android.util.JsonWriter;

import dev.bbkb.ime.core.settings.util.SettingsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The one owner of the emoji recents preference format ({@code emoji_recent_keys}).
 *
 * <p>The value is a compact JSON array, newest first. Until 2026-11 a default-configured Gson
 * wrote it, which escaped {@code < > & = '} as Unicode escapes; such a value reads the same.
 * {@link EmojiKeyboard} writes its whole key grid, so the array is normally padded
 * with {@code ""} for every blank filler key, and a key with no output text is written as its
 * integer code. Readers keep only non-empty strings.
 *
 * <p>Deliberately does NOT choose an exception policy: the Recents page and the recents overlay
 * have different ones (see their call sites), and both are user-visible.
 */
final class EmojiRecents {

    private EmojiRecents() {
    }

    /**
     * The stored non-empty string entries, in stored order, at most {@code limit} of them.
     *
     * <p>An empty or {@code null} value is no recents, as it was for Gson.
     *
     * @throws JSONException if the stored value is not a JSON array, or has anything after it
     */
    static List<String> read(SharedPreferences prefs, int limit) throws JSONException {
        final List<String> recents = new ArrayList<>();
        final String json = SettingsManager.getEmojiRecentKeys(prefs);
        if (json == null) {
            return recents;
        }
        final JSONTokener tokener = new JSONTokener(json);
        if (tokener.nextClean() == '\0') {
            return recents;
        }
        tokener.back();
        final Object value = tokener.nextValue();
        if (tokener.nextClean() != '\0') {
            throw new JSONException("Trailing content after the recents array");
        }
        if (value == JSONObject.NULL) {
            return recents;
        }
        if (!(value instanceof JSONArray)) {
            throw new JSONException("Recents value is not a JSON array");
        }
        final JSONArray stored = (JSONArray) value;
        for (int i = 0; i < stored.length(); i++) {
            final Object entry = stored.opt(i);
            if (entry instanceof String && !((String) entry).isEmpty()) {
                recents.add((String) entry);
                if (recents.size() >= limit) {
                    break;
                }
            }
        }
        return recents;
    }

    /** Stores {@code entries} (strings and integer key codes) as the recents array; returns the stored value. */
    static String write(SharedPreferences prefs, List<Object> entries) {
        final String json = toJson(entries);
        SettingsManager.setEmojiRecentKeys(prefs, json);
        return json;
    }

    private static String toJson(List<Object> entries) {
        final StringWriter out = new StringWriter();
        try (JsonWriter w = new JsonWriter(out)) {
            w.beginArray();
            for (Object entry : entries) {
                if (entry instanceof Number) {
                    w.value((Number) entry);
                } else if (entry == null) {
                    w.nullValue();
                } else {
                    w.value(entry.toString());
                }
            }
            w.endArray();
        } catch (IOException e) {
            // A StringWriter does not fail.
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** The stored value unparsed, for comparing with what a recents grid last read or wrote. */
    static String stored(SharedPreferences prefs) {
        return SettingsManager.getEmojiRecentKeys(prefs);
    }
}
