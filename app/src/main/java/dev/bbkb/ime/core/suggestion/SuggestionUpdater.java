package dev.bbkb.ime.core.suggestion;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.locale.LocaleUtils;

/**
 * Single façade for suggestion-strip updates. Delayed requests post {@code MSG_UPDATE_SUGGESTIONS}
 * (or the Japanese variant) via {@code UIUpdateHandler}; "now" requests go straight to the worker.
 *
 * <p>The immediate path's empty-result fallback doubles as the gesture-commit channel: re-test
 * gesture commit before changing it. {@code dispatchSuggestionRequest} calls
 * {@code enterPredictionMode()} for both request kinds, mutating tracker flags.
 */
public final class SuggestionUpdater {
    /** Why an update is requested; {@link #legacyCode} is forwarded to the message and the worker. */
    public enum Reason {
        /** A suggestion was picked; the strip should move to next-word predictions. */
        AFTER_MANUAL_PICK(0),
        /** The composing word changed; recompute current-word suggestions. */
        AFTER_KEYSTROKE(1),
        /** A gesture event finished. */
        AFTER_GESTURE(3),
        /** The cursor moved or recorrection opened a word. */
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
     * Delayed update, on the Japanese message when the Japanese IME is active (end-of-event and
     * separator paths). The two messages differ only in {@code flushPendingSuggestions} bookkeeping.
     */
    public void requestDelayedLocaleAware(Reason reason) {
        if (LocaleUtils.isCurrentSubtypeJapanese()) {
            mIme.uiUpdateHandler.postUpdateJapaneseSuggestions(reason.legacyCode);
        } else {
            mIme.uiUpdateHandler.postUpdateSuggestions(reason.legacyCode);
        }
    }

    /** Delayed update on the standard message regardless of locale (pick, recorrection, voice, etc.). */
    public void requestDelayed(Reason reason) {
        mIme.uiUpdateHandler.postUpdateSuggestions(reason.legacyCode);
    }

    /** Immediate worker-thread request for current-word suggestions, bypassing the handler delay. */
    public void requestSuggestionsNow() {
        mIme.getInputLogic().requestSuggestions(SettingsManager.getInstance().getSettingsValues());
    }

    /** Immediate worker-thread request for predictions (see the gesture-commit note above). */
    public void requestPredictionsNow(InputSource source) {
        mIme.getInputLogic().requestPredictions(source);
    }
}
