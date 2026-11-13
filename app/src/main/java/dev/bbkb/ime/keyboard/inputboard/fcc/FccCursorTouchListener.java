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

    @Override
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
                if (this.mTracking && this.mFccView.isShowing()) {
                    moveNub(motionEvent);
                    updateDirection();
                    return true;
                }
                // Intentional fall-through: a move while not tracking or hidden cancels the drag.
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

    /**
     * @param scale the target scale
     * @param durationMillis the duration in ms
     */
    private void animateScale(View view, float scale, int durationMillis) {
        view.animate().scaleX(scale).scaleY(scale).setDuration(durationMillis);
    }

    /** Follow the finger, locked to the dominant axis once past the threshold. */
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

    /**
     * @param offset the travel to clamp
     * @param isHorizontal true for the horizontal axis
     */
    private float clampTravel(float offset, boolean isHorizontal) {
        float f2 = isHorizontal ? this.mMaxTravelX : this.mMaxTravelY;
        if (offset > f2) {
            return f2;
        }
        float f3 = -f2;
        return offset < f3 ? f3 : offset;
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

    /**
     * @param offset the nub's travel
     * @param isHorizontal true when the drag is horizontal
     */
    private void scaleNubForDrag(float offset, boolean isHorizontal) {
        animateScale(this.mNub, 1.0f - (computeDragRatio(offset, isHorizontal) * 0.39999998f), 0);
    }

    /**
     * @param offset the nub's travel
     * @param isHorizontal true when the drag is horizontal
     */
    private float computeDragRatio(float offset, boolean isHorizontal) {
        float f2 = isHorizontal ? this.mMaxTravelX : this.mMaxTravelY;
        float fAbs = Math.abs(offset);
        float f3 = this.mThreshold;
        return (fAbs - f3) / (f2 - f3);
    }

    /**
     * @param baseDelayMillis the base delay in ms
     * @param isHorizontal true when the drag is horizontal
     * @param offset the nub's travel (further is faster)
     */
    private long computeRepeatDelay(long baseDelayMillis, boolean isHorizontal, float offset) {
        float f2 = baseDelayMillis;
        return Math.max((long) (f2 - ((Math.abs(offset) / (isHorizontal ? this.mMaxTravelX : this.mMaxTravelY + this.mVerticalOffset)) * f2)), 5L);
    }

    /** @param direction the direction to repeat */
    private void startRepeat(final FccView.Direction direction) {
        if (this.mFccView.getRepeatRunnable() == null || this.mCurrentDirection != direction) {
            this.mFccView.stopRepeat();
            this.mCurrentDirection = direction;
            this.mFccView.setRepeatRunnable(new Runnable() {
                @Override
                public void run() {
                    if (FccCursorTouchListener.this.mListener != null) {
                        FccCursorTouchListener.this.mListener.onMove(direction);
                    }
                    FccCursorTouchListener.this.mHandler.postDelayed(this, FccCursorTouchListener.this.mRepeatDelay);
                }
            });
            this.mHandler.postDelayed(this.mFccView.getRepeatRunnable(), 0L);
        }
    }
}
