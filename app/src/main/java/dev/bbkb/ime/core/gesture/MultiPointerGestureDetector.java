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

    private MotionEvent previousMoveEvent;

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
     * The keypad-coordinate rectangle a double-tap must stay inside on a touch-keypad device.
     *
     * Audit GD-30: this was a hardcoded {@code new Rect(0, 0, 1080, 450)} with no source and no
     * scaling — a third, different set of keypad dimensions alongside the device-config
     * mechanism. It now derives from {@link CkbKeyGrid}, which owns the keypad coordinate-space
     * size (1080x525 for athena) that the arbiter and the Gesture Lab already share; the old 450
     * silently excluded the bottom of the pad.
     */
    private final Rect doubleTapRegion = new Rect(
            0, 0,
            dev.bbkb.ime.core.gesture.arbiter.CkbKeyGrid.WIDTH,
            dev.bbkb.ime.core.gesture.arbiter.CkbKeyGrid.HEIGHT);

    
    public interface OnDoubleTapListener {
        boolean onDoubleTap(MotionEvent motionEvent);

        boolean onDoubleTapEvent(MotionEvent motionEvent);

        boolean onSingleTapConfirmed(MotionEvent motionEvent);
    }

    
    public interface OnGestureListener {
        void onLongPress(MotionEvent motionEvent);

        boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2);

        boolean onDown(MotionEvent motionEvent);

        boolean onScroll(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2);

        boolean onSingleTapUp(MotionEvent motionEvent);

        void onShowPress(MotionEvent motionEvent);
    }

    
    public static class SimpleOnGestureListener implements OnDoubleTapListener, OnGestureListener {
        public void onLongPress(MotionEvent motionEvent) {
        }

        public boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
            return false;
        }

        public boolean onDown(MotionEvent motionEvent) {
            return false;
        }

        public boolean onScroll(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
            return false;
        }

        public boolean onSingleTapUp(MotionEvent motionEvent) {
            return false;
        }

        public void onShowPress(MotionEvent motionEvent) {
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
            // Audit GD-13: the implicit super() is the deprecated no-arg Handler(), which binds
            // to whatever Looper the constructing thread happens to have. These are the
            // long-press / show-press / single-tap-confirm timers for the on-screen keyboard;
            // they belong on the main thread, explicitly.
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

    public void setIsLongpressEnabled(boolean z) {
        this.isLongpressEnabled = z;
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
        
        // Fixed switch case logic based on Smali implementation
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
        
        // Fixed main switch statement with correct case ordering
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
                // onSingleTapUp: must fire on every qualifying UP so that ImeGestureListener
                // can update previousTapDownTime (tap-down timestamp). Without this call previousTapDownTime stays at 0,
                // making the double-tap suppression check always true → FCC never activates.
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
                // Audit GD-30: the `doubleTapRegion != null` half of this test was a
                // constant-true decompiler artifact (the field is assigned at declaration and
                // never nulled) and read as if fling dispatch were gated on the keypad region.
                if ((velocityTracker = this.velocityTracker) != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    if (checkFling(motionEvent)) {
                        zM6020c = true;
                    }
                }
                this.handler.removeMessages(1);
                this.handler.removeMessages(3);
                // Keep downEventByPointer[pointerId] (first DOWN) — isConsideredDoubleTap needs it present on the second ACTION_DOWN.
                // It is recycled naturally in the next ACTION_DOWN at lines 318-322.
                // Store UP event into upEventByPointer for isConsideredDoubleTap's timing/distance check.
                // Recycle any previously stored UP first.
                MotionEvent prevUp = this.upEventByPointer.get(pointerId);
                if (prevUp != null) {
                    prevUp.recycle();
                }
                this.upEventByPointer.put(pointerId, motionEventObtain2);
                // Keep alwaysInBiggerTapRegionByPointer[pointerId] — isConsideredDoubleTap checks this flag first and returns false if absent.
                // It is reset to true in the next ACTION_DOWN at line 324.
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
                        // Audit GD-2: this used to MotionEvent.obtain(motionEvent) here and never
                        // recycle the copy — one leaked pooled event per pointer per ACTION_MOVE,
                        // draining the recycler pool for the whole input stack. Both callees only
                        // read getX/getY(index) off it, so the live event serves directly.
                        updateTapRegions(pointerId2, motionEvent, i4);
                        // DECOMPILATION FIX: Original Smali checks if BlackBerryIME gesture filter returns true
                        // Decompiler incorrectly changed this to check if Rect doubleTapRegion is not null
                        // Lines 1692-1723 in ClipboardItem.smali show the correct logic
                        if (this.ime != null && this.ime.shouldHandleGestureEvent(motionEvent) && this.previousMoveEvent != null) {
                            if (i4 < this.previousMoveEvent.getPointerCount()) {
                                int findPointerIndex = this.previousMoveEvent.findPointerIndex(pointerId2);
                                if (findPointerIndex >= 0) {
                                    updateConfirmSingleTapEligibility(pointerId2, motionEvent, i4);
                                }
                            }
                        }
                    }
                }
                if (this.doubleTapListener != null) {
                    if (this.activeDoubleTapPointerId != -1 && this.alwaysInBiggerTapRegionByPointer.get(this.activeDoubleTapPointerId)) {
                        zMo4683f = this.doubleTapListener.onDoubleTapEvent(motionEvent) | false;
                    } else {
                        zMo4683f = cancelDoubleTapTracking(motionEvent) | false;
                    }
                }
                if (this.inLongPress) {
                    MotionEvent motionEvent8 = this.downEventByPointer.get(pointerId);
                    if (motionEvent8 != null) {
                        zMo4683f |= this.gestureListener.onFling(motionEvent8, motionEvent, f2, f3);
                    }
                }
                // FIX: Do NOT update downEventByPointer during ACTION_MOVE!
                // downEventByPointer should ONLY contain the original ACTION_DOWN event for correct velocity calculation
                // Store previous motion event for gesture direction detection without overwriting start event
                MotionEvent previousEvent = this.downEventByPointer.get(pointerId);
                if (previousEvent != null) {
                    if (this.previousMoveEvent != null) {
                        this.previousMoveEvent.recycle();
                    }
                    this.previousMoveEvent = MotionEvent.obtain(motionEvent); // Store CURRENT event, not previous
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
                // Audit GD-2: the obtained copy that used to stand here was never recycled;
                // updateTapRegions only reads coordinates, so pass the live event.
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

    private static float distanceSquared(float f, float f2, float f3, float f4) {
        // Audit GD-14: two Math.pow calls (the double-precision exp/log path) plus
        // float->double->float conversions to square a float, three times per ACTION_MOVE per
        // pointer. The sibling updateTapRegions already does it this way.
        final float dx = f - f3;
        final float dy = f2 - f4;
        return (dx * dx) + (dy * dy);
    }

    private void updateConfirmSingleTapEligibility(int i, MotionEvent motionEvent, int i2) {
        MotionEvent motionEvent2 = this.downEventByPointer.get(i);
        if (motionEvent2 == null || !this.confirmSingleTapOnUpByPointer.get(i) || this.alwaysInTapRegionByPointer.get(i)) {
            return;
        }
        // Fix: Add null check for previousMoveEvent to prevent crash
        if (this.previousMoveEvent == null) {
            return;
        }
        int actionIndex = motionEvent2.getActionIndex();
        float x = motionEvent2.getX(actionIndex);
        float y = motionEvent2.getY(actionIndex);
        float x2 = motionEvent.getX(i2);
        float y2 = motionEvent.getY(i2);
        MotionEvent motionEvent3 = this.previousMoveEvent;
        int pointerIndex = motionEvent3.findPointerIndex(i);
        if (pointerIndex < 0) {
            return;
        }
        float x3 = motionEvent3.getX(pointerIndex);
        MotionEvent motionEvent4 = this.previousMoveEvent;
        float y3 = motionEvent4.getY(pointerIndex);
        float fM6009a = distanceSquared(x2, y2, x, y);
        float fM6009a2 = distanceSquared(x3, y3, x, y);
        float f = ((x2 - x) * (x3 - x2)) + ((y2 - y) * (y3 - y2));
        this.confirmSingleTapOnUpByPointer.put(i, fM6009a > fM6009a2 && ((double) ((f * f) / (fM6009a * distanceSquared(x2, y2, x3, y3)))) > DIRECTION_COS_SQ_THRESHOLD);
    }

    private void updateTapRegions(int i, MotionEvent motionEvent, int i2) {
        MotionEvent motionEvent2;
        if (!this.alwaysInBiggerTapRegionByPointer.get(i) || (motionEvent2 = this.downEventByPointer.get(i)) == null) {
            return;
        }
        int actionIndex = motionEvent2.getActionIndex();
        int x = (int) (motionEvent.getX(i2) - motionEvent2.getX(actionIndex));
        int y = (int) (motionEvent.getY(i2) - motionEvent2.getY(actionIndex));
        int i3 = (x * x) + (y * y);
        if (this.alwaysInTapRegionByPointer.get(i) && i3 > this.touchSlopSquare) {
            this.alwaysInTapRegionByPointer.put(i, false);
            this.handler.removeMessages(3);
            this.handler.removeMessages(1);
            this.handler.removeMessages(2);
            if (!this.hasMovedBeyondSlop) {
                this.hasMovedBeyondSlop = true;
            }
        }
        if (!this.alwaysInBiggerTapRegionByPointer.get(i) || i3 <= this.doubleTapTouchSlopSquare) {
            return;
        }
        this.alwaysInBiggerTapRegionByPointer.put(i, false);
    }

    private boolean cancelDoubleTapTracking(MotionEvent motionEvent) {
        this.activeDoubleTapPointerId = -1;
        // The copy is needed here (its action is rewritten to ACTION_CANCEL), but audit GD-2:
        // it was never recycled. The listener does not retain it, so recycle on the way out.
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
                Logger.verbose("IMEGestureDetector", "checkFling using simple X velocity " + x + " instead of " + xVelocity);
                xVelocity = x;
            }
            if (Math.abs(y) > Math.abs(yVelocity)) {
                Logger.verbose("IMEGestureDetector", "checkFling using simple Y velocity " + y + " instead of " + yVelocity);
                yVelocity = y;
            }
        }
        if (Math.abs(yVelocity) <= this.minimumFlingVelocity && Math.abs(xVelocity) <= this.minimumFlingVelocity) {
            return false;
        }
        Logger.isLoggable("IMEGestureDetector", Log.DEBUG);
        Logger.info("IMEGestureDetector", "Fling vxy=" + xVelocity + "," + yVelocity);
        return this.gestureListener.onFling(this.downEventByPointer.get(pointerId), motionEvent, xVelocity, yVelocity);
    }

    public void cancel() {
        cancelTaps();
        VelocityTracker velocityTracker = this.velocityTracker;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            this.velocityTracker = null;
        }
        // Fix: Clean up previousMoveEvent to prevent memory leaks
        if (this.previousMoveEvent != null) {
            this.previousMoveEvent.recycle();
            this.previousMoveEvent = null;
        }
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

    private boolean isConsideredDoubleTap(MotionEvent motionEvent, MotionEvent motionEvent2, MotionEvent motionEvent3, int i) {
        if (!this.alwaysInBiggerTapRegionByPointer.get(i)) {
            return false;
        }
        long eventTime = motionEvent3.getEventTime() - motionEvent2.getEventTime();
        if (eventTime > DOUBLE_TAP_TIMEOUT || eventTime < 40) {
            return false;
        }
        int actionIndex = motionEvent.getActionIndex();
        int actionIndex2 = motionEvent3.getActionIndex();
        Point point = new Point((int) motionEvent.getX(actionIndex), (int) motionEvent.getY(actionIndex));
        Point point2 = new Point((int) motionEvent3.getX(actionIndex2), (int) motionEvent3.getY(actionIndex2));
        if (DeviceProfile.current().isFromTouchKeypad(motionEvent) && (!this.doubleTapRegion.contains(point.x, point.y) || !this.doubleTapRegion.contains(point2.x, point2.y))) {
            return false;
        }
        int i2 = point.x - point2.x;
        int i3 = point.y - point2.y;
        boolean z = (i2 * i2) + (i3 * i3) < this.doubleTapSlopSquare;
        if (z) {
            Logger.info("IMEGestureDetector", "DoubleTap " + i + " t=" + motionEvent.getEventTime() + "," + motionEvent2.getEventTime() + "," + motionEvent3.getEventTime() + " p1d=" + point.x + "," + point.y + " p2d=" + point2.x + "," + point2.y);
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
