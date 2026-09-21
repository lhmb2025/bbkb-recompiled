package dev.bbkb.ime.keyboard.inputboard.fcc;

import android.annotation.SuppressLint;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardItem;
import dev.bbkb.ime.keyboard.inputboard.BoardHeightPolicy;


public final class FccView extends RelativeLayout {

    private static final String TAG = "FccView";

    private boolean mClosing;

    private boolean mIsToggling;

    private final float mNubOffset;

    private final KeyboardSwitcher mKeyboardSwitcher;

    private final int mKeyboardHeight;

    private ActionListener mListener;

    private SelectionProvider mSelectionProvider;

    private ImageView mLeftArrow;

    private ImageView mRightArrow;

    private ImageView mUpArrow;

    private ImageView mDownArrow;

    private ImageView mNub;

    private ImageView mCopyButton;  // copy (top-left)

    private ImageView mCutButton;  // cut (top-right)

    private ImageView mSelectButton;  // select (bottom-left)
    
    private ImageView mPasteButton;  // paste (bottom-right)

    private boolean mSelectMode;

    private float mNubOriginalX;

    private float mNubOriginalY;

    private Handler mHandler;

    private Runnable mRepeatRunnable;

    private Runnable mResetNubRunnable;

    private final long mRepeatStartTimeout;

    private final long mRepeatInterval;

    private Rect mArrowBounds;

    private boolean mNubMoved;

    private Direction mCurrentDirection;

    private boolean mInitialized;

    private boolean mNubDepressed;

    private boolean mOpening;

    
    public enum Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    
    public interface ActionListener {
        void onKeyDown(int i);

        void onMove(Direction enumC0992a);

        void onKeyUp(int i);

        void onToggleSelect(int i);
    }

    
    public interface SelectionProvider {
        boolean hasSelection();
    }

    public ActionListener getListener() {
        return this.mListener;
    }

    public ImageView getLeftFccArrow() {
        return this.mLeftArrow;
    }

    public ImageView getRightFccArrow() {
        return this.mRightArrow;
    }

    public ImageView getUpFccArrow() {
        return this.mUpArrow;
    }

    public ImageView getDownFccArrow() {
        return this.mDownArrow;
    }

    public ImageView getFccNub() {
        return this.mNub;
    }

    public float getFccNubOriginalX() {
        return this.mNubOriginalX;
    }

    public float getFccNubOriginalY() {
        return this.mNubOriginalY;
    }

    public boolean isSelectMode() {
        return this.mSelectMode;
    }

    @Override // android.view.View
    public Handler getHandler() {
        return this.mHandler;
    }

    public Runnable getRepeatRunnable() {
        return this.mRepeatRunnable;
    }

    public void setNubDepressed(boolean z) {
        this.mNubDepressed = z;
    }

    public void setRepeatRunnable(Runnable runnable) {
        this.mRepeatRunnable = runnable;
    }

    public boolean isOpening() {
        return this.mOpening;
    }

    public void setOpening(boolean z) {
        this.mOpening = z;
    }

    public boolean isClosing() {
        return this.mClosing;
    }

    public void setClosing(boolean z) {
        this.mClosing = z;
    }

    public boolean isToggling() {
        return this.mIsToggling;
    }

    public void setListeners(ActionListener interfaceC0993b, SelectionProvider interfaceC0994c) {
        if (interfaceC0993b == null) {
            stopRepeat();
        }
        this.mListener = interfaceC0993b;
        this.mSelectionProvider = interfaceC0994c;
    }

