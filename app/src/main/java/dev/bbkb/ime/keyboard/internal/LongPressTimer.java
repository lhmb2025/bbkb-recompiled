package dev.bbkb.ime.keyboard.internal;

import android.os.Handler;
import android.os.Looper;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;


/**
 * One-shot long-press timer: {@link #start} arms it for {@code mDelay} ms and fires
 * {@link Callback#onTimeout} unless cancelled first.
 */
public class LongPressTimer {

    private final long mDelay;

    private Callback mCallback;

    private int mCount = 0;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            LongPressTimer.this.mCount = 2;
            LongPressTimer.this.mCallback.onTimeout();
        }
    };

    
    public interface Callback {
        void onTimeout();
    }

    public LongPressTimer() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        this.mDelay = settings == null ? 300L : settings.keyLongpressTimeoutMs;
    }

    public void start() {
        startWithDelay(this.mDelay);
    }

    /** @param j delay in ms */
    public void startWithDelay(long j) {
        if (this.mCount != 0) {
            throw new IllegalStateException("Timer is already running!");
        }
        if (this.mCallback == null) {
            throw new IllegalStateException("Listener was never instantiated!");
        }
        this.mCount = 1;
        this.mHandler.removeCallbacks(this.mRunnable);
        this.mHandler.postDelayed(this.mRunnable, j);
    }

    public void cancel() {
        if (this.mCount == 0) {
            return;
        }
        this.mCount = 0;
        this.mHandler.removeCallbacks(this.mRunnable);
    }

    public void setCallback(Callback aVar) {
        this.mCallback = aVar;
    }

    public int getCount() {
        return this.mCount;
    }
}
