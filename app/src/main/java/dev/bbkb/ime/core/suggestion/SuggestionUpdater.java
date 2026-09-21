package dev.bbkb.ime.core.suggestion;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.locale.LocaleUtils;

/**
 * Single façade for requesting suggestion-strip updates (W6a of
 * docs/archived/2026-05_composing-and-ckb-gestures/2026-05_composing-simplification_history.md; docs/archived/2026-06_fable-audits-and-gesture-rebuild/2026-06_composition-pipeline_audit.md step 5).
 *
 * <p>Historically there were three independent entry points with different timing:
 * <ol>
 *   <li>{@code InputEventContext.setShouldUpdateSuggestions()} — consumed at end-of-event by
 *       {@code BlackBerryIME.applyPostEventUpdates}, which posts a <em>delayed</em>
 *       {@code MSG_UPDATE_SUGGESTIONS} ({@code suggestionUpdateDelay} ms);</li>
 *   <li>{@code UIUpdateHandler.postUpdateSuggestions(int)} /
 *       {@code postUpdateJapaneseSuggestions(int)} — the same delayed message, called
 *       directly from manual pick, recorrection, voice commit, swipe-delete, and the
 *       diacritics bar;</li>
 *   <li>{@code SuggestionRequestQueue.requestSuggestions/requestPredictions} — an
 *       <em>immediate</em> worker-thread request, used by the gesture pipeline.</li>
 * </ol>
 *
 * <p>This class is the W6a wrapper: every call site now funnels through here, but each
 * method preserves the exact legacy route and timing of the call site it replaced.
 * Consolidating the routes themselves (and deleting the legacy entry points) is W6b.
 *
 * <p><b>Behavioral notes preserved for W6b:</b>
 * <ul>
 *   <li>Only two call sites were historically Japanese-locale-aware (end-of-event updates
 *       and the separator keystroke path); the rest always post the standard message even
 *       in Japanese locale. Hence the {@link #requestDelayedLocaleAware} /
 *       {@link #requestDelayed} split — unifying them is a W6b decision.
 *       (The two messages currently share an identical handler; they differ only in the
 *       pending-message bookkeeping consumed by {@code flushPendingSuggestions}.)</li>
 *   <li>The immediate queue path's empty-result fallback doubles as the gesture-commit
 *       channel (constraint C4) — it must not be removed or scoped without re-testing
 *       gesture commit.</li>
 *   <li>F10: {@code SuggestionRequestQueue.dispatchSuggestionRequest} calls
 *       {@code enterPredictionMode()} for <em>both</em> suggestion and prediction
 *       requests, mutating tracker flags (including {@code wasAutoCorrected}). Scoping it
 *       to predictions only needs gesture-dependency verification first — W6b.</li>
 * </ul>
 */
public final class SuggestionUpdater {

    /**
     * Why an update is being requested. {@link #legacyCode} is the historical integer
     * forwarded to the update message (and from there to the suggestion worker).
     */
    public enum Reason {
        /** A suggestion was picked; the strip should move to next-word predictions. */
        AFTER_MANUAL_PICK(0),
        /** The composing word changed; recompute current-word suggestions. */
        AFTER_KEYSTROKE(1),
        /** A gesture event finished. */
        AFTER_GESTURE(3),
        /** The cursor moved / recorrection opened a word; recompute for the word at cursor. */
        AFTER_CURSOR_MOVE(5);

        public final int legacyCode;

        Reason(int legacyCode) {
            this.legacyCode = legacyCode;
        }
    }

    private final BlackBerryIME mIme;

    public SuggestionUpdater(BlackBerryIME ime) {
        this.mIme = ime;
    }

    /**
     * Posts a delayed suggestion update, routing to the Japanese-specific message when the
     * Japanese IME is active. Used by the call sites that were historically locale-aware:
     * end-of-event updates and the separator keystroke path.
     */
    public void requestDelayedLocaleAware(Reason reason) {
        if (LocaleUtils.isCurrentSubtypeJapanese()) {
            mIme.uiUpdateHandler.postUpdateJapaneseSuggestions(reason.legacyCode);
        } else {
            mIme.uiUpdateHandler.postUpdateSuggestions(reason.legacyCode);
        }
    }

    /**
     * Posts a delayed suggestion update on the standard message, regardless of locale —
     * preserving the historical behavior of the direct {@code postUpdateSuggestions}
     * call sites (manual pick, recorrection, voice commit, swipe-delete, diacritics bar).
     */
    public void requestDelayed(Reason reason) {
        mIme.uiUpdateHandler.postUpdateSuggestions(reason.legacyCode);
    }

    /**
     * Immediate worker-thread request for current-word suggestions (the gesture/strip
     * path; bypasses the UI-handler delay).
     */
    public void requestSuggestionsNow() {
        mIme.getInputLogic().requestSuggestions(SettingsManager.getInstance().getSettingsValues());
    }

    /**
     * Immediate worker-thread request for predictions. The empty-result fallback inside
     * the queue is the gesture-commit channel (C4); see the class javadoc.
     */
    public void requestPredictionsNow(InputSource source) {
        mIme.getInputLogic().requestPredictions(source);
    }
}
