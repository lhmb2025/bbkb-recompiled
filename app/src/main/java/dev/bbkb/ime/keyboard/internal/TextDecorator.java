package dev.bbkb.ime.keyboard.internal;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import android.view.inputmethod.CursorAnchorInfo;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.core.textinput.CapsModeUtils;
import dev.bbkb.ime.BuildConfig;


public class TextDecorator {

    private static final String TAG = "TextDecorator";

    private static final Listener EMPTY_LISTENER = new Listener() {
        @Override
        public void onWordCommit(String word) {
            // No-op default.
        }
    };

    private static final TextDecoratorUiOperator EMPTY_TIMER_PROXY = new TextDecoratorUiOperator() {
        @Override
        public void cancel() {
            // No-op default.
        }

        @Override
        public void showIndicator(Matrix matrix, RectF rectF, boolean showAtLeftSide) {
            // No-op default.
        }

        @Override
        public void setOnClickHandler(Runnable runnable) {
            // No-op default.
        }

        @Override
        public void hide() {
            // No-op default.
        }
    };

    private final Listener mListener;

    private int mState = 0;

    private String mLastComposingText = null;

    private boolean mIsRtl = false;

    private RectF mComposingTextRect = new RectF();

    private boolean mShouldShow = false;

    private String mWaitingWord = null;

    private int mWaitingFlags = 0;

    private int mWaitingSelectionStart = -1;

    private int mWaitingSelectionEnd = -1;

    private CursorAnchorInfo mCursorAnchorInfo = null;

    private TextDecoratorUiOperator mTimerProxy = EMPTY_TIMER_PROXY;

    private final Runnable mOnClickHandler = new Runnable() {
        @Override
        public void run() {
            TextDecorator.this.commitWaitingWord();
        }
    };

    private final UpdateHandler mUpdateHandler = new UpdateHandler(this);

    
    public interface Listener {
        void onWordCommit(String word);
    }

    public TextDecorator(Listener listener) {
        this.mListener = listener == null ? EMPTY_LISTENER : listener;
    }

    public void setTimerProxy(TextDecoratorUiOperator textDecoratorUiOperator) {
        this.mTimerProxy.cancel();
        this.mTimerProxy = textDecoratorUiOperator;
        this.mTimerProxy.setOnClickHandler(getOnClickHandler());
    }


    final Runnable getOnClickHandler() {
        return this.mOnClickHandler;
    }

    /**
     * @param word the committed word
     * @param shiftStateAtCommit commit flags
     * @param selectionStart selection start
     * @param selectionEnd selection end
     */
    public void showCommitIndicator(String word, int shiftStateAtCommit, int selectionStart, int selectionEnd) {
        this.mWaitingFlags = shiftStateAtCommit;
        this.mWaitingWord = word;
        this.mWaitingSelectionStart = selectionStart;
        this.mWaitingSelectionEnd = selectionEnd;
        this.mState = 1;
        postUpdateState();
    }

    /** @param shouldShow true if the indicator may be shown */
    public void setShouldShow(boolean shouldShow) {
        boolean z2 = this.mShouldShow != shouldShow;
        this.mShouldShow = shouldShow;
        if (z2) {
            postUpdateState();
        }
    }

    public void reset() {
        this.mWaitingWord = null;
        this.mWaitingFlags = 0;
        this.mState = 0;
        this.mWaitingSelectionStart = -1;
        this.mWaitingSelectionEnd = -1;
        hideIndicator("Resetting internal state.");
    }

