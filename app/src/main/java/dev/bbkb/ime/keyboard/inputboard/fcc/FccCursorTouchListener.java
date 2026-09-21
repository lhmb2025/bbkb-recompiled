package dev.bbkb.ime.keyboard.inputboard.fcc;

import android.os.Handler;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;



public final class FccCursorTouchListener implements View.OnTouchListener {

    private final FccView mFccView;

    private final FccView.ActionListener mListener;

    private final Handler mHandler;

    private final ImageView mLeftArrow;

    private final ImageView mRightArrow;

    private final ImageView mUpArrow;

    private final ImageView mDownArrow;

    private final ImageView mNub;

    private final float mNubOriginalX;

    private final float mNubOriginalY;

    private float mTouchStartX;

    private float mTouchStartY;

    private final float mMaxTravelX;

    private final float mMaxTravelY;

    private boolean mTracking;

    private FccView.Direction mCurrentDirection;

    private long mRepeatDelay;

    private final float mThreshold;

    private final float mVerticalOffset;

    public FccCursorTouchListener(FccView fccView) {
        this.mFccView = fccView;
        this.mHandler = this.mFccView.getHandler();
        this.mListener = this.mFccView.getListener();
        this.mNub = this.mFccView.getFccNub();
        this.mDownArrow = this.mFccView.getDownFccArrow();
        this.mRightArrow = this.mFccView.getRightFccArrow();
        this.mLeftArrow = this.mFccView.getLeftFccArrow();
        this.mUpArrow = this.mFccView.getUpFccArrow();
        this.mNubOriginalX = this.mFccView.getFccNubOriginalX();
        this.mNubOriginalY = this.mFccView.getFccNubOriginalY();
        this.mThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12.0f, this.mFccView.getContext().getResources().getDisplayMetrics());
        float width = (this.mNub.getWidth() / 2) * 0.39999998f;
        this.mMaxTravelX = (this.mRightArrow.getLeft() - this.mNub.getRight()) + width;
        this.mMaxTravelY = (this.mDownArrow.getTop() - this.mNub.getBottom()) + width;
        this.mVerticalOffset = this.mMaxTravelY * 0.333333f;
    }

    @Override // android.view.View.OnTouchListener
    public boolean onTouch(View view, MotionEvent motionEvent) {
        switch (motionEvent.getAction()) {
            case 0: // ACTION_DOWN
                this.mTracking = true;
                this.mFccView.setNubDepressed(this.mTracking);
                this.mTouchStartX = motionEvent.getRawX();
                this.mTouchStartY = motionEvent.getRawY();
                this.mFccView.playKeyFeedback();
                return true;
            case 2: // ACTION_MOVE
                // If tracking is active and view state is valid, handle the move
                if (this.mTracking && this.mFccView.isShowing()) {
                    moveNub(motionEvent);
                    updateDirection();
                    return true;
                }
                // Fall through to cancel handler if condition fails
                // (intentional - cancels gesture when h() returns false during move)
            case 1: // ACTION_UP
            case 3: // ACTION_CANCEL
            case 5: // ACTION_POINTER_DOWN
            case 6: // ACTION_POINTER_UP
                if (this.mTracking) {
                    this.mTracking = false;
                    this.mFccView.setNubDepressed(this.mTracking);
                    this.mFccView.stopRepeat();
                    animateScaleReset(this.mNub);
                    animateNubToOrigin();
                    view.performClick();
                }
                return true;
            case 4:
            default:
                return true;
        }
    }

    private void animateNubToOrigin() {
        this.mNub.animate().x(this.mNubOriginalX).y(this.mNubOriginalY).setDuration(150L);
    }

    private void animateScaleReset(View view) {
        animateScale(view, 1.0f, 300);
    }

    private void animateScale(View view, float f, int i) {
        view.animate().scaleX(f).scaleY(f).setDuration(i);
    }

    /**
     * Audit IB-23: the X assignment was a jadx-expanded pair of Float.compare
     * ternaries that evaluated Math.abs(rawY) three times, on every ACTION_MOVE of the
     * nub drag. It says exactly this - and it is the mirror of the horizontal test on
     * the line above, which the longhand form hid.
     */
    private void moveNub(MotionEvent motionEvent) {
        float rawX = motionEvent.getRawX() - this.mTouchStartX;
        float rawY = motionEvent.getRawY() - this.mTouchStartY;
        float absX = Math.abs(rawX);
        float absY = Math.abs(rawY);
        boolean horizontalDominant = absX > this.mThreshold && absX > absY;
        boolean verticalDominant = absY > this.mThreshold && absY > absX;
        this.mNub.setX(verticalDominant
                ? this.mNubOriginalX
                : clampTravel(rawX, true) + this.mNubOriginalX);
        this.mNub.setY(horizontalDominant
                ? this.mNubOriginalY
                : clampTravel(rawY, false) + this.mNubOriginalY);
    }

    private float clampTravel(float f, boolean z) {
        float f2 = z ? this.mMaxTravelX : this.mMaxTravelY;
        if (f > f2) {
            return f2;
        }
        float f3 = -f2;
        return f < f3 ? f3 : f;
    }

    private void updateDirection() {
        float x = this.mNub.getX() - this.mNubOriginalX;
        float y = this.mNubOriginalY - this.mNub.getY();
        float f = this.mThreshold;
        boolean z = x > f || x < (-f);
        float f2 = this.mThreshold;
        if (x > f2) {
            this.mRepeatDelay = computeRepeatDelay(150L, z, x);
            startRepeat(FccView.Direction.RIGHT);
            scaleNubForDrag(x, z);
            return;
        }
        if (x < (-f2)) {
            this.mRepeatDelay = computeRepeatDelay(150L, z, x);
            startRepeat(FccView.Direction.LEFT);
            scaleNubForDrag(x, z);
        } else if (y > f2) {
            this.mRepeatDelay = computeRepeatDelay(300L, z, y);
            startRepeat(FccView.Direction.UP);
            scaleNubForDrag(y, z);
        } else if (y < (-f2)) {
            this.mRepeatDelay = computeRepeatDelay(300L, z, y);
            startRepeat(FccView.Direction.DOWN);
            scaleNubForDrag(y, z);
        } else {
            this.mFccView.stopRepeat();
            this.mCurrentDirection = null;
        }
    }

    private void scaleNubForDrag(float f, boolean z) {
        animateScale(this.mNub, 1.0f - (computeDragRatio(f, z) * 0.39999998f), 0);
    }

    private float computeDragRatio(float f, boolean z) {
        float f2 = z ? this.mMaxTravelX : this.mMaxTravelY;
        float fAbs = Math.abs(f);
        float f3 = this.mThreshold;
        return (fAbs - f3) / (f2 - f3);
    }

    private long computeRepeatDelay(long j, boolean z, float f) {
        float f2 = j;
        return Math.max((long) (f2 - ((Math.abs(f) / (z ? this.mMaxTravelX : this.mMaxTravelY + this.mVerticalOffset)) * f2)), 5L);
    }

    private void startRepeat(final FccView.Direction enumC0992a) {
        if (this.mFccView.getRepeatRunnable() == null || this.mCurrentDirection != enumC0992a) {
            this.mFccView.stopRepeat();
            this.mCurrentDirection = enumC0992a;
            this.mFccView.setRepeatRunnable(new Runnable() {
                @Override // java.lang.Runnable
                public void run() {
                    if (FccCursorTouchListener.this.mListener != null) {
                        FccCursorTouchListener.this.mListener.onMove(enumC0992a);
                    }
                    FccCursorTouchListener.this.mHandler.postDelayed(this, FccCursorTouchListener.this.mRepeatDelay);
                }
            });
            this.mHandler.postDelayed(this.mFccView.getRepeatRunnable(), 0L);
        }
    }
}
