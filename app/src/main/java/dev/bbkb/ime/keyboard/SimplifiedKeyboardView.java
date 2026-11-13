package dev.bbkb.ime.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import dev.bbkb.ime.keyboard.internal.KeyDetector;
import dev.bbkb.ime.keyboard.internal.KeyDrawParams;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.keyboard.internal.LongPressTimer;

import dev.bbkb.ime.BuildConfig;


public class SimplifiedKeyboardView extends KeyboardView {
    /** Used until a listener is set: ignores every event. */
    private static final onKeyEventListener NO_OP_KEY_LISTENER = new onKeyEventListener() {
        @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
        public void onKeyDown(Key key) {
        }

        @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
        public void onKeyUp(Key key, boolean releasedOnKey) {
        }

        @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
        public void onKeyLongPress(Key key) {
        }
    };

    protected final KeyDetector keyboardActionListener;


    private LongPressTimer keyboardCoordinates;

    private onKeyEventListener keyEventListener;

    private Key pressedKey;

    /**
     * Long-press callback, allocated once. Reads {@link #pressedKey} at fire time: handleKeyDown
     * assigns it right after start(), and every ACTION_DOWN cancels the timer first.
     */
    private final LongPressTimer.Callback longPressCallback = new LongPressTimer.Callback() {
        @Override
        public void onTimeout() {
            SimplifiedKeyboardView.this.handleKeyUp(SimplifiedKeyboardView.this.pressedKey);
        }
    };

    public interface onKeyEventListener {
        void onKeyDown(Key key);

        /** @param releasedOnKey true if released on the pressed key */
        void onKeyUp(Key key, boolean releasedOnKey);

        void onKeyLongPress(Key key);
    }

