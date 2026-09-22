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
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.keyboard.internal.LongPressTimer;

import dev.bbkb.ime.BuildConfig;


public class SimplifiedKeyboardView extends KeyboardView {

    private static final onKeyEventListener NO_OP_KEY_LISTENER = new onKeyEventListener() {
        @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
        public void onKeyDown(Key key) {
        }

        @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
        public void onKeyUp(Key key, boolean z) {
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
     * Long-press timeout callback. Allocated once: this used to be a fresh anonymous class
     * capturing the key on EVERY ACTION_DOWN that landed on a long-press-capable key. Reading
     * {@link #pressedKey} at fire time is equivalent - handleKeyDown assigns it immediately
     * after start(), and every new ACTION_DOWN cancels the timer first.
     */
    private final LongPressTimer.Callback longPressCallback = new LongPressTimer.Callback() {
        @Override
        public void onTimeout() {
            SimplifiedKeyboardView.this.handleKeyUp(SimplifiedKeyboardView.this.pressedKey);
        }
    };

    public interface onKeyEventListener {
        void onKeyDown(Key key);

        void onKeyUp(Key key, boolean z);

        void onKeyLongPress(Key key);
    }

    public SimplifiedKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    public SimplifiedKeyboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.keyboardActionListener = new KeyDetector();
        this.keyboardCoordinates = new LongPressTimer();
        this.keyEventListener = NO_OP_KEY_LISTENER;
    }

    /**
     * The aux bars (UIM, arrows, accents, diacritics) render as one flat surface in modern
     * themes. A board with real key surfaces (the number pad) overrides this to false and
     * gets KeyboardView's normal painting: keyColor for normal keys, keyColorAlt for
     * functional keys, plus the theme's cap painters.
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
        // Modern boards: the aux bars (UIM, arrows, accents, diacritics) render as
        // one flat surface — no per-key fill, no hairline edge dividers. Press feedback
        // is a rounded translucent state layer centered in the key.
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
        // The highlight-driven tint below is aux-bar semantics (active board = accent,
        // rest = muted). A board with real key surfaces uses KeyboardView's standard
        // tinting so its icons (enter arrow, delete) match the regular VKB.
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

    private boolean releasedKey(boolean z) {
        Key key = this.pressedKey;
        if (key == null) {
            return false;
        }
        key.onReleased();
        invalidateKey(this.pressedKey);
        if (this.keyboardCoordinates.getCount() == 2 && z) {
            this.keyEventListener.onKeyLongPress(this.pressedKey);
        } else {
            this.keyEventListener.onKeyUp(this.pressedKey, z);
        }
        this.pressedKey = null;
        return true;
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        // Every aux bar (UIM, arrows, accents, diacritics) is shown and hidden constantly; a
        // pending long-press message would otherwise fire onKeyUp on a detached view.
        this.keyboardCoordinates.cancel();
        releasedKey(false);
        super.onDetachedFromWindow();
    }

    @Override // android.view.View
    protected void onVisibilityChanged(View view, int i) {
        if (i != 0) {
            releasedKey(false);
        }
        super.onVisibilityChanged(view, i);
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
