package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.SharedPreferences;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The one owner of the emoji recents preference format ({@code emoji_recent_keys}).
 *
 * <p>The value is a JSON array written by a default-configured {@link Gson} (HTML escaping on),
 * newest first. {@link EmojiKeyboard} writes its whole key grid, so the array is normally padded
 * with {@code ""} for every blank filler key, and a key with no output text is written as its
 * integer code. Readers keep only non-empty strings.
 *
 * <p>Deliberately does NOT choose an exception policy: the Recents page and the recents overlay
 * have different ones (see their call sites), and both are user-visible.
 */
final class EmojiRecents {

    /** Hoisted: a Gson builds its type-adapter chain and a TypeToken walks its generic superclass (IB-19/IB-24). */
    private static final Gson GSON = new Gson();

    private static final Type STORED_TYPE = new TypeToken<ArrayList<Object>>() {}.getType();

    private EmojiRecents() {
    }

    /**
     * The stored non-empty string entries, in stored order, at most {@code limit} of them.
     *
     * @throws com.google.gson.JsonSyntaxException if the stored value is not a JSON array
     */
    static List<String> read(SharedPreferences prefs, int limit) {
        final List<String> recents = new ArrayList<>();
        final List<Object> stored = GSON.fromJson(SettingsManager.getEmojiRecentKeys(prefs), STORED_TYPE);
        if (stored == null) {
            return recents;
        }
        for (Object entry : stored) {
            if (entry instanceof String && !((String) entry).isEmpty()) {
                recents.add((String) entry);
                if (recents.size() >= limit) {
                    break;
                }
            }
        }
        return recents;
    }

    /** Stores {@code entries} (strings and integer key codes) as the recents array. */
    static void write(SharedPreferences prefs, List<Object> entries) {
        SettingsManager.setEmojiRecentKeys(prefs, GSON.toJson(entries));
    }
}