    public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        this.mCursorAnchorInfo = cursorAnchorInfo;
        postUpdateWithCursorAnchorInfo();
    }

    private void dismiss(String reason) {
        this.mTimerProxy.hide();
        if (BuildConfig.DEBUG) Log.d(TAG, reason);
    }

    private void hideIndicator(String reason) {
        this.mTimerProxy.hide();
        if (BuildConfig.DEBUG) Log.d(TAG, reason);
    }

    private void postUpdateState() {
        this.mUpdateHandler.postUpdate();
    }

    private void postUpdateWithCursorAnchorInfo() {
        this.mUpdateHandler.postUpdateWithAnchorInfo();
        updateCommitIndicator();
    }

        void updateCommitIndicator() {
        CursorAnchorInfo cursorAnchorInfo = this.mCursorAnchorInfo;
        if (cursorAnchorInfo == null) {
            hideIndicator("CursorAnchorInfo isn't available.");
            return;
        }
        Matrix matrixM3896e = cursorAnchorInfo.getMatrix();
        if (matrixM3896e == null) {
            dismiss("Matrix is null");
        }
        CharSequence charSequenceM3894c = cursorAnchorInfo.getComposingText();
        if (!TextUtils.isEmpty(charSequenceM3894c)) {
            int iM3895d = cursorAnchorInfo.getComposingTextStart();
            int length = (charSequenceM3894c.length() + iM3895d) - 1;
            RectF rectFM3891a = cursorAnchorInfo.getCharacterBounds(length);
            boolean z = (cursorAnchorInfo.getCharacterBoundsFlags(length) & 2) != 0;
            if (rectFM3891a == null || matrixM3896e == null || z) {
                this.mTimerProxy.hide();
                return;
            }
            String string = charSequenceM3894c.toString();
            float f = rectFM3891a.top;
            float f2 = rectFM3891a.bottom;
            float f3 = rectFM3891a.left;
            float fMax = rectFM3891a.right;
            float fMin = f3;
            boolean z2 = false;
            for (int length2 = charSequenceM3894c.length() - 1; length2 >= 0; length2--) {
                int i = iM3895d + length2;
                RectF rectFM3891a2 = cursorAnchorInfo.getCharacterBounds(i);
                int iM3893b = cursorAnchorInfo.getCharacterBoundsFlags(i);
                if (rectFM3891a2 == null || rectFM3891a2.top != f || rectFM3891a2.bottom != f2) {
                    break;
                }
                if ((iM3893b & 4) != 0) {
                    z2 = true;
                }
                fMin = Math.min(rectFM3891a2.left, fMin);
                fMax = Math.max(rectFM3891a2.right, fMax);
            }
            this.mLastComposingText = string;
            this.mIsRtl = z2;
            this.mComposingTextRect.set(fMin, f, fMax, f2);
        }
        int iM3890a = cursorAnchorInfo.getSelectionStart();
        int iM3892b = cursorAnchorInfo.getSelectionEnd();
        switch (this.mState) {
            case 0:
                this.mTimerProxy.hide();
                return;
            case 1:
                if (iM3890a != this.mWaitingSelectionStart || iM3892b != this.mWaitingSelectionEnd) {
                    this.mTimerProxy.hide();
                    return;
                } else {
                    this.mState = 2;
                    break;
                }
            case 2:
                if (iM3890a != this.mWaitingSelectionStart || iM3892b != this.mWaitingSelectionEnd) {
                    this.mTimerProxy.hide();
                    this.mState = 0;
                    this.mWaitingSelectionStart = -1;
                    this.mWaitingSelectionEnd = -1;
                    return;
                }
                break;
            default:
                dismiss("Unexpected internal mode=" + this.mState);
                return;
        }
        if (!TextUtils.equals(this.mLastComposingText, this.mWaitingWord)) {
            dismiss("mLastComposingText doesn't match mWaitingWord");
        } else if ((cursorAnchorInfo.getInsertionMarkerFlags() & 2) != 0) {
            this.mTimerProxy.hide();
        } else {
            this.mTimerProxy.showIndicator(matrixM3896e, this.mComposingTextRect, this.mIsRtl);
        }
    }

        void commitWaitingWord() {
        if (this.mState != 2) {
            return;
        }
        if (CapsModeUtils.shouldLowercaseWord(this.mWaitingFlags)) {
            this.mListener.onWordCommit(this.mWaitingWord.toLowerCase(SettingsManager.getInstance().getSettingsValues().locale));
        } else {
            this.mListener.onWordCommit(this.mWaitingWord);
        }
    }

    
    private static final class UpdateHandler {

        private final HandlerC1675a mHandler;

        /** @param textDecorator the owner, held weakly */
        public UpdateHandler(TextDecorator textDecorator) {
            this.mHandler = new HandlerC1675a(textDecorator);
        }

        
        private static final class HandlerC1675a extends WeakOwnerHandler<TextDecorator> {
            /** @param textDecorator the owner, held weakly */
            public HandlerC1675a(TextDecorator textDecorator) {
                super(textDecorator);
            }

            @Override
            public void handleMessage(Message message) {
                TextDecorator c1074pV = getOwner();
                if (c1074pV != null && message.what == 0) {
                    c1074pV.updateCommitIndicator();
                }
            }
        }

        public void postUpdate() {
            if (this.mHandler.hasMessages(0)) {
                return;
            }
            this.mHandler.obtainMessage(0).sendToTarget();
        }

        public void postUpdateWithAnchorInfo() {
            this.mHandler.removeMessages(0);
        }
    }
}
