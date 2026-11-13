package dev.bbkb.ime.core.gesture;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.shared.Logger;

import java.util.Arrays;
import dev.bbkb.ime.BuildConfig;



public class MultiPointerGestureDetector {

    private static final int LONGPRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    private static final int TAP_TIMEOUT = ViewConfiguration.getTapTimeout();

    private static final int DOUBLE_TAP_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();

    private static final double DIRECTION_COS_SQ_THRESHOLD = Math.pow(Math.cos(0.436d), 2.0d);

    private boolean isLongpressEnabled;

    private VelocityTracker velocityTracker;

    private int lastDeviceId;

    private final BlackBerryIME ime;

    private int touchSlopSquare;

    private int doubleTapTouchSlopSquare;

    private int doubleTapSlopSquare;

    private int minimumFlingVelocity;

    private int maximumFlingVelocity;

    private final Handler handler;

    private final OnGestureListener gestureListener;

    private OnDoubleTapListener doubleTapListener;

    /* The previous ACTION_MOVE's pointer positions, indexed by pointer id. */
    private static final int MAX_POINTER_ID = 32;
    private final float[] previousMoveX = new float[MAX_POINTER_ID];
    private final float[] previousMoveY = new float[MAX_POINTER_ID];
    /** Bit n set: pointer id n was in the previous move. 0 when there is no previous move. */
    private int previousMovePointerIds;
    private int previousMovePointerCount;

    private boolean inLongPress;

    private int lastTapPointerId;

    private boolean hasMovedBeyondSlop;

    private float lastFocusX;

    private float lastFocusY;

    private final SparseBooleanArray stillDownByPointer = new SparseBooleanArray(2);

    private final SparseBooleanArray deferConfirmSingleTapByPointer = new SparseBooleanArray(2);

    private final SparseBooleanArray confirmSingleTapOnUpByPointer = new SparseBooleanArray(2);

    private final SparseBooleanArray alwaysInTapRegionByPointer = new SparseBooleanArray(2);

    private final SparseBooleanArray alwaysInBiggerTapRegionByPointer = new SparseBooleanArray(2);

    private final SparseArray<MotionEvent> downEventByPointer = new SparseArray<>(2);

    private final SparseArray<MotionEvent> upEventByPointer = new SparseArray<>(2);

    private int activeDoubleTapPointerId = -1;

    /**
     * The keypad-coordinate rectangle a double-tap must stay inside on a touch-keypad device,
     * sized from {@link CkbKeyGrid} (1080x525 on athena), shared with the arbiter and Gesture Lab.
     */
    private final Rect doubleTapRegion = new Rect(
            0, 0,
            dev.bbkb.ime.core.gesture.arbiter.CkbKeyGrid.getWidth(),
            dev.bbkb.ime.core.gesture.arbiter.CkbKeyGrid.getHeight());

    
    public interface OnDoubleTapListener {
        boolean onDoubleTap(MotionEvent motionEvent);

        boolean onDoubleTapEvent(MotionEvent motionEvent);

        boolean onSingleTapConfirmed(MotionEvent motionEvent);
    }

    
    public interface OnGestureListener {
        void onLongPress(MotionEvent motionEvent);

        /**
         * @param velocityX x velocity in px/s (the focus x while dragging after a long press)
         * @param velocityY y velocity in px/s (the focus y while dragging after a long press)
         */
        boolean onFling(MotionEvent motionEvent, MotionEvent e2, float velocityX, float velocityY);

        boolean onDown(MotionEvent motionEvent);

        /**
         * @param distanceX focus x of the event
         * @param distanceY focus y of the event
         */
        boolean onScroll(MotionEvent motionEvent, MotionEvent e2, float distanceX, float distanceY);

        boolean onSingleTapUp(MotionEvent motionEvent);

        void onShowPress(MotionEvent motionEvent);
    }

    
    public static class SimpleOnGestureListener implements OnDoubleTapListener, OnGestureListener {
        public void onLongPress(MotionEvent motionEvent) {
            // No-op default.
        }

        public boolean onFling(MotionEvent motionEvent, MotionEvent e2, float velocityX, float velocityY) {
            return false;
        }

