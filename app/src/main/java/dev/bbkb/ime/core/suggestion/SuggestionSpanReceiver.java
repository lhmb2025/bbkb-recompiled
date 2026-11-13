package dev.bbkb.ime.core.suggestion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;


/**
 * Receives {@code SUGGESTION_PICKED}, sent when the user picks an alternative from the system
 * long-press correction popup of a {@link android.text.style.SuggestionSpan} we attached
 * ({@link SuggestionSpanBuilder}). The framework has already substituted the word; this only
 * feeds the pick to {@link SuggestionPickLearner}.
 */
public final class SuggestionSpanReceiver extends BroadcastReceiver {

    private static final String TAG = "SuggestionSpanReceiver";

    private static final String ACTION_SUGGESTION_PICKED = "android.text.style.SUGGESTION_PICKED";

    /** Extra: the replaced (committed) word. */
    private static final String EXTRA_BEFORE = "before";

    /** Extra: the alternative the user chose. */
    private static final String EXTRA_AFTER = "after";

    /** Extra: the span's hash code. */
    private static final String EXTRA_HASH = "hashCode";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SUGGESTION_PICKED.equals(intent.getAction())) {
            return;
        }

        Bundle extras = intent.getExtras();
        String before = extras != null ? extras.getString(EXTRA_BEFORE, "<none>") : "<none>";
        String after  = extras != null ? extras.getString(EXTRA_AFTER,  "<none>") : "<none>";
        int    hash   = extras != null ? extras.getInt(EXTRA_HASH, -1) : -1;
        // Learns `after` only if it is one of the alternatives we attached to `before`.
        boolean learned = SuggestionPickLearner.onPicked(
                extras != null ? extras.getString(EXTRA_BEFORE) : null,
                extras != null ? extras.getString(EXTRA_AFTER) : null);
        if (BuildConfig.DEBUG) Log.i(TAG, "SUGGESTION_PICKED learned=" + learned);

        if (BuildConfig.DEBUG) {
        Log.i(TAG, "SUGGESTION_PICKED: before=\"" + before + "\" after=\"" + after
                + "\" hash=" + hash + " — no IME action taken (framework substituted word)");
        }

        if (BuildConfig.DEBUG) {
            StringBuilder sb = new StringBuilder("SUGGESTION_PICKED full extras:");
            if (extras != null) {
                for (String key : extras.keySet()) {
                    sb.append(" [").append(key).append("=").append(extras.get(key)).append("]");
                }
            } else {
                sb.append(" <null>");
            }
            if (BuildConfig.DEBUG) Log.d(TAG, sb.toString());
        }
    }
}
