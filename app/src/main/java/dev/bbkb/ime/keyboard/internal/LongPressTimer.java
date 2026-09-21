package dev.bbkb.ime.keyboard.internal;

import android.os.Handler;
import android.os.Looper;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;


/**
 * A one-shot long-press timer: {@link #start} arms it for {@code mDelay} ms and fires
 * {@link Callback#onTimeout} unless something cancels first.
 *
 * <p>Named {@code KeyboardCoordinates} until 2026-09-16 - a decompilation-era name that describes
 * nothing this class does and collides in meaning with the real coordinate helpers
 * ({@code CoordinateUtils}). AOSP folds this into {@link TimerHandler}; ours is separate, so it
 * gets its own truthful name.
 */
public class LongPressTimer {

    private final long mDelay;

    private Callback mCallback;

    private int mCount = 0;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mRunnable = new Runnable() {
        @Override // java.lang.Runnable
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