        public boolean onDown(MotionEvent motionEvent) {
            return false;
        }

        public boolean onScroll(MotionEvent motionEvent, MotionEvent e2, float distanceX, float distanceY) {
            return false;
        }

        public boolean onSingleTapUp(MotionEvent motionEvent) {
            return false;
        }

        public void onShowPress(MotionEvent motionEvent) {
            // No-op default.
        }

        @Override // MultiPointerGestureDetector.OnDoubleTapListener
        public boolean onDoubleTap(MotionEvent motionEvent) {
            return false;
        }

        @Override // MultiPointerGestureDetector.OnDoubleTapListener
        public boolean onDoubleTapEvent(MotionEvent motionEvent) {
            return false;
        }

        @Override // MultiPointerGestureDetector.OnDoubleTapListener
        public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
            return false;
        }
    }

    
    private class GestureHandler extends Handler {
        GestureHandler() {
            // The tap and long-press timers belong on the main thread; the no-arg Handler() would
            // bind to the constructing thread's Looper.
            super(Looper.getMainLooper());
        }

        GestureHandler(Handler handler) {
            super(handler.getLooper());
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            switch (message.what) {
                case 1:
                    MotionEvent showPressEvent = (MotionEvent) MultiPointerGestureDetector.this.downEventByPointer.get(message.arg1);
                    if (showPressEvent != null) {
                        MultiPointerGestureDetector.this.gestureListener.onShowPress(showPressEvent);
                    }
                    return;
                case 2:
                    MultiPointerGestureDetector.this.dispatchLongPress();
                    return;
                case 3:
                    int i = message.arg1;
                    if (MultiPointerGestureDetector.this.doubleTapListener != null) {
                        if (!MultiPointerGestureDetector.this.stillDownByPointer.get(i)) {
                            MotionEvent singleTapEvent = (MotionEvent) MultiPointerGestureDetector.this.downEventByPointer.get(i);
                            if (singleTapEvent != null) {
                                MultiPointerGestureDetector.this.doubleTapListener.onSingleTapConfirmed(singleTapEvent);
                            }
                            return;
                        } else {
                            MultiPointerGestureDetector.this.deferConfirmSingleTapByPointer.put(i, true);
                            return;
                        }
                    }
                    return;
                default:
                    throw new RuntimeException("Unknown message " + message);
            }
        }
    }

    public MultiPointerGestureDetector(Context context, OnGestureListener listener, Handler handler, BlackBerryIME blackBerryIME) {
        if (handler != null) {
            this.handler = new GestureHandler(handler);
        } else {
            this.handler = new GestureHandler();
        }
        this.gestureListener = listener;
        if (listener instanceof OnDoubleTapListener) {
            setOnDoubleTapListener((OnDoubleTapListener) listener);
        }
        init(context);
        this.ime = blackBerryIME;
    }

    private void init(Context context) {
        if (this.gestureListener == null) {
            throw new NullPointerException("OnGestureListener must not be null");
        }
        if (context == null) {
            throw new NullPointerException("context must not be null");
        }
        this.isLongpressEnabled = true;
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        int scaledTouchSlop = viewConfiguration.getScaledTouchSlop();
        int scaledDoubleTapSlop = viewConfiguration.getScaledDoubleTapSlop();
        this.minimumFlingVelocity = viewConfiguration.getScaledMinimumFlingVelocity();
        this.maximumFlingVelocity = viewConfiguration.getScaledMaximumFlingVelocity();
        int i = scaledTouchSlop * scaledTouchSlop;
        this.touchSlopSquare = i;
        this.doubleTapTouchSlopSquare = i;
        this.doubleTapSlopSquare = scaledDoubleTapSlop * scaledDoubleTapSlop;
    }

    public void setOnDoubleTapListener(OnDoubleTapListener dtListener) {
        this.doubleTapListener = dtListener;
    }

    /** @param isLongpressEnabled true to detect long presses */
    public void setIsLongpressEnabled(boolean isLongpressEnabled) {
        this.isLongpressEnabled = isLongpressEnabled;
    }

    public boolean onTouchEvent(MotionEvent motionEvent) {
        int i = -1;
        int pointerId = 0;
        boolean zMo4682e = false;
        MotionEvent motionEvent2;
        boolean zM6020c;
        OnDoubleTapListener dtListener;
        VelocityTracker velocityTracker;
        MotionEvent motionEvent3;
        if (this.lastDeviceId != motionEvent.getDeviceId()) {
            if (this.velocityTracker != null) {
                cancel();
            }
            this.lastDeviceId = motionEvent.getDeviceId();
        }
        if (this.velocityTracker == null) {
            this.velocityTracker = VelocityTracker.obtain();
        }
        this.velocityTracker.addMovement(motionEvent);
        int actionMasked = motionEvent.getActionMasked();
        int actionIndex = motionEvent.getActionIndex();
        boolean zMo4683f = false;

        if (actionMasked == 1) {
            i = -1;
            pointerId = motionEvent.getPointerId(actionIndex);
        } else {
            switch (actionMasked) {
                case 5:
                    i = -1;
                    pointerId = motionEvent.getPointerId(actionIndex);
                    break;
                case 6:
                    i = actionIndex;
                    pointerId = motionEvent.getPointerId(actionIndex);
                    break;
                default:
                    i = -1;
                    pointerId = 0;
                    break;
            }
        }
        
        int pointerCount = motionEvent.getPointerCount();
        float x = 0.0f;
        float y = 0.0f;
        for (int i2 = 0; i2 < pointerCount; i2++) {
            if (i != i2) {
                x += motionEvent.getX(i2);
                y += motionEvent.getY(i2);
            }
        }
        float f = i != -1 ? pointerCount - 1 : pointerCount;
        float f2 = x / f;
        float f3 = y / f;

        switch (actionMasked) {
            case 0:
                this.hasMovedBeyondSlop = false;
                if (this.lastTapPointerId != pointerId) {
                    zMo4682e = this.activeDoubleTapPointerId != -1 ? cancelDoubleTapTracking(motionEvent) | false : false;
                    cancelTaps();
                    this.lastTapPointerId = pointerId;
                } else {
                    zMo4682e = false;
                }
                motionEvent2 = this.downEventByPointer.get(pointerId);
                if (this.doubleTapListener != null) {
                    boolean zHasMessages = this.handler.hasMessages(3);
                    if (zHasMessages) {
                        this.handler.removeMessages(3);
                    }
                    MotionEvent motionEvent4 = this.upEventByPointer.get(pointerId);
                    if (motionEvent2 != null && motionEvent4 != null && zHasMessages && isConsideredDoubleTap(motionEvent2, motionEvent4, motionEvent, pointerId)) {
                        this.activeDoubleTapPointerId = pointerId;
                        zMo4682e = zMo4682e | this.doubleTapListener.onDoubleTap(motionEvent2) | this.doubleTapListener.onDoubleTapEvent(motionEvent);
                    } else {
                        this.handler.sendMessageDelayed(Message.obtain(this.handler, 3, pointerId, 0), DOUBLE_TAP_TIMEOUT);
                    }
                }
                this.lastFocusX = f2;
                this.lastFocusY = f3;
                if (motionEvent2 != null) {
                    motionEvent2.recycle();
                }
                MotionEvent motionEventObtain = MotionEvent.obtain(motionEvent);
                this.downEventByPointer.put(pointerId, motionEventObtain);
                this.alwaysInTapRegionByPointer.put(pointerId, true);
                this.alwaysInBiggerTapRegionByPointer.put(pointerId, true);
                this.stillDownByPointer.put(pointerId, true);
                this.inLongPress = false;
                this.deferConfirmSingleTapByPointer.put(pointerId, false);
                this.confirmSingleTapOnUpByPointer.put(pointerId, true);
                if (this.isLongpressEnabled) {
                    this.handler.removeMessages(2);
                    if (actionMasked == 0) {
                        this.handler.sendEmptyMessageAtTime(2, motionEventObtain.getEventTime() + TAP_TIMEOUT + LONGPRESS_TIMEOUT);
                    }
                }
                this.handler.sendMessageAtTime(Message.obtain(this.handler, 1, pointerId, 0), motionEventObtain.getEventTime() + TAP_TIMEOUT);
                return zMo4682e | this.gestureListener.onDown(motionEvent);
            case 1:
                if (!this.stillDownByPointer.get(pointerId)) {
                    if (BuildConfig.DEBUG) Log.w("IMEGestureDetector", "No matching down event for event:" + motionEvent);
                    return false;
                }
                MotionEvent motionEventObtain2 = MotionEvent.obtain(motionEvent);
                updateTapRegions(pointerId, motionEventObtain2, actionIndex);
                int i3 = this.activeDoubleTapPointerId;
                if (i3 != -1) {
                    if (i3 == pointerId && this.alwaysInBiggerTapRegionByPointer.get(i3)) {
                        zM6020c = this.doubleTapListener.onDoubleTapEvent(motionEvent) | false;
                        this.activeDoubleTapPointerId = -1;
                    } else {
                        zM6020c = cancelDoubleTapTracking(motionEvent) | false;
                    }
                } else {
                    zM6020c = false;
                }
                // Fires on every qualifying UP: ImeGestureListener needs it to update previousTapDownTime,
                // or its double-tap suppression check is always true and FCC never activates.
                zM6020c |= this.gestureListener.onSingleTapUp(motionEvent);
                if (this.inLongPress && !this.deferConfirmSingleTapByPointer.get(pointerId)) {
                    zM6020c |= this.gestureListener.onScroll(motionEvent, motionEvent, f2, f3);
                    if (this.isLongpressEnabled) {
                        this.handler.removeMessages(2);
                        this.handler.sendEmptyMessageAtTime(2, motionEventObtain2.getEventTime() + TAP_TIMEOUT + LONGPRESS_TIMEOUT);
                    }
                }
                if (this.isLongpressEnabled && (dtListener = this.doubleTapListener) != null && this.confirmSingleTapOnUpByPointer.get(pointerId)) {
                    MotionEvent motionEvent5 = this.downEventByPointer.get(pointerId);
                    if (motionEvent5 != null) {
                        zM6020c |= dtListener.onSingleTapConfirmed(motionEvent5);
                    }
                }
                if ((velocityTracker = this.velocityTracker) != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    if (checkFling(motionEvent)) {
                        zM6020c = true;
                    }
                }
                this.handler.removeMessages(1);
                this.handler.removeMessages(3);
                // Keep the first DOWN (recycled at the next ACTION_DOWN) and store this UP:
                // isConsideredDoubleTap needs both on the second ACTION_DOWN.
                MotionEvent prevUp = this.upEventByPointer.get(pointerId);
                if (prevUp != null) {
                    prevUp.recycle();
                }
                this.upEventByPointer.put(pointerId, motionEventObtain2);
                // Keep alwaysInBiggerTapRegionByPointer: isConsideredDoubleTap checks it first
                // (reset at the next ACTION_DOWN).
                this.alwaysInTapRegionByPointer.delete(pointerId);
                this.stillDownByPointer.delete(pointerId);
                this.deferConfirmSingleTapByPointer.delete(pointerId);
                this.confirmSingleTapOnUpByPointer.delete(pointerId);
                return zM6020c;
            case 2:
                this.lastFocusX = f2;
                this.lastFocusY = f3;
                for (int i4 = 0; i4 < pointerCount; i4++) {
                    int pointerId2 = motionEvent.getPointerId(i4);
                    MotionEvent motionEvent6 = this.downEventByPointer.get(pointerId2);
                    if (motionEvent6 != null) {
                        // Both callees only read getX/getY(index), so no copy of the live event is needed.
                        updateTapRegions(pointerId2, motionEvent, i4);
                        // The cheap previous-move checks first: shouldHandleGestureEvent reads
                        // the device profile and settings, per pointer per move.
                        if (this.previousMovePointerIds != 0 && i4 < this.previousMovePointerCount
                                && hadPreviousMovePointer(pointerId2)
                                && this.ime != null && this.ime.shouldHandleGestureEvent(motionEvent)) {
                            updateConfirmSingleTapEligibility(pointerId2, motionEvent, i4);
                        }
                    }
                }
                if (this.doubleTapListener != null) {
                    if (this.activeDoubleTapPointerId != -1 && this.alwaysInBiggerTapRegionByPointer.get(this.activeDoubleTapPointerId)) {
                        zMo4683f = this.doubleTapListener.onDoubleTapEvent(motionEvent) | false;
                    } else if (this.activeDoubleTapPointerId != -1) {
                        zMo4683f = cancelDoubleTapTracking(motionEvent) | false;
                    }
                    // else: no double tap is tracked, so nothing to cancel (as in AOSP).
                }
                if (this.inLongPress) {
                    MotionEvent motionEvent8 = this.downEventByPointer.get(pointerId);
                    if (motionEvent8 != null) {
                        zMo4683f |= this.gestureListener.onFling(motionEvent8, motionEvent, f2, f3);
                    }
                }
                // downEventByPointer must keep only the ACTION_DOWN (fling velocity uses it);
                // move positions are remembered separately for direction detection.
                MotionEvent previousEvent = this.downEventByPointer.get(pointerId);
                if (previousEvent != null) {
                    rememberMovePositions(motionEvent);
                }
                return zMo4683f;
            case 3:
                cancel();
                return false;
            case 5:
                this.lastFocusX = f2;
                this.lastFocusY = f3;
                return false;
            case 6:
                if (!this.stillDownByPointer.get(pointerId)) {
                    if (BuildConfig.DEBUG) Log.w("IMEGestureDetector", "No matching down event for event:" + motionEvent);
                    return false;
                }
                // updateTapRegions only reads coordinates, so the live event is passed.
                updateTapRegions(pointerId, motionEvent, actionIndex);
                int i5 = this.activeDoubleTapPointerId;
                if (i5 != -1) {
                    if (i5 == pointerId && this.alwaysInBiggerTapRegionByPointer.get(i5)) {
                        zMo4682e = this.doubleTapListener.onDoubleTapEvent(motionEvent) | false;
                        this.activeDoubleTapPointerId = -1;
                    } else {
                        zMo4682e = cancelDoubleTapTracking(motionEvent) | false;
                    }
                }
                if (this.doubleTapListener != null && this.confirmSingleTapOnUpByPointer.get(pointerId)) {
                    zMo4682e |= this.doubleTapListener.onDoubleTapEvent(motionEvent);
                }
                this.downEventByPointer.remove(pointerId);
                this.upEventByPointer.remove(pointerId);
                this.alwaysInTapRegionByPointer.delete(pointerId);
                this.alwaysInBiggerTapRegionByPointer.delete(pointerId);
                this.stillDownByPointer.delete(pointerId);
                this.deferConfirmSingleTapByPointer.delete(pointerId);
                this.confirmSingleTapOnUpByPointer.delete(pointerId);
                return zMo4682e;
            default:
                if (BuildConfig.DEBUG) Log.w("IMEGestureDetector", "Unexpected action in event:" + motionEvent);
                return false;
        }
    }

    /**
     * @param x1 x of the first point
     * @param y1 y of the first point
     * @param x2 x of the second point
     * @param y2 y of the second point
     */
    private static float distanceSquared(float x1, float y1, float x2, float y2) {
        final float dx = x1 - x2;
        final float dy = y1 - y2;
        return (dx * dx) + (dy * dy);
    }

    private void rememberMovePositions(MotionEvent motionEvent) {
        int ids = 0;
        int count = motionEvent.getPointerCount();
        for (int index = 0; index < count; index++) {
            int id = motionEvent.getPointerId(index);
            if (id >= 0 && id < MAX_POINTER_ID) {
                ids |= 1 << id;
                this.previousMoveX[id] = motionEvent.getX(index);
                this.previousMoveY[id] = motionEvent.getY(index);
            }
        }
        this.previousMovePointerIds = ids;
        this.previousMovePointerCount = count;
    }

    private boolean hadPreviousMovePointer(int pointerId) {
        return pointerId >= 0 && pointerId < MAX_POINTER_ID
                && (this.previousMovePointerIds & (1 << pointerId)) != 0;
    }

    /**
     * @param pointerId pointer id
     * @param pointerIndex the pointer's index in {@code motionEvent}
     */
    private void updateConfirmSingleTapEligibility(int pointerId, MotionEvent motionEvent, int pointerIndex) {
        MotionEvent motionEvent2 = this.downEventByPointer.get(pointerId);
        if (motionEvent2 == null || !this.confirmSingleTapOnUpByPointer.get(pointerId) || this.alwaysInTapRegionByPointer.get(pointerId)) {
            return;
        }
        if (!hadPreviousMovePointer(pointerId)) {
            return;
        }
        int actionIndex = motionEvent2.getActionIndex();
        float x = motionEvent2.getX(actionIndex);
        float y = motionEvent2.getY(actionIndex);
        float x2 = motionEvent.getX(pointerIndex);
        float y2 = motionEvent.getY(pointerIndex);
        float x3 = this.previousMoveX[pointerId];
        float y3 = this.previousMoveY[pointerId];
        float fM6009a = distanceSquared(x2, y2, x, y);
        float fM6009a2 = distanceSquared(x3, y3, x, y);
        float f = ((x2 - x) * (x3 - x2)) + ((y2 - y) * (y3 - y2));
        this.confirmSingleTapOnUpByPointer.put(pointerId, fM6009a > fM6009a2 && ((double) ((f * f) / (fM6009a * distanceSquared(x2, y2, x3, y3)))) > DIRECTION_COS_SQ_THRESHOLD);
    }

    /**
     * @param pointerId pointer id
     * @param pointerIndex the pointer's index in {@code motionEvent}
     */
    private void updateTapRegions(int pointerId, MotionEvent motionEvent, int pointerIndex) {
        MotionEvent motionEvent2;
        if (!this.alwaysInBiggerTapRegionByPointer.get(pointerId) || (motionEvent2 = this.downEventByPointer.get(pointerId)) == null) {
            return;
        }
        int actionIndex = motionEvent2.getActionIndex();
        int x = (int) (motionEvent.getX(pointerIndex) - motionEvent2.getX(actionIndex));
        int y = (int) (motionEvent.getY(pointerIndex) - motionEvent2.getY(actionIndex));
        int i3 = (x * x) + (y * y);
        if (this.alwaysInTapRegionByPointer.get(pointerId) && i3 > this.touchSlopSquare) {
            this.alwaysInTapRegionByPointer.put(pointerId, false);
            this.handler.removeMessages(3);
            this.handler.removeMessages(1);
            this.handler.removeMessages(2);
            if (!this.hasMovedBeyondSlop) {
                this.hasMovedBeyondSlop = true;
            }
        }
        if (!this.alwaysInBiggerTapRegionByPointer.get(pointerId) || i3 <= this.doubleTapTouchSlopSquare) {
            return;
        }
        this.alwaysInBiggerTapRegionByPointer.put(pointerId, false);
    }

    private boolean cancelDoubleTapTracking(MotionEvent motionEvent) {
        this.activeDoubleTapPointerId = -1;
        // A copy, since its action is rewritten; the listener does not retain it, so it is recycled.
        MotionEvent cancelEvent = MotionEvent.obtainNoHistory(motionEvent);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        try {
            return this.doubleTapListener.onDoubleTapEvent(cancelEvent);
        } finally {
            cancelEvent.recycle();
        }
    }

    private boolean checkFling(MotionEvent motionEvent) {
        this.velocityTracker.computeCurrentVelocity(1000, this.maximumFlingVelocity);
        int pointerId = motionEvent.getPointerId(motionEvent.getActionIndex());
        float xVelocity = this.velocityTracker.getXVelocity(pointerId);
        float yVelocity = this.velocityTracker.getYVelocity(pointerId);
        MotionEvent motionEvent2 = this.downEventByPointer.get(pointerId);
        long eventTime = motionEvent.getEventTime() - motionEvent2.getEventTime();
        if (eventTime > 0) {
            float f = eventTime;
            float x = ((motionEvent.getX() - motionEvent2.getX()) * 1000.0f) / f;
            float y = ((motionEvent.getY() - motionEvent2.getY()) * 1000.0f) / f;
            if (Math.abs(x) > Math.abs(xVelocity)) {
                if (BuildConfig.DEBUG) Logger.verbose("IMEGestureDetector", "checkFling using simple X velocity " + x + " instead of " + xVelocity);
                xVelocity = x;
            }
            if (Math.abs(y) > Math.abs(yVelocity)) {
                if (BuildConfig.DEBUG) Logger.verbose("IMEGestureDetector", "checkFling using simple Y velocity " + y + " instead of " + yVelocity);
                yVelocity = y;
            }
        }
        if (Math.abs(yVelocity) <= this.minimumFlingVelocity && Math.abs(xVelocity) <= this.minimumFlingVelocity) {
            return false;
        }
        if (BuildConfig.DEBUG) {
            Logger.info("IMEGestureDetector", "Fling vxy=" + xVelocity + "," + yVelocity);
        }
        return this.gestureListener.onFling(this.downEventByPointer.get(pointerId), motionEvent, xVelocity, yVelocity);
    }

    public void cancel() {
        cancelTaps();
        VelocityTracker velocityTracker = this.velocityTracker;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            this.velocityTracker = null;
        }
        this.previousMovePointerIds = 0;
        this.previousMovePointerCount = 0;
        this.stillDownByPointer.clear();
        for (SparseArray sparseArray : Arrays.asList(this.downEventByPointer, this.upEventByPointer)) {
            for (int size = sparseArray.size() - 1; size >= 0; size--) {
                MotionEvent motionEvent = (MotionEvent) sparseArray.valueAt(size);
                if (motionEvent != null) {
                    motionEvent.recycle();
                }
            }
            sparseArray.clear();
        }
    }

    private void cancelTaps() {
        this.handler.removeMessages(1);
        this.handler.removeMessages(2);
        this.handler.removeMessages(3);
        this.activeDoubleTapPointerId = -1;
        this.alwaysInTapRegionByPointer.clear();
        this.alwaysInBiggerTapRegionByPointer.clear();
        this.deferConfirmSingleTapByPointer.clear();
        if (this.inLongPress) {
            this.inLongPress = false;
        }
    }

    /**
     * @param motionEvent the first DOWN
     * @param firstUp the first UP
     * @param secondDown the second DOWN
     * @param pointerId pointer id
     */
    private boolean isConsideredDoubleTap(MotionEvent motionEvent, MotionEvent firstUp, MotionEvent secondDown, int pointerId) {
        if (!this.alwaysInBiggerTapRegionByPointer.get(pointerId)) {
            return false;
        }
        long eventTime = secondDown.getEventTime() - firstUp.getEventTime();
        if (eventTime > DOUBLE_TAP_TIMEOUT || eventTime < 40) {
            return false;
        }
        int actionIndex = motionEvent.getActionIndex();
        int actionIndex2 = secondDown.getActionIndex();
        Point point = new Point((int) motionEvent.getX(actionIndex), (int) motionEvent.getY(actionIndex));
        Point point2 = new Point((int) secondDown.getX(actionIndex2), (int) secondDown.getY(actionIndex2));
        if (DeviceProfile.current().isFromTouchKeypad(motionEvent) && (!this.doubleTapRegion.contains(point.x, point.y) || !this.doubleTapRegion.contains(point2.x, point2.y))) {
            return false;
        }
        int i2 = point.x - point2.x;
        int i3 = point.y - point2.y;
        boolean z = (i2 * i2) + (i3 * i3) < this.doubleTapSlopSquare;
        if (z) {
            if (BuildConfig.DEBUG) Logger.info("IMEGestureDetector", "DoubleTap " + pointerId + " t=" + motionEvent.getEventTime() + "," + firstUp.getEventTime() + "," + secondDown.getEventTime() + " p1d=" + point.x + "," + point.y + " p2d=" + point2.x + "," + point2.y);
        }
        return z;
    }

        void dispatchLongPress() {
        this.handler.removeMessages(3);
        this.deferConfirmSingleTapByPointer.put(0, false);
        this.inLongPress = true;
        MotionEvent longPressEvent = this.downEventByPointer.get(0);
        if (longPressEvent != null) {
            this.gestureListener.onLongPress(longPressEvent);
        }
    }
}