    public SimplifiedKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    /** @param defStyleAttr default style attribute */
    public SimplifiedKeyboardView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        this.keyboardActionListener = new KeyDetector();
        this.keyboardCoordinates = new LongPressTimer();
        this.keyEventListener = NO_OP_KEY_LISTENER;
    }

    @Override
    protected boolean anchorsHintToMainIcon() {
        return true;
    }

    /** Labels drawn as the suggestion strip draws its words; see {@link #setStripTextStyle}. */
    private boolean stripTextStyle;

    /**
     * Draws labels like the suggestion strip (its text size, default typeface, TextView-style
     * centring), for the accent bar, which stands in for the strip.
     */
    public void setStripTextStyle(boolean on) {
        if (stripTextStyle != on) {
            stripTextStyle = on;
            invalidate();
        }
    }

    @Override
    protected void drawKeyContent(Key key, Canvas canvas, Paint paint, KeyDrawParams formatter, boolean suppressLabel) {
        String label = stripTextStyle && !suppressLabel ? labelFor(key) : null;
        if (label == null || getKeyboard() == null || key.getIcon(getKeyboard().mIconsSet, formatter.animAlpha) != null) {
            super.drawKeyContent(key, canvas, paint, formatter, suppressLabel);
            return;
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(getResources().getDimension(R.dimen.config_suggestion_text_size));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextScaleX(1.0f);
        paint.clearShadowLayer();
        paint.setColor(key.isActive() ? key.getLabelColor(formatter) : 0);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float centerY = key.getHeight() * 0.5f;
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2.0f;
        canvas.drawText(label, key.getDrawWidth() * 0.5f, baseline, paint);
    }

    /**
     * Aux bars render as one flat surface in modern themes. A board with real key surfaces
     * (the number pad) returns false to get KeyboardView's normal painting.
     */
    protected boolean drawsFlatModernBackground() {
        return true;
    }

    @Override
    protected void drawKeyBackground(Key key, Canvas canvas) {
        if (!KeyboardColorManager.styleSpec().getModernBoards() || !drawsFlatModernBackground()) {
            super.drawKeyBackground(key, canvas);
            return;
        }
        // No per-key fill or dividers; press feedback is a rounded translucent state layer.
        if (!key.isPressed()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float inset = 4f * density;
        float layerHeight = key.getHeight() - (2f * inset);
        if (layerHeight <= 0) {
            return;
        }
        float layerWidth = Math.min(key.getDrawWidth() - (2f * inset), layerHeight * 1.8f);
        float centerX = key.getDrawWidth() / 2f;
        float radius = 8f * density;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(KeyboardColorManager.INSTANCE.getPressedStateLayer());
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(centerX - (layerWidth / 2f), inset,
                centerX + (layerWidth / 2f), inset + layerHeight, radius, radius, paint);
    }

    @Override
    protected void applyIconTint(Key key, Drawable iconDrawable) {
        // Aux-bar tint (active board = accent, rest muted). Boards with real key surfaces use
        // the standard tinting so their icons match the regular VKB.
        if (!drawsFlatModernBackground()) {
            super.applyIconTint(key, iconDrawable);
            return;
        }
        if (key.isHighlighted()) {
            KeyboardColorManager.INSTANCE.tint(iconDrawable);
        } else {
            int inactiveColor = KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY);
            KeyboardColorManager.INSTANCE.tint(iconDrawable, inactiveColor);
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void setKeyboard(Keyboard keyboard) {
        super.setKeyboard(keyboard);
        this.keyboardActionListener.setKeyboard(keyboard, 0.0f, 0.0f);
    }

    public void setOnKeyEventListener(onKeyEventListener onKeyEventListener) {
        if (onKeyEventListener != null) {
            this.keyEventListener = onKeyEventListener;
        } else {
            this.keyEventListener = NO_OP_KEY_LISTENER;
        }
    }

    @Override // android.view.View
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        if (motionEvent.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("UIM_TOUCH_DEBUG", "SimplifiedKeyboardView.onTouchEvent: ACTION_DOWN"
                    + " isShown=" + isShown() + " visibility=" + getVisibility()
                    + " x=" + motionEvent.getX() + " y=" + motionEvent.getY());
            }
        }
        if (!isShown()) {
            return true;
        }
        final Key keyM6369a = gotKeyAtEvent(motionEvent);
        Key key = this.pressedKey;
        if (key != null && !key.equals(keyM6369a)) {
            releasedKey(false);
        }
        switch (motionEvent.getActionMasked()) {
            case 0:
                this.keyboardCoordinates.cancel();
                if (keyM6369a != null && keyM6369a.hasLongPressKey()) {
                    this.keyboardCoordinates.setCallback(this.longPressCallback);
                    this.keyboardCoordinates.start();
                }
                handleKeyDown(keyM6369a);
                break;
            case 1:
                this.keyboardCoordinates.cancel();
                handleKeyUp(keyM6369a);
                break;
            case 2:
                break;
            default:
                releasedKey(false);
                break;
        }
        return true;
    }

    private Key gotKeyAtEvent(MotionEvent motionEvent) {
        int actionIndex = motionEvent.getActionIndex();
        int x = (int) motionEvent.getX(actionIndex);
        int y = (int) motionEvent.getY(actionIndex);
        Key keyMo6590a = this.keyboardActionListener.detectHitKey(x, y);
        if (keyMo6590a == null || keyMo6590a.isActive()) {
            if (motionEvent.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                if (BuildConfig.DEBUG) {
                android.util.Log.d("UIM_TOUCH_DEBUG", "gotKeyAtEvent: x=" + x + " y=" + y
                        + " key=" + (keyMo6590a == null ? "null" : keyMo6590a.getCodeString())
                        + " isActive=" + (keyMo6590a != null ? keyMo6590a.isActive() : "N/A")
                        + " result=" + (keyMo6590a == null ? "null(no-key)" : "key-active"));
                }
            }
            return keyMo6590a;
        }
        if (motionEvent.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("UIM_TOUCH_DEBUG", "gotKeyAtEvent: x=" + x + " y=" + y
                    + " key=" + keyMo6590a.getCodeString() + " isActive=FALSE -> returning null (inactive)");
            }
        }
        return null;
    }

    /** @param releasedOnKey true if released on the pressed key (commits) */
    private boolean releasedKey(boolean releasedOnKey) {
        Key key = this.pressedKey;
        if (key == null) {
            return false;
        }
        key.onReleased();
        invalidateKey(this.pressedKey);
        if (this.keyboardCoordinates.getCount() == 2 && releasedOnKey) {
            this.keyEventListener.onKeyLongPress(this.pressedKey);
        } else {
            this.keyEventListener.onKeyUp(this.pressedKey, releasedOnKey);
        }
        this.pressedKey = null;
        return true;
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        // Aux bars are shown and hidden constantly; a pending long-press would fire onKeyUp on a detached view.
        this.keyboardCoordinates.cancel();
        releasedKey(false);
        super.onDetachedFromWindow();
    }

    @Override // android.view.View
    protected void onVisibilityChanged(View view, int visibility) {
        if (visibility != 0) {
            releasedKey(false);
        }
        super.onVisibilityChanged(view, visibility);
    }

    void handleKeyUp(Key key) {
        Key key2 = this.pressedKey;
        if (key2 != null) {
            releasedKey(key2.equals(key));
        }
    }

    private void handleKeyDown(Key key) {
        this.pressedKey = key;
        if (key != null) {
            key.onPressed();
            invalidateKey(key);
            AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(key.getCode(), this);
            this.keyEventListener.onKeyDown(key);
        }
    }
}
