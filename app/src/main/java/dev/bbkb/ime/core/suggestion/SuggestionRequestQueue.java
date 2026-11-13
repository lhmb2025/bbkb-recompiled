package dev.bbkb.ime.core.suggestion;

import android.os.Handler;
import android.os.Message;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.textinput.InputLogic;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.BuildConfig;

/**
 * Suggestion requests for one service instance, run on the engine thread
 * ({@link EngineThreads#engineLooper()}). A request that a newer one makes pointless is dropped
 * while still queued ({@code MSG_RUN}); one whose reply somebody waits for never is
 * ({@code MSG_RUN_KEEP}). Nothing here may post with a delay: the engine looper's FIFO order is
 * what orders requests against key feeds and learning ops.
 */
public class SuggestionRequestQueue implements Handler.Callback {

    /**
     * The queue InputLogic holds before (and after) a real one is built. Every entry point
     * short-circuits on {@link #live()}: this instance has no handler, IME or InputLogic.
     */
    public static final SuggestionRequestQueue DISABLED_INSTANCE = new SuggestionRequestQueue();

    /** A queued request a later request makes pointless: dropped when the later one arrives. */
    private static final int MSG_RUN = 1;
    /** A request whose reply someone waits for (a sync update, a gesture commit): never dropped. */
    private static final int MSG_RUN_KEEP = 2;

    final Handler mHandler;

    final BlackBerryIME mIme;

    final InputLogic mInputLogic;

    private final Object mLock;

    // volatile: isSuggestionsEnabled() reads it without the lock.
    private volatile boolean mSuggestionsEnabled;

    private SuggestionRequestQueue() {
        this.mLock = new Object();
        this.mHandler = null;
        this.mIme = null;
        this.mInputLogic = null;
    }

    public SuggestionRequestQueue(BlackBerryIME ime, InputLogic inputLogic) {
        this.mLock = new Object();
        // Requests run on the shared engine thread in FIFO order with every other engine op, so a
        // key feed posted before a request is in the engine's buffer when it runs. The thread lives
        // as long as the process (the engine keeps pointers into the last caller's stack), so
        // shutdown() only drops this instance's requests.
        this.mHandler = new Handler(EngineThreads.engineLooper(), this);
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

    /** Drops this instance's pending requests; the shared engine thread stays (see EngineThreads). */
    public void shutdown() {
        if (!live()) {
            return;
        }
        this.mHandler.removeCallbacksAndMessages(null);
    }

    @Override // android.os.Handler.Callback
    public boolean handleMessage(Message message) {
        if (!live()) {
            return true;
        }
        if (BuildConfig.DEBUG) android.util.Log.d("SUGG", "SuggestionWorker.handleMessage: what=" + message.what + " thread=" + Thread.currentThread().getName());
        if (message.what == MSG_RUN || message.what == MSG_RUN_KEEP) {
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
                // Runs for plain suggestion requests too (clears wasAutoCorrected); scoping it to
                // predictions needs the gesture-commit path re-verified first.
                this.mInputLogic.mComposingTracker.enterPredictionMode();
                // Captured before dispatch so the reply carries the session it was requested under.
                final int requestGeneration = this.mInputLogic.getSessionGeneration();
                // A prediction request is also the gesture-commit channel, so it is never dropped.
                sendToWorkerThread(isPrediction ? 3 : 2, isPrediction, c0666ac -> {
                    if (c0666ac.isEmpty()) {
                        // Gesture-commit channel: the gesture word arrives via mCurrentSuggestions
                        // when the engine returns empty. Re-test gesture commit before changing.
                        SuggestedWords fallback = SuggestionRequestQueue.this.mInputLogic.mCurrentSuggestions;
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("SUGG_COMMIT_DEBUG", "dispatchSuggestionRequest callback: worker returned EMPTY"
                                + " isPrediction=" + isPrediction
                                + " fallbackSize=" + fallback.size());
                        }
                        c0666ac = fallback;
                    }
                    // Check and disable must be atomic with enable/disableSuggestions. No deadlock:
                    // the lock's other sections are short and never wait on this thread.
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
        sendToWorkerThread(what, false, callback);
    }

    /**
     * @param keep true for a request whose reply a caller waits on; false for one that the next
     *             request supersedes and that is dropped from the queue when a newer one arrives
     */
    public void sendToWorkerThread(int what, boolean keep, SuggestionEngine.SuggestionCallback callback) {
        if (!live()) {
            return;
        }
        // Each request replays the whole word in the engine, so drop queued superseded requests
        // instead of running a backlog whose replies would be discarded; a running one finishes.
        this.mHandler.removeMessages(MSG_RUN);
        this.mHandler.obtainMessage(keep ? MSG_RUN_KEEP : MSG_RUN, what, 0, callback).sendToTarget();
    }
}
