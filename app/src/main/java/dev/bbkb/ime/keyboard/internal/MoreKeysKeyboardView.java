package dev.bbkb.ime.keyboard.internal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.keyboard.KeyboardView;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.CoordinateUtils;


public class MoreKeysKeyboardView extends KeyboardView implements MoreKeysPanel {

    protected final KeyDetector mKeyDetector;

    protected KeyboardActionListenerInterface mListener;


    private final int[] mCoordinates;

    private final Drawable mDivider;

    private MoreKeysPanel.Controller mController;

    private int mOriginX;

    private int mOriginY;

    private Key mCurrentKey;


    private int mActivePointerId;

    public MoreKeysKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.moreKeysKeyboardViewStyle);
    }

    public MoreKeysKeyboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.mCoordinates = CoordinateUtils.newCoordinateArray();
        this.mController = EMPTY;
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.MoreKeysKeyboardView, i, R.style.MoreKeysKeyboardView);
        this.mDivider = typedArrayObtainStyledAttributes.getDrawable(R.styleable.MoreKeysKeyboardView_divider);
        Drawable drawable = this.mDivider;
        if (drawable != null) {
            drawable.setAlpha(128);
        }
        typedArrayObtainStyledAttributes.recycle();
        this.mKeyDetector = new MoreKeysDetector(getResources().getDimension(R.dimen.config_more_keys_keyboard_slide_allowance));
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            setMeasuredDimension(keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight(), keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom());
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected void drawKeyContent(Key key, Canvas canvas, Paint paint, KeyDrawParams formatter, boolean isPreview) {
        if (key.isSpacerKey() && (key instanceof MoreKeysKeyboard.MoreKeysDivider) && this.mDivider != null) {
            int iM6219ae = key.getDrawWidth();
            int iM6215aa = key.getHeight();
            int iMin = Math.min(this.mDivider.getIntrinsicWidth(), iM6219ae);
            int intrinsicHeight = this.mDivider.getIntrinsicHeight();
            drawDrawable(canvas, this.mDivider, (iM6219ae - iMin) / 2, (iM6215aa - intrinsicHeight) / 2, iMin, intrinsicHeight);
            return;
        }
        // Propagate rather than forcing false: the override shadowed its own parameter. Both are
        // false at every call site today (KeyboardView.onDrawKeyboard passes false), so this is
        // behaviour-identical and correct if the preview flag is ever plumbed through.
        super.drawKeyContent(key, canvas, paint, formatter, isPreview);
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void setKeyboard(Keyboard keyboard) {
        super.setKeyboard(keyboard);
        this.mKeyDetector.setKeyboard(keyboard, -getPaddingLeft(), (-getPaddingTop()) + getVerticalCorrection());
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void showMoreKeysPanel(View view, MoreKeysPanel.Controller aVar, int i, int i2, KeyboardActionListenerInterface interfaceC0976f) {
        this.mController = aVar;
        this.mListener = interfaceC0976f;
        View containerView = getContainerView();
        int defaultCoordX = ((i - getDefaultCoordX()) - containerView.getPaddingLeft()) - getPaddingLeft();
        int measuredHeight = (i2 - containerView.getMeasuredHeight()) + containerView.getPaddingBottom() + getPaddingBottom();
        view.getLocationInWindow(this.mCoordinates);
        int iMax = Math.max(0, Math.min(view.getMeasuredWidth() - containerView.getMeasuredWidth(), defaultCoordX)) + CoordinateUtils.x(this.mCoordinates);
        int iM5690b = CoordinateUtils.y(this.mCoordinates) + measuredHeight;
        containerView.setX(iMax);
        containerView.setY(iM5690b);
        this.mOriginX = defaultCoordX + containerView.getPaddingLeft();
        this.mOriginY = measuredHeight + containerView.getPaddingTop();
        aVar.onShowMoreKeysPanel(this);
    }

    protected int getDefaultCoordX() {
        return ((MoreKeysKeyboard) getKeyboard()).getDefaultCoordX();
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onDownEvent(int i, int i2, int i3, long j) {
        this.mActivePointerId = i3;
        this.mCurrentKey = detectKey(i, i2);
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onMoveEvent(int i, int i2, int i3, long j) {
        if (this.mActivePointerId != i3) {
            return;
        }
        boolean z = this.mCurrentKey != null;
        this.mCurrentKey = detectKey(i, i2);
        if (z && this.mCurrentKey == null) {
            this.mController.onDismissMoreKeysPanel();
        }
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onUpEvent(int i, int i2, int i3, long j) {
        if (this.mActivePointerId != i3) {
            return;
        }
        this.mCurrentKey = detectKey(i, i2);
        Key key = this.mCurrentKey;
        if (key != null) {
            setReleasedKeyGraphics(key);
            onKeyInput(this.mCurrentKey, i, i2, j);
            this.mCurrentKey = null;
        }
    }

    protected void onKeyInput(Key key, int i, int i2, long j) {
        int iM6232c = key.getCode();
        if (iM6232c == -4) {
            this.mListener.onTextInput(this.mCurrentKey.getKeySpecOutputText(), j);
        } else if (iM6232c != -21) {
            if (getKeyboard().allowsGestureForCode(iM6232c)) {
                this.mListener.onCodeInput(iM6232c, i, i2, j, false);
            } else {
                this.mListener.onCodeInput(iM6232c, -1, -1, j, false);
            }
        }
        this.mListener.onMoreKeysKeyTyped();
    }

    private Key detectKey(int i, int i2) {
        Key key = this.mCurrentKey;
        Key keyMo6590a = this.mKeyDetector.detectHitKey(i, i2);
        if (keyMo6590a == key) {
            return keyMo6590a;
        }
        if (key != null) {
            setReleasedKeyGraphics(key);
        }
        if (keyMo6590a != null) {
            setPressedKeyGraphics(keyMo6590a);
        }
        return keyMo6590a;
    }

    // Named for what they do, matching PointerTracker's vocabulary. The decompiled names
    // (onDownKey / onUpKey) were the exact inverse of their bodies, and every call site was
    // inverted to compensate.
    private void setReleasedKeyGraphics(Key key) {
        key.onReleased();
        invalidateKey(key);
    }

    private void setPressedKeyGraphics(Key key) {
        key.onPressed();
        invalidateKey(key);
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void dismissMoreKeysPanel() {
        if (isShowingInParent()) {
            this.mController.onCancelMoreKeysPanel();
        }
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public int translateX(int i) {
        return i - this.mOriginX;
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public int translateY(int i) {
        return i - this.mOriginY;
    }

    @Override // android.view.View
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        long eventTime = motionEvent.getEventTime();
        int actionIndex = motionEvent.getActionIndex();
        int x = (int) motionEvent.getX(actionIndex);
        int y = (int) motionEvent.getY(actionIndex);
        int pointerId = motionEvent.getPointerId(actionIndex);
        switch (actionMasked) {
            case 0:
            case 5:
                onDownEvent(x, y, pointerId, eventTime);
                break;
            case 1:
            case 6:
                onUpEvent(x, y, pointerId, eventTime);
                break;
            case 2:
                onMoveEvent(x, y, pointerId, eventTime);
                break;
        }
        return true;
    }

    private View getContainerView() {
        return (View) getParent();
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void showInParent(ViewGroup viewGroup) {
        removeFromParent();
        viewGroup.addView(getContainerView());
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void removeFromParent() {
        View containerView = getContainerView();
        ViewGroup viewGroup = (ViewGroup) containerView.getParent();
        if (viewGroup != null) {
            viewGroup.removeView(containerView);
        }
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public boolean isShowingInParent() {
        return getContainerView().getParent() != null;
    }
}
