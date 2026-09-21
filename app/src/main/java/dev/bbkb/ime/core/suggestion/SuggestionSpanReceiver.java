package dev.bbkb.ime.core.suggestion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;


/**
 * BroadcastReceiver for Android's {@code android.text.style.SUGGESTION_PICKED} intent.
 *
 * <p>Android fires this broadcast whenever the user picks an alternative from a
 * {@link android.text.style.SuggestionSpan} popup (the long-press-on-word correction menu
 * shown by the system text view, <em>not</em> the IME suggestion strip). The IME attaches
 * {@code SuggestionSpan} instances to committed words via {@link SuggestionSpanBuilder};
 * this receiver is the notification class registered on those spans.
 *
 * <p><b>Current status</b>: The receiver fires correctly (confirmed via the logging below)
 * but takes no action. The picked suggestion has already been substituted into the editor
 * by the Android framework before this broadcast is sent — the IME does not need to do
 * anything to apply the replacement. However, the following post-pick actions are currently
 * <em>not</em> performed and may be desirable:
 * <ul>
 *   <li>Notifying the learning subsystem of the user-chosen alternative (word learning)</li>
 *   <li>Resetting suggestion state so the next suggestion request reflects the new word</li>
 *   <li>Logging telemetry for suggestion acceptance rate</li>
 * </ul>
 *
 * <p>See {@code docs/archived/2026-03_bug-hunts-and-kotlin/2026-03_suggestion-span-receiver_plan.md} for the full investigation and test plan.
 */
public final class SuggestionSpanReceiver extends BroadcastReceiver {

    private static final String TAG = "SuggestionSpanReceiver";

    /**
     * The action broadcast by Android when the user picks a suggestion from a
     * {@link android.text.style.SuggestionSpan} popup.
     */
    private static final String ACTION_SUGGESTION_PICKED = "android.text.style.SUGGESTION_PICKED";

    /**
     * Extra key: the text that was replaced (the original committed word).
     * Present in API 21+.
     */
    private static final String EXTRA_BEFORE = "before";

    /**
     * Extra key: the suggestion text chosen by the user.
     * Present in API 21+.
     */
    private static final String EXTRA_AFTER = "after";

    /**
     * Extra key: character position in the text where the replacement occurred.
     * Present in API 21+.
     */
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
        // The user preferred `after` over the word we committed: teach the engine, provided
        // `after` is one of the alternatives we attached to `before` (see SuggestionPickLearner).
        boolean learned = SuggestionPickLearner.onPicked(
                extras != null ? extras.getString(EXTRA_BEFORE) : null,
                extras != null ? extras.getString(EXTRA_AFTER) : null);
        if (BuildConfig.DEBUG) Log.i(TAG, "SUGGESTION_PICKED learned=" + learned);

        // Always log at INFO so we can confirm the receiver fires on device without
        // a debug build. This is the key observable needed for Step 5.4 investigation.
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

        // TODO(5.4): Notify learning subsystem of the user-picked alternative.
        //   The 'after' string is the word the user chose. Feed it to
        //   DynamicLearningManager or NuanceSDKManager.addWord() here once
        //   the learning coordination path is established (Phase 9 item).
    }
}