    public FccView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mHandler = new Handler(android.os.Looper.getMainLooper());
        Resources resources = context.getResources();
        this.mRepeatStartTimeout = resources.getInteger(R.integer.config_key_repeat_start_timeout);
        this.mRepeatInterval = resources.getInteger(R.integer.config_key_repeat_interval);
        this.mNubOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15.0f, getResources().getDisplayMetrics());
        this.mResetNubRunnable = new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                FccView.this.resetNubPosition();
            }
        };
        hide();
        this.mKeyboardSwitcher = KeyboardSwitcher.getInstance();
        this.mKeyboardHeight = BoardHeightPolicy.fallbackHeight(resources);
    }

    @Override // android.view.View
    protected void onFinishInflate() {
        super.onFinishInflate();
        this.mLeftArrow = (ImageView) findViewById(R.id.left_fcc_caret);
        this.mRightArrow = (ImageView) findViewById(R.id.right_fcc_caret);
        this.mUpArrow = (ImageView) findViewById(R.id.up_fcc_caret);
        this.mDownArrow = (ImageView) findViewById(R.id.down_fcc_caret);
        this.mNub = (ImageView) findViewById(R.id.fcc_nub);
        this.mCopyButton = (ImageView) findViewById(R.id.copy);
        this.mCutButton = (ImageView) findViewById(R.id.cut);
        this.mSelectButton = (ImageView) findViewById(R.id.select);
        this.mPasteButton = (ImageView) findViewById(R.id.paste);
        
        // Apply colors from KeyboardColorManager
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
            // Modern boards share the keyboard's background surface; legacy keyColor.
            setBackgroundColor(modernBoards
                    ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                    : KeyboardColorManager.INSTANCE.getKeyColor());

            // Tint control icons with iconColorAlt (lighter secondary color)
            int iconColorAlt = KeyboardColorManager.INSTANCE.getIconColorAlt();
            KeyboardColorManager.INSTANCE.tint(this.mLeftArrow, iconColorAlt); // left arrow
            KeyboardColorManager.INSTANCE.tint(this.mRightArrow, iconColorAlt); // right arrow
            KeyboardColorManager.INSTANCE.tint(this.mUpArrow, iconColorAlt); // up arrow
            KeyboardColorManager.INSTANCE.tint(this.mDownArrow, iconColorAlt); // down arrow
            // Legacy: mNub keeps its original asset color. (Under Material the whole
            // legacy group — nub included — is replaced by the trackpad layout below;
            // the split disc/rings drawables remain available should the nub return.)
            KeyboardColorManager.INSTANCE.tint(this.mCopyButton, iconColorAlt); // copy
            KeyboardColorManager.INSTANCE.tint(this.mCutButton, iconColorAlt); // cut
            KeyboardColorManager.INSTANCE.tint(this.mSelectButton, iconColorAlt); // select
            KeyboardColorManager.INSTANCE.tint(this.mPasteButton, iconColorAlt); // paste

            // Modern boards get the redesigned layout (trackpad + word jump + chips);
            // legacy styles keep the original nub + corner-glyph layout.
            View legacyGroup = findViewById(R.id.fcc_legacy_group);
            View materialGroup = findViewById(R.id.fcc_material_group);
            legacyGroup.setVisibility(modernBoards ? View.GONE : View.VISIBLE);
            materialGroup.setVisibility(modernBoards ? View.VISIBLE : View.GONE);
            if (modernBoards) {
                setupMaterialGroup();
            }

            // Tint the axis/target with keyColorAlt
            android.widget.ImageView fccTarget = (android.widget.ImageView) findViewById(R.id.fcc_target);
            if (fccTarget != null) {
                KeyboardColorManager.INSTANCE.tint(
                    fccTarget,
                    KeyboardColorManager.INSTANCE.getKeyColorAlt()
                );
            }
        }
    }

    private void applyMaterialActionButton(ImageView button) {
        int minPx = (int) (48 * getResources().getDisplayMetrics().density);
        button.setMinimumWidth(minPx);
        button.setMinimumHeight(minPx);
        button.setForeground(KeyboardColorManager.pressedHighlight());
    }

    // ===== Material redesign: trackpad + word jump + labeled action chips =====

    private android.widget.LinearLayout mChipSelect;
    private android.widget.LinearLayout mChipCopy;
    private android.widget.LinearLayout mChipCut;
    private android.widget.LinearLayout mChipPaste;
    private boolean mMaterialGroupActive;

    private void setupMaterialGroup() {
        this.mMaterialGroupActive = true;
        final float density = getResources().getDisplayMetrics().density;
        int hintColor = KeyboardColorManager.INSTANCE.getHintColor(KeyboardColorManager.ALPHA_FULL);

        // Trackpad surface: rounded key-colored panel, faint affordance in the middle.
        View trackpad = findViewById(R.id.fcc_trackpad);
        android.graphics.drawable.GradientDrawable pad =
                new android.graphics.drawable.GradientDrawable();
        pad.setColor(KeyboardColorManager.INSTANCE.getKeyColor());
        pad.setCornerRadius(16 * density);
        trackpad.setBackground(pad);
        int affordanceColor = KeyboardColorManager.INSTANCE.applyAlpha(hintColor, 0.55f);
        KeyboardColorManager.INSTANCE.tint(
                (ImageView) findViewById(R.id.fcc_trackpad_icon), affordanceColor);
        ((android.widget.TextView) findViewById(R.id.fcc_trackpad_hint))
                .setTextColor(affordanceColor);
        trackpad.setOnTouchListener(createTrackpadListener(density));

        // Word-jump keys: ctrl+dpad through the same listener path as the actions.
        setupJumpKey((ImageView) findViewById(R.id.fcc_jump_left), 21);
        setupJumpKey((ImageView) findViewById(R.id.fcc_jump_right), 22);

        // Action chips
        this.mChipSelect = findViewById(R.id.fcc_chip_select);
        this.mChipCopy = findViewById(R.id.fcc_chip_copy);
        this.mChipCut = findViewById(R.id.fcc_chip_cut);
        this.mChipPaste = findViewById(R.id.fcc_chip_paste);
        styleChip(this.mChipSelect, false);
        styleChip(this.mChipCopy, false);
        styleChip(this.mChipCut, false);
        styleChip(this.mChipPaste, false);

        // Select: tap toggles selection mode, long-press selects all (mirrors the
        // legacy select glyph's behavior).
        this.mChipSelect.setOnClickListener(v -> {
            if (mListener == null) return;
            toggleSelectMode(1);
            playKeyFeedback();
            updateButtons();
        });
        this.mChipSelect.setOnLongClickListener(v -> {
            if (mListener == null) return false;
            playKeyFeedback();
            mListener.onKeyDown(29);
            mListener.onKeyUp(29);
            updateButtons();
            return true;
        });
        this.mChipCopy.setOnClickListener(v -> runChipAction(this.mChipCopy, 31, 1));
        this.mChipCut.setOnClickListener(v -> runChipAction(this.mChipCut, 52, 0));
        this.mChipPaste.setOnClickListener(v -> runChipAction(this.mChipPaste, 50, -1));
    }

    private void setupJumpKey(ImageView key, final int dpadKeyCode) {
        KeyboardColorManager.INSTANCE.tint(
                key, KeyboardColorManager.INSTANCE.getIconColorAlt());
        key.setForeground(KeyboardColorManager.pressedHighlight());
        key.setOnClickListener(v -> {
            if (mListener == null) return;
            playKeyFeedback();
            mListener.onKeyDown(dpadKeyCode);
            mListener.onKeyUp(dpadKeyCode);
        });
    }

    /** Copy/cut/paste chip behavior, mirroring the legacy corner buttons (minus toasts). */
    private void runChipAction(android.widget.LinearLayout chip, int keyCode, int collapseDirection) {
        if (mListener == null) return;
        playKeyFeedback();
        mListener.onKeyDown(keyCode);
        mListener.onKeyUp(keyCode);
        if (this.mSelectMode) {
            toggleSelectMode(collapseDirection);
        }
        updateButtons();
        flashChip(chip);
    }

    /** Normal (or accent-active) chip styling. */
    private void styleChip(android.widget.LinearLayout chip, boolean active) {
        float density = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(active
                ? KeyboardColorManager.INSTANCE.getAccentColor()
                : KeyboardColorManager.INSTANCE.getKeyColorAlt());
        bg.setCornerRadius(20 * density);
        chip.setBackground(bg);
        // Press layer matches the pill outline (radius = height/2, no inset).
        chip.setForeground(KeyboardColorManager.pressedHighlight(20f, 0f));
        int contentColor = active
                ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                : KeyboardColorManager.INSTANCE.getTextColor();
        KeyboardColorManager.INSTANCE.tint((ImageView) chip.getChildAt(0), contentColor);
        ((android.widget.TextView) chip.getChildAt(1)).setTextColor(contentColor);
    }

    /** Brief accent flash as the action confirmation (replaces the legacy toasts). */
    private void flashChip(final android.widget.LinearLayout chip) {
        styleChip(chip, true);
        this.mHandler.postDelayed(() -> {
            if (chip == this.mChipSelect) {
                styleChip(chip, this.mSelectMode);
            } else {
                styleChip(chip, false);
            }
        }, 500L);
    }

    private void setChipEnabled(android.widget.LinearLayout chip, boolean enabled) {
        if (chip == null) return;
        chip.setEnabled(enabled);
        chip.setAlpha(enabled ? 1.0f : 0.38f);
    }

    /**
     * Trackpad drag: emit one cursor step per threshold of movement, per axis.
     * Vertical steps run coarser than horizontal so line jumps don't fire while
     * scrubbing along a line.
     */
    private View.OnTouchListener createTrackpadListener(final float density) {
        final float stepX = 18 * density;
        final float stepY = 30 * density;
        return new View.OnTouchListener() {
            private float lastX;
            private float lastY;

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = motionEvent.getX();
                        lastY = motionEvent.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (mListener == null) return true;
                        while (motionEvent.getX() - lastX >= stepX) {
                            mListener.onMove(Direction.RIGHT);
                            playKeyFeedback();
                            lastX += stepX;
                        }
                        while (lastX - motionEvent.getX() >= stepX) {
                            mListener.onMove(Direction.LEFT);
                            playKeyFeedback();
                            lastX -= stepX;
                        }
                        while (motionEvent.getY() - lastY >= stepY) {
                            mListener.onMove(Direction.DOWN);
                            playKeyFeedback();
                            lastY += stepY;
                        }
                        while (lastY - motionEvent.getY() >= stepY) {
                            mListener.onMove(Direction.UP);
                            playKeyFeedback();
                            lastY -= stepY;
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    @Override // android.view.ViewGroup, android.view.View
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (this.mInitialized) {
            return;
        }
        this.mInitialized = true;
        this.mNubOriginalX = this.mNub.getX();
        this.mNubOriginalY = this.mNub.getY();
        setupArrowListeners();
        setupButtonListeners();
        this.mNub.setOnTouchListener(new FccCursorTouchListener(this));
    }

    @Override // android.widget.RelativeLayout, android.view.View
    protected void onMeasure(int i, int i2) {
        setMeasuredDimension(
                BoardHeightPolicy.measuredWidth(this),
                BoardHeightPolicy.measuredHeight(this, this.mKeyboardSwitcher, this.mKeyboardHeight));
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getMeasuredHeight(), View.MeasureSpec.EXACTLY));
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    private boolean hasSelection() {
        SelectionProvider interfaceC0994c = this.mSelectionProvider;
        return interfaceC0994c != null && interfaceC0994c.hasSelection();
    }

    public void show() {
        updateButtons();
        if (hasSelection()) {
            toggleSelectMode(-1);
        }
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        setVisibility(View.GONE);
        stopRepeat();
        if (this.mSelectMode) {
            toggleSelectMode(-1);
        }
    }

        void toggleSelectMode(int i) {
        this.mIsToggling = true;
        this.mSelectMode = true ^ this.mSelectMode;
        this.mListener.onToggleSelect(i);
        if (this.mSelectMode) {
            this.mSelectButton.setImageResource(R.drawable.ic_inputboard_fcc_selecting_mode);
            this.mLeftArrow.setImageResource(R.drawable.ic_inputboard_fcc_arrow_left);
            this.mRightArrow.setImageResource(R.drawable.ic_inputboard_fcc_arrow_right);
            this.mUpArrow.setImageResource(R.drawable.ic_inputboard_fcc_arrow_up);
            this.mDownArrow.setImageResource(R.drawable.ic_inputboard_fcc_arrow_down);
        } else {
            this.mSelectButton.setImageResource(R.drawable.ic_inputboard_fcc_shift);
        }
        // Material: the select chip fills accent while selection mode is engaged —
        // the icon swap alone gives no color cue that the mode is active.
        if (this.mMaterialGroupActive && this.mChipSelect != null) {
            styleChip(this.mChipSelect, this.mSelectMode);
        }
        this.mIsToggling = false;
    }

    private void setupArrowListeners() {
        this.mLeftArrow.setOnTouchListener(createArrowTouchListener(Direction.LEFT));
        this.mRightArrow.setOnTouchListener(createArrowTouchListener(Direction.RIGHT));
        this.mUpArrow.setOnTouchListener(createArrowTouchListener(Direction.UP));
        this.mDownArrow.setOnTouchListener(createArrowTouchListener(Direction.DOWN));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupButtonListeners() {
        // Copy button (top-left) - keycode 31
        this.mCopyButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        FccView.this.playKeyFeedback();
                        FccView.this.mListener.onKeyDown(31);
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        FccView.this.updateButtons();
                        if (FccView.this.mSelectMode) {
                            FccView.this.toggleSelectMode(1);
                        }
                        FccView.this.mListener.onKeyUp(31);
                        Toast.makeText(FccView.this.getContext(), R.string.clipboard_copy_toast_message, Toast.LENGTH_SHORT).show();
                        view.performClick();
                        return true;
                    default:
                        view.setPressed(false);
                        return true;
                }
            }
        });
        
        // Cut button (top-right) - keycode 52
        this.mCutButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        FccView.this.playKeyFeedback();
                        FccView.this.mListener.onKeyDown(52);
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        FccView.this.mListener.onKeyUp(52);
                        if (FccView.this.mSelectMode) {
                            FccView.this.toggleSelectMode(0);
                        }
                        FccView.this.updateButtons();
                        Toast.makeText(FccView.this.getContext(), R.string.clipboard_copy_toast_message, Toast.LENGTH_SHORT).show();
                        view.performClick();
                        return true;
                    default:
                        view.setPressed(false);
                        return true;
                }
            }
        });
        
        // Paste button (bottom-right) - keycode 50
        this.mPasteButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        FccView.this.playKeyFeedback();
                        FccView.this.mListener.onKeyDown(50);
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        if (FccView.this.mSelectMode) {
                            FccView.this.toggleSelectMode(-1);
                        }
                        FccView.this.updateButtons();
                        FccView.this.mListener.onKeyUp(50);
                        view.performClick();
                        return true;
                    default:
                        view.setPressed(false);
                        return true;
                }
            }
        });
        
        // Select button (bottom-left) - click toggles selection, long-press = select all
        this.mSelectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FccView.this.toggleSelectMode(1);
                FccView.this.playKeyFeedback();
                FccView.this.updateButtons();
            }
        });
        this.mSelectButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                // Select All - keycode 29
                FccView.this.playKeyFeedback();
                FccView.this.mListener.onKeyDown(29);
                FccView.this.mListener.onKeyUp(29);
                FccView.this.updateButtons();
                return true; // consume the long click
            }
        });
    }

    private View.OnTouchListener createArrowTouchListener(final Direction enumC0992a) {
        return new View.OnTouchListener() {
            @Override // android.view.View.OnTouchListener
            public boolean onTouch(View view, MotionEvent motionEvent) {
                switch (motionEvent.getAction()) {
                    case 0:
                        if (FccView.this.mNubDepressed || FccView.this.mRepeatRunnable != null) {
                            return false;
                        }
                        FccView.this.animateNub(enumC0992a, true);
                        FccView.this.mArrowBounds = new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
                        if (FccView.this.mListener != null) {
                            FccView.this.mListener.onMove(enumC0992a);
                        }
                        FccView.this.startRepeat(enumC0992a);
                        FccView.this.playKeyFeedback();
                        return true;
                    case 2:
                        if (!FccView.this.mArrowBounds.contains(view.getLeft() + ((int) motionEvent.getX()), view.getTop() + ((int) motionEvent.getY()))) {
                            if (FccView.this.mNubMoved) {
                                FccView.this.resetNubPosition();
                                FccView.this.mNubMoved = false;
                            }
                            FccView.this.stopRepeat();
                            view.performClick();
                        }
                        return true;
                    case 1:
                    case 3:
                        if (FccView.this.mNubMoved) {
                            FccView.this.resetNubPosition();
                            FccView.this.mNubMoved = false;
                        }
                        FccView.this.stopRepeat();
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

        void animateNub(Direction enumC0992a, boolean z) {
        ViewPropertyAnimator viewPropertyAnimatorY;
        this.mCurrentDirection = enumC0992a;
        switch (enumC0992a) {
            case UP:
                viewPropertyAnimatorY = this.mNub.animate().y(this.mNubOriginalY - this.mNubOffset);
                break;
            case RIGHT:
                viewPropertyAnimatorY = this.mNub.animate().x(this.mNubOriginalX + this.mNubOffset);
                break;
            case DOWN:
                viewPropertyAnimatorY = this.mNub.animate().y(this.mNubOriginalY + this.mNubOffset);
                break;
            case LEFT:
                viewPropertyAnimatorY = this.mNub.animate().x(this.mNubOriginalX - this.mNubOffset);
                break;
            default:
                viewPropertyAnimatorY = null;
                break;
        }
        if (viewPropertyAnimatorY != null) {
            viewPropertyAnimatorY.setDuration(75L);
            if (z) {
                viewPropertyAnimatorY.withEndAction(this.mResetNubRunnable);
            }
        }
    }

        ViewPropertyAnimator resetNubPosition() {
        return this.mNub.animate().x(this.mNubOriginalX).y(this.mNubOriginalY).setDuration(125L);
    }

        void startRepeat(final Direction enumC0992a) {
        this.mHandler.removeCallbacks(this.mRepeatRunnable);
        this.mRepeatRunnable = new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                if (FccView.this.mListener != null) {
                    if (!FccView.this.mNubMoved) {
                        FccView.this.animateNub(enumC0992a, false);
                        FccView.this.playKeyFeedback();
                        FccView.this.mNubMoved = true;
                    }
                    FccView.this.mListener.onMove(enumC0992a);
                }
                FccView.this.mHandler.postDelayed(this, FccView.this.mRepeatInterval);
            }
        };
        this.mHandler.postDelayed(this.mRepeatRunnable, this.mRepeatStartTimeout);
    }

    public void playKeyFeedback() {
        AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(-42, this);
    }

    public void stopRepeat() {
        this.mHandler.removeCallbacksAndMessages(null);
        this.mRepeatRunnable = null;
    }

    public boolean hasClipboardContent() {
        ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipDescription primaryClipDescription = clipboardManager.getPrimaryClipDescription();
        boolean z = primaryClipDescription != null && (primaryClipDescription.hasMimeType("text/plain") || primaryClipDescription.hasMimeType("text/html"));
        String strM6964a = ClipboardItem.getTextFromClipData(clipboardManager.getPrimaryClip());
        return (!z || strM6964a == null || strM6964a.equals("")) ? false : true;
    }

    public void updateButtons() {
        boolean hasClipboard = hasClipboardContent();  // clipboard has pasteable content
        boolean hasSelection = hasSelection();  // text is selected

        // Paste button (bottom-right) - enabled when clipboard has content
        this.mPasteButton.setEnabled(hasClipboard);
        this.mPasteButton.setImageResource(R.drawable.ic_inputboard_fcc_paste);

        // Copy button (top-left) - enabled when text is selected
        this.mCopyButton.setEnabled(hasSelection);
        this.mCopyButton.setImageResource(R.drawable.ic_inputboard_fcc_copy);

        // Cut button (top-right) - enabled when text is selected
        this.mCutButton.setEnabled(hasSelection);
        this.mCutButton.setImageResource(R.drawable.ic_inputboard_fcc_cut);

        // Material chips mirror the same enablement
        if (this.mMaterialGroupActive) {
            setChipEnabled(this.mChipPaste, hasClipboard);
            setChipEnabled(this.mChipCopy, hasSelection);
            setChipEnabled(this.mChipCut, hasSelection);
        }
    }

}
