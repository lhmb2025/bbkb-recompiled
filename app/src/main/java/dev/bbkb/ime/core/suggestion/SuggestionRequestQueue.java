package dev.bbkb.ime.core.suggestion;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.InputLogic;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.BuildConfig;

public class SuggestionRequestQueue implements Handler.Callback {

    /**
     * The queue InputLogic holds before (and after) a real one is built. Every entry point
     * short-circuits on {@link #live()}, which is what the eight no-op overrides of the old
     * anonymous subclass did: this instance has no handler thread, no IME and no InputLogic,
     * so the real bodies would NPE.
     */
    public static final SuggestionRequestQueue DISABLED_INSTANCE = new SuggestionRequestQueue();

    final Handler mHandler;

    final BlackBerryIME mIme;

    final InputLogic mInputLogic;

    private final Object mLock;

    // FIX Issue 7: volatile ensures cross-thread visibility for unsynchronized reads in isSuggestionsEnabled()
    private volatile boolean mSuggestionsEnabled;

    private SuggestionRequestQueue() {
        this.mLock = new Object();
        this.mHandler = null;
        this.mIme = null;
        this.mInputLogic = null;
    }

    public SuggestionRequestQueue(BlackBerryIME ime, InputLogic inputLogic) {
        this.mLock = new Object();
        // Use THREAD_PRIORITY_BACKGROUND for better battery/performance balance
        HandlerThread handlerThread = new HandlerThread(SuggestionRequestQueue.class.getSimpleName(),
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        this.mHandler = new Handler(handlerThread.getLooper(), this);
        this.mIme = ime;
        this.mInputLogic = inputLogic;
    }

    /** {@code false} only for {@link #DISABLED_INSTANCE}, which has no handler thread. */
    private boolean live() {
        return this.mHandler != null;
    }

    public void cancelAll() {
        if (!live()) {
            return;
        }
        this.mHandler.removeCallbacksAndMessages(null);
    }

    public void shutdown() {
        if (!live()) {
            return;
        }
        this.mHandler.getLooper().quitSafely();
    }

    @Override // android.os.Handler.Callback
    public boolean handleMessage(Message message) {
        if (!live()) {
            return true;
        }
        if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "SuggestionWorker.handleMessage: what=" + message.what + " thread=" + Thread.currentThread().getName());
        if (message.what == 1) {
            try {
                this.mIme.runSuggestionRequest(message.arg1, (SuggestionEngine.SuggestionCallback) message.obj);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) android.util.Log.e("SUGG", "SuggestionWorker.handleMessage: runSuggestionRequest threw exception", e);
            }
        }
        return true;
    }

    public void enableSuggestions() {
        if (!live()) {
            return;
        }
        synchronized (this.mLock) {
            this.mSuggestionsEnabled = true;
        }
    }

    public boolean isSuggestionsEnabled() {
        return this.mSuggestionsEnabled;
    }

    private void dispatchSuggestionRequest(final boolean isPrediction, final InputSource inputSource) {
        synchronized (this.mLock) {
            if (this.mSuggestionsEnabled) {
                // F10 (W6b): this fires for BOTH suggestion and prediction requests,
                // flipping the tracker into prediction mode and clearing wasAutoCorrected
                // even for plain suggestion requests. Scoping it to the prediction branch
                // needs gesture-dependency verification first; behavior preserved for now.
                this.mInputLogic.mComposingTracker.enterPredictionMode();
                // Audit DW-3: captured HERE, before dispatch, so the reply carries the session it
                // was requested under rather than whatever session it lands in.
                final int requestGeneration = this.mInputLogic.getSessionGeneration();
                sendToWorkerThread(isPrediction ? 3 : 2, c0666ac -> {
                    if (c0666ac.isEmpty()) {
                        // C4: this fallback doubles as the gesture-commit channel — the
                        // gesture word arrives via mCurrentSuggestions when the engine
                        // returns empty. Do not remove without re-testing gesture commit.
                        SuggestedWords fallback = SuggestionRequestQueue.this.mInputLogic.mCurrentSuggestions;
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("SUGG_COMMIT_DEBUG", "dispatchSuggestionRequest callback: worker returned EMPTY"
                                + " isPrediction=" + isPrediction
                                + " fallbackSize=" + fallback.size());
                        }
                        c0666ac = fallback;
                    }
                    // F12: the enabled check and the disable write must be atomic with
                    // enableSuggestions/disableSuggestions, which hold mLock. This runs on
                    // the worker thread; the lock's other critical sections are short and
                    // never block on this thread, so no deadlock risk.
                    synchronized (SuggestionRequestQueue.this.mLock) {
                        if (SuggestionRequestQueue.this.mSuggestionsEnabled) {
                            if (isPrediction) {
                                SuggestionRequestQueue.this.mSuggestionsEnabled = false;
                                SuggestionRequestQueue.this.mIme.uiUpdateHandler.postBatchInputSuggestions(c0666ac, inputSource);
                            } else {
                                SuggestionRequestQueue.this.mIme.uiUpdateHandler.postShowSuggestions(c0666ac, requestGeneration);
                            }
                        }
                    }
                });
            }
        }
    }

    public void requestSuggestions() {
        if (!live()) {
            return;
        }
        synchronized (this.mLock) {
            dispatchSuggestionRequest(false, InputSource.UNKNOWN);
        }
    }

    public void disableSuggestions() {
        if (!live()) {
            return;
        }
        synchronized (this.mLock) {
            this.mSuggestionsEnabled = false;
            this.mInputLogic.mComposingTracker.resetPredictionState();
        }
    }

    public void requestPredictions(InputSource inputSource) {
        if (!live()) {
            return;
        }
        synchronized (this.mLock) {
            dispatchSuggestionRequest(true, inputSource);
        }
    }

    public void sendToWorkerThread(int what, SuggestionEngine.SuggestionCallback callback) {
        if (!live()) {
            return;
        }
        this.mHandler.obtainMessage(1, what, 0, callback).sendToTarget();
    }
}
