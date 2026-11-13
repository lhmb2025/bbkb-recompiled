package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;

/** Tells the user, once per language per process, that a keyboard is used without its language pack. */
public final class DictionaryAvailabilityNotice {

    private static final Set<String> sShown = Collections.synchronizedSet(new HashSet<>());

    private DictionaryAvailabilityNotice() {
    }

    /** Any thread; the toast is posted to main. */
    public static void packMissing(Context context, Locale locale) {
        if (locale == null) {
            return;
        }
        final String key = locale.toString();
        if (!sShown.add(key)) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String name = ResourceLocaleUtils.getSubtypeLocaleDisplayName(key);
        final int message = needsDictionaryToType(locale)
                ? R.string.dictionary_required_to_type
                : R.string.dictionary_missing_no_suggestions;
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(app, app.getString(message, name), Toast.LENGTH_LONG).show());
    }

    /**
     * Languages whose characters are assembled by the engine (Hangul syllables, kana, Chinese
     * candidates): without the pack the keyboard types nothing, not just nothing useful.
     */
    static boolean needsDictionaryToType(Locale locale) {
        return LocaleUtils.isChineseOrJapanese(locale) || "ko".equals(locale.getLanguage());
    }

    /** Lets a later attempt notify again (tests, or after a pack was installed). */
    public static void reset() {
        sShown.clear();
    }
}
