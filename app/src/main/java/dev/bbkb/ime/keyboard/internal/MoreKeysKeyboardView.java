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

    public MoreKeysKeyboardView(Context context, AttributeSet attributeSet, int defStyle) {
        super(context, attributeSet, defStyle);
        this.mCoordinates = CoordinateUtils.newCoordinateArray();
        this.mController = EMPTY;
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.MoreKeysKeyboardView, defStyle, R.style.MoreKeysKeyboardView);
        this.mDivider = typedArrayObtainStyledAttributes.getDrawable(R.styleable.MoreKeysKeyboardView_divider);
        Drawable drawable = this.mDivider;
        if (drawable != null) {
            drawable.setAlpha(128);
        }
        typedArrayObtainStyledAttributes.recycle();
        this.mKeyDetector = new MoreKeysDetector(getResources().getDimension(R.dimen.config_more_keys_keyboard_slide_allowance));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            setMeasuredDimension(keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight(), keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom());
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void drawKeyContent(Key key, Canvas canvas, Paint paint, KeyDrawParams formatter, boolean isPreview) {
        if (key.isSpacerKey() && (key instanceof MoreKeysKeyboard.MoreKeysDivider) && this.mDivider != null) {
            int iM6219ae = key.getDrawWidth();
            int iM6215aa = key.getHeight();
            int iMin = Math.min(this.mDivider.getIntrinsicWidth(), iM6219ae);
            int intrinsicHeight = this.mDivider.getIntrinsicHeight();
            drawDrawable(canvas, this.mDivider, (iM6219ae - iMin) / 2, (iM6215aa - intrinsicHeight) / 2, iMin, intrinsicHeight);
            return;
        }
        super.drawKeyContent(key, canvas, paint, formatter, isPreview);
    }

    @Override
    public void setKeyboard(Keyboard keyboard) {
        // Popups are cached and reused: a key left pressed by a mid-drag dismiss must not stay highlighted.
        if (keyboard != null) {
            for (Key key : keyboard.getKeys()) {
                key.onReleased();
            }
        }
        this.mCurrentKey = null;
        super.setKeyboard(keyboard);
        this.mKeyDetector.setKeyboard(keyboard, -getPaddingLeft(), (-getPaddingTop()) + getVerticalCorrection());
    }

    /**
     * @param controller the controller told when the panel shows and closes
     * @param pointX x to center the panel on (the key's center or the touch point)
     * @param pointY y the panel's bottom sits at in {@code view}
     * @param listener receives the typed key
     */
    @Override
    public void showMoreKeysPanel(View view, MoreKeysPanel.Controller controller, int pointX, int pointY, KeyboardActionListenerInterface listener) {
        this.mController = controller;
        this.mListener = listener;
        View containerView = getContainerView();
        int defaultCoordX = ((pointX - getDefaultCoordX()) - containerView.getPaddingLeft()) - getPaddingLeft();
        int measuredHeight = (pointY - containerView.getMeasuredHeight()) + containerView.getPaddingBottom() + getPaddingBottom();
        view.getLocationInWindow(this.mCoordinates);
        int iMax = Math.max(0, Math.min(view.getMeasuredWidth() - containerView.getMeasuredWidth(), defaultCoordX)) + CoordinateUtils.x(this.mCoordinates);
        int iM5690b = CoordinateUtils.y(this.mCoordinates) + measuredHeight;
        containerView.setX(iMax);
        containerView.setY(iM5690b);
        this.mOriginX = defaultCoordX + containerView.getPaddingLeft();
        this.mOriginY = measuredHeight + containerView.getPaddingTop();
        controller.onShowMoreKeysPanel(this);
    }

    protected int getDefaultCoordX() {
        return ((MoreKeysKeyboard) getKeyboard()).getDefaultCoordX();
    }

    /**
     * @param x x
     * @param y y
     * @param pointerId pointer id
     * @param eventTime event time
     */
    @Override
    public void onDownEvent(int x, int y, int pointerId, long eventTime) {
        this.mActivePointerId = pointerId;
        this.mCurrentKey = detectKey(x, y);
    }

    /**
     * @param x x
     * @param y y
     * @param pointerId pointer id
     * @param eventTime event time
     */
    @Override
    public void onMoveEvent(int x, int y, int pointerId, long eventTime) {
        if (this.mActivePointerId != pointerId) {
            return;
        }
        boolean z = this.mCurrentKey != null;
        this.mCurrentKey = detectKey(x, y);
        if (z && this.mCurrentKey == null) {
            this.mController.onDismissMoreKeysPanel();
        }
    }

    /**
     * @param x x
     * @param y y
     * @param pointerId pointer id
     * @param eventTime event time
     */
    @Override
    public void onUpEvent(int x, int y, int pointerId, long eventTime) {
        if (this.mActivePointerId != pointerId) {
            return;
        }
        this.mCurrentKey = detectKey(x, y);
        Key key = this.mCurrentKey;
        if (key != null) {
            setReleasedKeyGraphics(key);
            onKeyInput(this.mCurrentKey, x, y, eventTime);
            this.mCurrentKey = null;
        }
    }

    /**
     * @param x x
     * @param y y
     * @param eventTime event time
     */
    protected void onKeyInput(Key key, int x, int y, long eventTime) {
        int iM6232c = key.getCode();
        if (iM6232c == -4) {
            this.mListener.onTextInput(this.mCurrentKey.getKeySpecOutputText(), eventTime);
        } else if (iM6232c != -21) {
            if (getKeyboard().allowsGestureForCode(iM6232c)) {
                this.mListener.onCodeInput(iM6232c, x, y, eventTime, false);
            } else {
                this.mListener.onCodeInput(iM6232c, -1, -1, eventTime, false);
            }
        }
        this.mListener.onMoreKeysKeyTyped();
    }

    private Key detectKey(int x, int y) {
        Key key = this.mCurrentKey;
        Key keyMo6590a = this.mKeyDetector.detectHitKey(x, y);
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

    private void setReleasedKeyGraphics(Key key) {
        key.onReleased();
        invalidateKey(key);
    }

    private void setPressedKeyGraphics(Key key) {
        key.onPressed();
        invalidateKey(key);
    }

    @Override
    public void dismissMoreKeysPanel() {
        if (isShowingInParent()) {
            this.mController.onCancelMoreKeysPanel();
        }
    }

    /** @param x x in the parent view */
    @Override
    public int translateX(int x) {
        return x - this.mOriginX;
    }

    /** @param y y in the parent view */
    @Override
    public int translateY(int y) {
        return y - this.mOriginY;
    }

    @Override
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

    @Override
    public void showInParent(ViewGroup viewGroup) {
        removeFromParent();
        viewGroup.addView(getContainerView());
    }

    @Override
    public void removeFromParent() {
        View containerView = getContainerView();
        ViewGroup viewGroup = (ViewGroup) containerView.getParent();
        if (viewGroup != null) {
            viewGroup.removeView(containerView);
        }
    }

    @Override
    public boolean isShowingInParent() {
        return getContainerView().getParent() != null;
    }
}
