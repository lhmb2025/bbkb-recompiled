package dev.bbkb.ime.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Build;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.TypefaceUtils;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiKeyboard;
import dev.bbkb.ime.keyboard.internal.KeyVisualAttributes;
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.KeyDrawParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.TreeMap;
import dev.bbkb.ime.BuildConfig;


public class KeyboardView extends View {

    private static final Paint PAGE_INDICATOR_PAINT = new Paint();

    private final HashSet<Key> invalidatedKeys;

    private final Rect keyBounds;

    private final Rect invalidatedRegion;

    private Bitmap cachedKeyboardBitmap;

    private final Canvas bitmapCanvas;

    private final Paint paint;

    private final Paint.FontMetrics fontMetrics;

    private boolean hintsEnabled;

    private boolean mappedMode;

    private Context context;

    private SharedPreferences sharedPreferences;

    /** Per-key draw state, rebuilt whenever the keyboard or the palette changes. */
    private final HashMap<Key, KeyDrawParams> keyFormatters = new HashMap<>();

    private final HashMap<Key, String[]> multilineHintLines = new HashMap<>();

    /** Keys of the current partial-redraw pass; see drawKeyboard. */
    private final ArrayList<Key> drawList = new ArrayList<>();

    private static final int[] EMPTY_ROW_EXTENTS = new int[0];

    /** Row tops/bottoms of the current keyboard, ascending; see drawFrets. */
    private int[] rowTops = EMPTY_ROW_EXTENTS;

    private int[] rowBottoms = EMPTY_ROW_EXTENTS;

    /** Display density, cached: it cannot change without the view being recreated. */
    private final float density;

    /** Reusable per-key background scratch state for drawKeyBackground and the cap painters. */
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint dividerPaint = new Paint();

    private final Paint capStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF capRect = new RectF();

    private final RectF capHighlightRect = new RectF();

    protected final KeyDrawParams textFormatter;

    private final KeyVisualAttributes defaultKeyStyleData;

    private final int keyboardMode;

    private final float hintLetterPadding;

    private final float multilineHintLabelPadding;

    private final String popupHintLetter;

    private final float popupHintLetterPadding;

    private final float shiftedLetterHintPadding;

    private final float textShadowRadius;

    private final float verticalCorrection;

    private final float spacebarIconWidth;

    private final int pageIndicatorActiveColor;

    private final int pageIndicatorInactiveColor;

    private int maxVkbSymbolPage;

    private int maxPkbSymbolPage;

    private Keyboard keyboard;

    private boolean needsFullRedraw;

    /** The current keyboard's keys, for a cheap "is this key drawn here" check. */
    private final HashSet<Key> keyboardKeys = new HashSet<>();

    /**
     * Per-key RenderNodes for keys whose look changes per tap (API 29+, hardware canvas, opt-in via
     * {@link #usesKeyLayers}). HWUI damages the whole view when its display list changes, which on
     * e-ink is a full-keyboard refresh per tap; a press re-records only its node instead. Each node
     * repaints the background and frets under its key, so this needs an opaque background.
     */
    private KeyLayers keyLayers;

    /** The display list last recorded references the key layers; see {@link #onDraw}. */
    private boolean keyLayersInDisplayList;

    /** Recording the at-rest keyboard: no pressed states, no per-tap content. */
    private boolean drawingRestingKeys;

    /** {@link #drawHintLabelOrIcon} scratch. */
    private final Rect hintBounds = new Rect();

    private final int hintIconSize;

    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = new kotlin.jvm.functions.Function0<kotlin.Unit>() {
        @Override
        public kotlin.Unit invoke() {
            rebuildPerKeyDrawState();
            updateKeyboardBackground();
            sculptedFillCache.clear();
            fretFillCache.clear();
            invalidateAllKeys();
            return kotlin.Unit.INSTANCE;
        }
    };

    static {
        PAGE_INDICATOR_PAINT.setTextAlign(Paint.Align.CENTER);
        PAGE_INDICATOR_PAINT.setAntiAlias(true);
    }

    public KeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.keyboardViewStyle);
    }

    public KeyboardView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        this.textFormatter = new KeyDrawParams();
        this.invalidatedKeys = new HashSet<>();
        this.keyBounds = new Rect();
        this.invalidatedRegion = new Rect();
        this.bitmapCanvas = new Canvas();
        this.paint = new Paint();
        this.fontMetrics = new Paint.FontMetrics();
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.KeyboardView, defStyleAttr, R.style.KeyboardView);
        try {
            this.spacebarIconWidth = typedArrayObtainStyledAttributes.getFloat(R.styleable.KeyboardView_spacebarIconWidthRatio, 1.0f);
            this.hintLetterPadding = typedArrayObtainStyledAttributes.getDimension(R.styleable.KeyboardView_keyHintLetterPadding, 0.0f);
            this.multilineHintLabelPadding = typedArrayObtainStyledAttributes.getDimension(R.styleable.KeyboardView_keyMultilineHintLabelPadding, 0.0f);
            this.popupHintLetter = typedArrayObtainStyledAttributes.getString(R.styleable.KeyboardView_keyPopupHintLetter);
            this.popupHintLetterPadding = typedArrayObtainStyledAttributes.getDimension(R.styleable.KeyboardView_keyPopupHintLetterPadding, 0.0f);
            this.shiftedLetterHintPadding = typedArrayObtainStyledAttributes.getDimension(R.styleable.KeyboardView_keyShiftedLetterHintPadding, 0.0f);
            this.textShadowRadius = typedArrayObtainStyledAttributes.getFloat(R.styleable.KeyboardView_keyTextShadowRadius, -1.0f);
            this.verticalCorrection = typedArrayObtainStyledAttributes.getDimension(R.styleable.KeyboardView_verticalCorrection, 0.0f);
        } finally {
            typedArrayObtainStyledAttributes.recycle();
        }
        TypedArray typedArrayObtainStyledAttributes2 = context.obtainStyledAttributes(attributeSet, R.styleable.Keyboard_Key, defStyleAttr, R.style.KeyboardView);
        try {
            this.keyboardMode = typedArrayObtainStyledAttributes2.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0);
            this.defaultKeyStyleData = KeyVisualAttributes.createIfHasStyleAttributes(typedArrayObtainStyledAttributes2);
        } finally {
            typedArrayObtainStyledAttributes2.recycle();
        }
        this.paint.setAntiAlias(true);
        this.density = context.getResources().getDisplayMetrics().density;
        this.hintIconSize = context.getResources().getDimensionPixelSize(R.dimen.config_key_hint_icon_size);
        this.backgroundPaint.setStyle(Paint.Style.FILL);
        this.dividerPaint.setStyle(Paint.Style.FILL);
        this.capStrokePaint.setStyle(Paint.Style.STROKE);
        this.pageIndicatorActiveColor = ContextCompat.getColor(context, R.color.page_switcher_active_color);
        this.pageIndicatorInactiveColor = ContextCompat.getColor(context, R.color.page_switcher_inactive_color);
        this.context = context;
        this.sharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);

        updateKeyboardBackground();
    }

    public KeyVisualAttributes getKeyVisualAttribute() {
        return this.defaultKeyStyleData;
    }

    private void updateKeyboardBackground() {
        setBackground(KeyboardColorManager.fill(keyboardBackgroundColor()));
    }

    /** The view's own fill behind the keys. The slideboard number pad overrides it. */
    protected int keyboardBackgroundColor() {
        return KeyboardColorManager.INSTANCE.getBackgroundColor();
    }

    private static void applyAlphaToColor(Paint paint, int alpha) {
        int color = paint.getColor();
        paint.setARGB((paint.getAlpha() * alpha) / 255, Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setHardwareAcceleratedDrawingEnabled(boolean enabled) {
        if (enabled) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    public void setKeyboard(Keyboard keyboard) {
        Keyboard previous = this.keyboard;
        this.keyboard = keyboard;
        if (this.keyboard.mId != null && LocaleUtils.isChinese(this.keyboard.mId.mLocale)) {
            this.maxVkbSymbolPage = 7;
            this.maxPkbSymbolPage = 107;
        } else {
            this.maxVkbSymbolPage = 6;
            this.maxPkbSymbolPage = 106;
        }
        if (getIsCustomSymbolPageEnabled()) {
            this.maxVkbSymbolPage++;
        }
        if (getIsPkbCustomSymbolPageEnabled()) {
            this.maxPkbSymbolPage++;
        }
        int keyboardHeight = keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap;
        this.textFormatter.applyKeyStyleData(keyboardHeight, this.defaultKeyStyleData);
        this.textFormatter.applyKeyStyleData(keyboardHeight, keyboard.mMoreKeySpec);
        invalidateAllKeys();
        // Relayout only on a size change (onMeasure reads nothing else); emoji pages measure from
        // their key rows, so they always relayout.
        if (previous == null || previous.mOccupiedWidth != keyboard.mOccupiedWidth
                || previous.mOccupiedHeight != keyboard.mOccupiedHeight
                || keyboard instanceof EmojiKeyboard || previous instanceof EmojiKeyboard) {
            requestLayout();
        }
        rebuildPerKeyDrawState();
    }

    /**
     * Must run after anything that changes the base formatter (keyboard swap, palette change,
     * {@link #applyDefaultKeyStyle}): the cached formatters are copies.
     */
    private void rebuildPerKeyDrawState() {
        this.keyFormatters.clear();
        this.multilineHintLines.clear();
        this.drawList.clear();
        this.keyboardKeys.clear();
        if (this.keyboard == null) {
            this.rowTops = EMPTY_ROW_EXTENTS;
            this.rowBottoms = EMPTY_ROW_EXTENTS;
            return;
        }
        // Style data is keyed by key identifier: a key without its own KeyVisualAttributes picks
        // up the style of a same-code key in the same keyboard (never across keyboards).
        HashMap<String, KeyVisualAttributes> styleByIdentifier = new HashMap<>();
        for (Key key : this.keyboard.getKeys()) {
            KeyVisualAttributes styleData = key.getKeyData();
            if (styleData != null) {
                styleByIdentifier.put(getKeyIdentifier(key), styleData);
            }
        }
        int keyboardHeight = this.keyboard.mMostCommonKeyHeight - this.keyboard.mVerticalGap;
        TreeMap<Integer, Integer> rowExtents = new TreeMap<>();
        for (Key key : this.keyboard.getKeys()) {
            this.keyboardKeys.add(key);
            if (!styleByIdentifier.isEmpty()) {
                KeyVisualAttributes styleData = styleByIdentifier.get(getKeyIdentifier(key));
                if (styleData != null) {
                    this.keyFormatters.put(key,
                            this.textFormatter.createWithStyleData(keyboardHeight, styleData));
                }
            }
            if (key.hasMultilineHintLabel()) {
                String hintLabel = key.getHintLabel();
                if (hintLabel != null) {
                    this.multilineHintLines.put(key, hintLabel.split("\n"));
                }
            }
            Integer bottom = rowExtents.get(key.getY());
            int keyBottom = key.getY() + key.getHeight();
            if (bottom == null || keyBottom > bottom) {
                rowExtents.put(key.getY(), keyBottom);
            }
        }
        int rowCount = rowExtents.size();
        this.rowTops = new int[rowCount];
        this.rowBottoms = new int[rowCount];
        int rowIndex = 0;
        for (java.util.Map.Entry<Integer, Integer> row : rowExtents.entrySet()) {
            this.rowTops[rowIndex] = row.getKey();
            this.rowBottoms[rowIndex] = row.getValue();
            rowIndex++;
        }
    }

    public Keyboard getKeyboard() {
        return this.keyboard;
    }

    /** Display density; fixed for the view's life. */
    protected final float getDensity() {
        return this.density;
    }

    protected float getVerticalCorrection() {
        return this.verticalCorrection;
    }

    protected void applyDefaultKeyStyle(int keyboardHeight) {
        this.textFormatter.applyKeyStyleData(keyboardHeight, this.defaultKeyStyleData);
        rebuildPerKeyDrawState();
    }

    @Override // android.view.View
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Keyboard c0965e = this.keyboard;
        if (c0965e == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } else {
            setMeasuredDimension(c0965e.mOccupiedWidth + getPaddingLeft() + getPaddingRight(), this.keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom());
        }
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (canvas.isHardwareAccelerated()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canUseKeyLayers()) {
                // At-rest keys, then the key layers; a later press re-records only its layer.
                this.drawingRestingKeys = true;
                try {
                    drawKeyboard(canvas);
                } finally {
                    this.drawingRestingKeys = false;
                }
                drawKeyLayers(canvas);
                this.keyLayersInDisplayList = true;
                return;
            }
            releaseKeyLayers();
            drawKeyboard(canvas);
            return;
        }
        releaseKeyLayers();
        if ((this.needsFullRedraw || !this.invalidatedKeys.isEmpty()) || this.cachedKeyboardBitmap == null) {
            if (allocateBitmapIfNeeded()) {
                this.needsFullRedraw = true;
                this.bitmapCanvas.setBitmap(this.cachedKeyboardBitmap);
            }
            drawKeyboard(this.bitmapCanvas);
        }
        canvas.drawBitmap(this.cachedKeyboardBitmap, 0.0f, 0.0f, (Paint) null);
    }

    private boolean allocateBitmapIfNeeded() {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return false;
        }
        Bitmap bitmap = this.cachedKeyboardBitmap;
        if (bitmap != null && bitmap.getWidth() == width && this.cachedKeyboardBitmap.getHeight() == height) {
            return false;
        }
        releaseBitmap();
        // Not pooled across views: a size-keyed pool would hand one buffer to two same-sized views
        // (aux strip / arrow bar), and one would erase or recycle the other's.
        this.cachedKeyboardBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return true;
    }

    private void releaseBitmap() {
        this.bitmapCanvas.setBitmap(null);
        this.bitmapCanvas.setMatrix(null);
        Bitmap bitmap = this.cachedKeyboardBitmap;
        if (bitmap != null) {
            bitmap.recycle();
            this.cachedKeyboardBitmap = null;
        }
    }

    private void drawKeyboard(Canvas canvas) {
        if (this.keyboard == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        Paint paint = this.paint;
        boolean shouldDrawAll = this.needsFullRedraw || this.invalidatedKeys.isEmpty();
        boolean isHardwareAccelerated = canvas.isHardwareAccelerated();
        if (shouldDrawAll || isHardwareAccelerated) {
            this.invalidatedRegion.set(0, 0, width, height);
        } else {
            // Resolve membership once: Keyboard.hasKey is a linear scan on a miss.
            this.invalidatedRegion.setEmpty();
            this.drawList.clear();
            Iterator<Key> it = this.invalidatedKeys.iterator();
            while (it.hasNext()) {
                Key next = it.next();
                if (this.keyboard.hasKey(next)) {
                    this.drawList.add(next);
                    int iMo6216ab = next.getX() + getPaddingLeft();
                    int iMo6217ac = next.getY() + getPaddingTop();
                    this.keyBounds.set(iMo6216ab, iMo6217ac, next.getWidth() + iMo6216ab, next.getHeight() + iMo6217ac);
                    this.invalidatedRegion.union(this.keyBounds);
                }
            }
        }
        if (!isHardwareAccelerated) {
            canvas.clipRect(this.invalidatedRegion);
            canvas.drawColor(-16777216, PorterDuff.Mode.CLEAR);
            Drawable background = getBackground();
            if (background != null) {
                background.draw(canvas);
            }
        }
        // Frets repaint on every pass: a partial redraw's clip can span the row gap between two
        // invalidated keys, and the background repaint above would wipe that fret slice.
        if (drawsFrets() && KeyboardColorManager.styleSpec().getFretHeightDp() > 0f) {
            drawFrets(canvas);
        }
        if (shouldDrawAll || isHardwareAccelerated) {
            Iterator<Key> it2 = this.keyboard.getKeys().iterator();
            while (it2.hasNext()) {
                drawKey(it2.next(), canvas, paint);
            }
        } else {
            for (int i = 0; i < this.drawList.size(); i++) {
                drawKey(this.drawList.get(i), canvas, paint);
            }
            this.drawList.clear();
        }
        this.invalidatedKeys.clear();
        this.needsFullRedraw = false;
    }

    private void drawKey(Key key, Canvas canvas, Paint paint) {
        int keyX = key.getDrawX() + getPaddingLeft();
        int keyY = key.getY() + getPaddingTop();
        canvas.translate(keyX, keyY);
        KeyDrawParams c1061tM7418b = this.keyFormatters.get(key);
        if (c1061tM7418b == null) {
            c1061tM7418b = this.textFormatter;
        }
        c1061tM7418b.animAlpha = 255;
        if (key.isEnabled()) {
            drawKeyBackground(key, canvas);
        }
        if (shouldDrawPageIndicator(key)) {
            drawPageIndicator(canvas, key);
        } else {
            drawKeyContent(key, canvas, paint, c1061tM7418b, false);
        }
        canvas.translate(-keyX, -keyY);
    }

    /**
     * Whether the page-switch key ({@code -15}) shows the symbol-page dot row: the current element
     * is one of the pages it cycles. {@link #maxVkbSymbolPage} / {@link #maxPkbSymbolPage} are dot
     * counts, bumped by one for the custom page, whose element id is always 8 / 108 (contiguous
     * with the base pages only for Chinese, which has pages 5 to 7). So the bump is stripped before
     * using the bound as a range, and the custom page is matched by id.
     */
    private boolean shouldDrawPageIndicator(Key key) {
        if (key.getCode() != -15) {
            return false;
        }
        final int elementId = this.keyboard.mId.mElementId;
        final int lastVkbBasePage = this.maxVkbSymbolPage - (getIsCustomSymbolPageEnabled() ? 1 : 0);
        final int lastPkbBasePage = this.maxPkbSymbolPage - (getIsPkbCustomSymbolPageEnabled() ? 1 : 0);
        if (elementId >= 5 && elementId <= lastVkbBasePage) {
            return true;
        }
        if (elementId >= 105 && elementId <= lastPkbBasePage) {
            return true;
        }
        return (getIsCustomSymbolPageEnabled() && elementId == 8)
                || (getIsPkbCustomSymbolPageEnabled() && elementId == 108);
    }

    private void drawPageIndicator(Canvas canvas, Key key) {
        int basePageIndex;
        int dotIndex = 0;
        boolean isPkb = this.keyboard.mId.mElementId >= 105;
        if (!isPkb) {
            basePageIndex = (this.maxVkbSymbolPage - 5) + 1;
        } else {
            basePageIndex = (this.maxPkbSymbolPage - 105) + 1;
        }
        int activePageIndex = getActivePageIndex(isPkb);
        float pageCount = basePageIndex;
        float dotSize = key.getHeight() / pageCount;
        if (basePageIndex > 2) {
            dotSize = (float) (dotSize * ((basePageIndex / 10.0d) + 1.0d));
        }
        PAGE_INDICATOR_PAINT.setTextSize(dotSize);
        float fMeasureText = PAGE_INDICATOR_PAINT.measureText("•");
        float dotSpacing = (key.getHeight() - (pageCount * fMeasureText)) / (basePageIndex + 1);
        while (dotIndex < basePageIndex) {
            PAGE_INDICATOR_PAINT.setColor(activePageIndex == dotIndex ? this.pageIndicatorActiveColor : this.pageIndicatorInactiveColor);
            int nextIndex = dotIndex + 1;
            canvas.drawText("•", key.getWidth() / 2.0f, (this.keyboard.mVerticalGap / 2) + (nextIndex * dotSpacing) + (dotIndex * fMeasureText) + (dotSize / 2.0f), PAGE_INDICATOR_PAINT);
            dotIndex = nextIndex;
        }
    }

    /**
     * The active dot: offset from the family's first symbol page (5 / 105), shifted by
     * {@link #adjustPageIndexForCustomPage} for where the custom page sits in the cycle.
     */
    private int getActivePageIndex(boolean isPkb) {
        int pageIndex = isPkb
                ? this.keyboard.mId.mElementId - 105
                : this.keyboard.mId.mElementId - 5;
        if (isPkb || !getIsCustomSymbolPageEnabled()) {
            return (isPkb && getIsPkbCustomSymbolPageEnabled()) ? adjustPageIndexForCustomPage(isPkb, pageIndex, getIsPkbCustomSymbolPageFirst()) : pageIndex;
        }
        return adjustPageIndexForCustomPage(isPkb, pageIndex, getIsCustomSymbolPageFirst());
    }

    /**
     * Shifts a dot index for where the custom page sits in the cycle; called only when the
     * caller's family has custom pages enabled.
     *
     * <p>Trap: each clause tests {@code basePageIndex} against both families' bounds, which can
     * differ (separate preferences). Benign only because the foreign match is an element id this
     * family cannot reach here (7 / 107 are Chinese-only, and Chinese takes the
     * {@code isRightToLeft} arm first). Do not widen the element range without a per-family bound.
     */
    private int adjustPageIndexForCustomPage(boolean isPkb, int basePageIndex, boolean customPageFirst) {
        boolean isRightToLeft = LocaleUtils.isChinese(this.keyboard.mId.mLocale);
        if (!customPageFirst) {
            return ((isRightToLeft || basePageIndex != (this.maxVkbSymbolPage + 1) + (-5)) && (isRightToLeft || basePageIndex != (this.maxPkbSymbolPage + 1) + (-105))) ? basePageIndex : basePageIndex - 1;
        }
        if ((!isRightToLeft || ((isPkb || basePageIndex != this.maxVkbSymbolPage - 5) && !(isPkb && basePageIndex == this.maxPkbSymbolPage - 105))) && (isRightToLeft || !(basePageIndex == (this.maxVkbSymbolPage + 1) - 5 || basePageIndex == (this.maxPkbSymbolPage + 1) - 105))) {
            return basePageIndex + 1;
        }
        return 0;
    }

    /** The enter/action key ('\n'). */
    private static final int CODE_ENTER = 10;

    /**
     * Weight-500 face (bold below API 28) for {@link StyleSpec} mediumKeyTypeface; also used by
     * KeyPreviewView and FlickSuggestionView so previews match the key face.
     */
    public static Typeface mediumWeightTypeface(Typeface base) {
        Typeface resolved = base != null ? base : Typeface.DEFAULT;
        // Memoised: it runs per labelled key per frame, and Typeface.create is synchronized.
        synchronized (MEDIUM_WEIGHT_TYPEFACES) {
            Typeface medium = MEDIUM_WEIGHT_TYPEFACES.get(resolved);
            if (medium == null) {
                medium = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                        ? Typeface.create(resolved, 500, false)
                        : Typeface.create(resolved, Typeface.BOLD);
                MEDIUM_WEIGHT_TYPEFACES.put(resolved, medium);
            }
            return medium;
        }
    }

    /** Base face to its medium-weight face, by identity; see {@link #mediumWeightTypeface}. */
    private static final IdentityHashMap<Typeface, Typeface> MEDIUM_WEIGHT_TYPEFACES = new IdentityHashMap<>();

    /** backgroundType 8 / 9: the slideboard number pad's keys. */
    private static boolean isSlideKey(Key key) {
        return key.backgroundType == 8 || key.backgroundType == 9;
    }

    /** Opaque pressed fill (flat and sculpted painters). */
    private static int keyPressedColorFor(Key key) {
        return isSlideKey(key)
                ? KeyboardColorManager.INSTANCE.getSlidePressedColor()
                : KeyboardColorManager.INSTANCE.getKeyColorPressed();
    }

    /** Translucent pressed state layer (rounded painter). */
    private static int pressedStateLayerFor(Key key) {
        return isSlideKey(key)
                ? KeyboardColorManager.INSTANCE.getSlidePressedStateLayer()
                : KeyboardColorManager.INSTANCE.getPressedStateLayer();
    }

    /** The key-surface color for a key's backgroundType. */
    private static int keyBackgroundColorFor(Key key) {
        switch (key.backgroundType) {
            case 8: // slide (number pad)
            case 9: // slideFunctional (number pad symbols)
                return KeyboardColorManager.INSTANCE.getSlideKeyColor();
            case 2: // functional (delete, ?123/ABC, enter/search/go)
            case 3: // stickyOff (shift key not locked)
            case 5: // action (custom label action keys)
                return KeyboardColorManager.INSTANCE.getKeyColorAlt();
            case 4: // stickyOn (shift key locked)
                return KeyboardColorManager.INSTANCE.getKeyColorPressed();
            case 1: // normal keys
            case 6: // spacebar
            default:
                return KeyboardColorManager.INSTANCE.getKeyColor();
        }
    }

    protected void drawKeyBackground(Key key, Canvas canvas) {
        StyleSpec spec = KeyboardColorManager.styleSpec();
        switch (spec.getKeyCap()) {
            case ROUNDED:
                drawCapKeyBackground(key, canvas, spec);
                return;
            case SCULPTED:
                drawSculptedKeyBackground(key, canvas, spec);
                return;
            default:
                break;
        }
        int keyWidth = key.getDrawWidth();
        int keyHeight = key.getHeight();

        int backgroundColor = keyBackgroundColorFor(key);

        // backgroundType 0 ("empty") paints no surface, so the container shows through (the arrow
        // bar then matches the flat suggestion strip it replaces).
        if (key.backgroundType != 0) {
            this.backgroundPaint.setColor(backgroundColor);

            canvas.drawRect(0, 0, keyWidth, keyHeight, this.backgroundPaint);
        }

        if (isKeyDrawnPressed(key)) {
            this.backgroundPaint.setColor(keyPressedColorFor(key));

            canvas.drawRect(0, 0, keyWidth, keyHeight, this.backgroundPaint);
        }

        // Hairline dividers on the left and right edges of normal, empty (emoji) and slide keys.
        if (key.backgroundType == 1 || key.backgroundType == 0 || isSlideKey(key)) {
            final float density = this.density;
            final Paint dividerPaint = this.dividerPaint;
            dividerPaint.setColor(isSlideKey(key)
                    ? KeyboardColorManager.INSTANCE.getSlideSeparatorColor()
                    : KeyboardColorManager.INSTANCE.getBackgroundColor());
            float dividerWidth = 0.5f * density;
            float topPadding = keyHeight * 0.08f;
            float bottomPadding = keyHeight * 0.05f;
            canvas.drawRect(0, topPadding, dividerWidth, keyHeight - bottomPadding, dividerPaint);
            canvas.drawRect(keyWidth - dividerWidth, topPadding, keyWidth, keyHeight - bottomPadding, dividerPaint);
        }
    }

    /**
     * Whether the cap painters draw caps for backgroundType 0 ("empty") keys. False keeps aux bars
     * and the arrow bar surfaceless; emoji pages say true so each emoji floats on its own cap.
     */
    protected boolean capPainterDrawsEmptyKeys() {
        return false;
    }

    /**
     * Fills {@link #capRect} with the visible cap of {@code key} in key-local coordinates, for both
     * cap painters (fret placement and corner-hint padding use the same spec insets).
     *
     * @return false when the cap is degenerate and nothing should be drawn
     */
    private boolean fillCapRect(Key key, StyleSpec spec) {
        float insetH = spec.getKeyInsetHDp() * this.density;
        float insetV = spec.getKeyInsetVDp() * this.density;
        this.capRect.set(insetH, insetV, key.getDrawWidth() - insetH, key.getHeight() - insetV);
        return !this.capRect.isEmpty();
    }

    /**
     * Rounded inset caps with a translucent pressed state layer; the enter key becomes an accent
     * pill when the spec sets accentEnterKey. A pressed empty key still gets the state layer.
     */
    private void drawCapKeyBackground(Key key, Canvas canvas, StyleSpec spec) {
        if (!fillCapRect(key, spec)) {
            return;
        }
        final RectF cap = this.capRect;
        boolean accentKey = spec.getAccentEnterKey() && key.getCode() == CODE_ENTER;
        float radius = accentKey ? cap.height() / 2f : spec.getKeyRadiusDp() * this.density;
        final Paint paint = this.backgroundPaint;
        if (key.backgroundType != 0 || capPainterDrawsEmptyKeys()) {
            paint.setColor(accentKey
                    ? KeyboardColorManager.INSTANCE.getAccentColor()
                    : keyBackgroundColorFor(key));
            canvas.drawRoundRect(cap, radius, radius, paint);
        }
        if (isKeyDrawnPressed(key)) {
            paint.setColor(pressedStateLayerFor(key));
            canvas.drawRoundRect(cap, radius, radius, paint);
        }
    }

    /** Whether this view draws the style's fret bars; only the main board opts in. */
    protected boolean drawsFrets() {
        return false;
    }

    /** Fret finish relative to the fret color: ~1dp rims over a light-to-dark body gradient. */
    private static final float FRET_RIM_TOP_LIGHTEN = 0.55f;
    private static final float FRET_RIM_BOTTOM_LIGHTEN = 0.40f;
    private static final float FRET_BODY_TOP_LIGHTEN = 0.30f;
    private static final float FRET_BODY_BOTTOM_LIGHTEN = 0.08f;
    private static final float FRET_RIM_DP = 1f;

    /**
     * Fret fill paints keyed by (color, height), since fret heights can differ. Shaders are in
     * fret-local coordinates. Cleared on palette change.
     */
    private final android.util.LongSparseArray<Paint> fretFillCache = new android.util.LongSparseArray<>();

    /** (color, size) cache key; raw float bits so fractionally different heights do not collide. */
    private static long paintCacheKey(int base, float sizePx) {
        return (((long) base) << 32) | (Float.floatToIntBits(sizePx) & 0xFFFFFFFFL);
    }

    private Paint fretFillPaint(int base, float fretHeightPx) {
        long cacheKey = paintCacheKey(base, fretHeightPx);
        Paint cached = fretFillCache.get(cacheKey);
        if (cached == null) {
            float rim = Math.min(FRET_RIM_DP * this.density,
                    fretHeightPx / 3f);
            float rimStop = rim / fretHeightPx;
            // Duplicated stops make the rim edges hard instead of feathered.
            int[] colors = {
                    ColorUtils.blendARGB(base, Color.WHITE, FRET_RIM_TOP_LIGHTEN),
                    ColorUtils.blendARGB(base, Color.WHITE, FRET_RIM_TOP_LIGHTEN),
                    ColorUtils.blendARGB(base, Color.WHITE, FRET_BODY_TOP_LIGHTEN),
                    ColorUtils.blendARGB(base, Color.WHITE, FRET_BODY_BOTTOM_LIGHTEN),
                    ColorUtils.blendARGB(base, Color.WHITE, FRET_RIM_BOTTOM_LIGHTEN),
                    ColorUtils.blendARGB(base, Color.WHITE, FRET_RIM_BOTTOM_LIGHTEN),
            };
            float[] stops = {0f, rimStop, rimStop, 1f - rimStop, 1f - rimStop, 1f};
            cached = new Paint(Paint.ANTI_ALIAS_FLAG);
            cached.setStyle(Paint.Style.FILL);
            cached.setShader(new LinearGradient(
                    0f, 0f, 0f, fretHeightPx, colors, stops, Shader.TileMode.CLAMP));
            fretFillCache.put(cacheKey, cached);
        }
        return cached;
    }

    /**
     * BB10 frets: full-width bars between key rows plus one at the top edge, placed from the
     * visible cap bands (key rects plus cap insets, not mVerticalGap) so a seam always survives
     * and caps never overlap a fret. Drawn after the background, before the keys.
     */
    private void drawFrets(Canvas canvas) {
        StyleSpec spec = KeyboardColorManager.styleSpec();
        final float density = this.density;
        float fretH = spec.getFretHeightDp() * density;
        float insetV = spec.getKeyInsetVDp() * density;
        final int[] rowTops = this.rowTops;
        final int[] rowBottoms = this.rowBottoms;
        if (rowTops.length == 0) {
            return;
        }
        int width = getWidth();
        int paddingTop = getPaddingTop();
        float minSeam = 0.5f * density;

        // Every fret is full height and bottom-anchored the same seam above the caps below it.
        // The seam is the symmetric interior one, capped by what the top fret can reach without
        // clipping, so the top fret does not sit visibly lower than the rest.
        float firstCapTop = rowTops[0] + paddingTop + insetV;
        float seam = firstCapTop - fretH;
        if (rowTops.length > 1) {
            float band = (rowTops[1] + insetV) - (rowBottoms[0] - insetV);
            seam = Math.min(seam, (band - fretH) / 2f);
        }
        seam = Math.max(minSeam, seam);

        float topFretBottom = firstCapTop - seam;
        if (topFretBottom > 0f) {
            drawFretBar(canvas, width, topFretBottom - fretH, fretH);
        }
        for (int row = 1; row < rowTops.length; row++) {
            float capBottomAbove = rowBottoms[row - 1] + paddingTop - insetV;
            float capTopBelow = rowTops[row] + paddingTop + insetV;
            float top = capTopBelow - seam - fretH;
            // Degenerate bands: never overlap the caps above; the seam below absorbs the shortfall.
            top = Math.max(top, capBottomAbove + minSeam);
            drawFretBar(canvas, width, top, fretH);
        }
    }

    private void drawFretBar(Canvas canvas, int width, float top, float height) {
        Paint paint = fretFillPaint(KeyboardColorManager.INSTANCE.getFretColor(), height);
        canvas.translate(0f, top);
        canvas.drawRect(0f, 0f, width, height, paint);
        canvas.translate(0f, -top);
    }

    /** Sculpted cap finish: how far the gradient/highlight sit from the base color. */
    private static final float SCULPT_GRADIENT_LIGHTEN = 0.10f;
    private static final float SCULPT_GRADIENT_DARKEN = 0.18f;
    private static final float SCULPT_HIGHLIGHT_LIGHTEN = 0.28f;
    private static final int SCULPT_HIGHLIGHT_ALPHA = 165;
    private static final float SCULPT_BORDER_DARKEN = 0.18f;

    /** Sculpted cap fill paints keyed by (base color, cap height). Cleared on palette change. */
    private final android.util.LongSparseArray<Paint> sculptedFillCache = new android.util.LongSparseArray<>();

    private Paint sculptedFillPaint(int base, RectF cap) {
        long cacheKey = paintCacheKey(base, cap.height());
        Paint paint = sculptedFillCache.get(cacheKey);
        if (paint == null) {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    0f, cap.top, 0f, cap.bottom,
                    ColorUtils.blendARGB(base, Color.WHITE, SCULPT_GRADIENT_LIGHTEN),
                    ColorUtils.blendARGB(base, Color.BLACK, SCULPT_GRADIENT_DARKEN),
                    Shader.TileMode.CLAMP));
            sculptedFillCache.put(cacheKey, paint);
        }
        return paint;
    }

    /**
     * BB10 sculpted caps: the {@link #drawCapKeyBackground} geometry with a top-lit gradient and a
     * top inner highlight, both derived from the base color. Pressed keys re-derive from
     * keyPressed (opaque) instead of a state layer. No border: the gaps separate keys.
     */
    private void drawSculptedKeyBackground(Key key, Canvas canvas, StyleSpec spec) {
        if (!fillCapRect(key, spec)) {
            return;
        }
        final float density = this.density;
        final RectF cap = this.capRect;
        float radius = spec.getKeyRadiusDp() * density;
        if (key.backgroundType == 0 && !capPainterDrawsEmptyKeys()) {
            // Surfaceless keys (aux bars, arrow bar) still show press feedback.
            if (isKeyDrawnPressed(key)) {
                final Paint pressed = this.backgroundPaint;
                pressed.setColor(KeyboardColorManager.INSTANCE.getPressedStateLayer());
                canvas.drawRoundRect(cap, radius, radius, pressed);
            }
            return;
        }
        int base = isKeyDrawnPressed(key)
                ? keyPressedColorFor(key)
                : keyBackgroundColorFor(key);
        canvas.drawRoundRect(cap, radius, radius, sculptedFillPaint(base, cap));

        float strokeWidth = density;
        final Paint stroke = this.capStrokePaint;
        stroke.setStrokeWidth(strokeWidth);

        // Top inner highlight: the cap outline clipped to the top, so only the lit rim shows.
        final RectF highlightRect = this.capHighlightRect;
        highlightRect.set(cap);
        highlightRect.inset(strokeWidth / 2f, strokeWidth / 2f);
        stroke.setColor(ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(base, Color.WHITE, SCULPT_HIGHLIGHT_LIGHTEN),
                SCULPT_HIGHLIGHT_ALPHA));
        canvas.save();
        canvas.clipRect(cap.left, cap.top, cap.right, cap.top + radius + (2f * strokeWidth));
        canvas.drawRoundRect(highlightRect, Math.max(0f, radius - (strokeWidth / 2f)),
                Math.max(0f, radius - (strokeWidth / 2f)), stroke);
        canvas.restore();
    }

    /** The text painted on a key's face; a subclass can stand another in for it while a mode is on. */
    protected String labelFor(Key key) {
        return key.getLabel();
    }

    /**
     * @param suppressLabel do not paint the label or icon: something else is painted over the face
     *     (the autocorrect word on the spacebar). Not "this is a key preview".
     */
    protected void drawKeyContent(Key key, Canvas canvas, Paint paint, KeyDrawParams formatter, boolean suppressLabel) {
        String label;
        int keyHeight;
        float textY;
        float textX;
        int iconWidth;
        String hintLabel;
        float hintY;
        float hintX;
        int keyWidth = key.getDrawWidth();
        int keyHeightInt = key.getHeight();
        float keyWidthFloat = keyWidth;
        float centerX = keyWidthFloat * 0.5f;
        float centerY = keyHeightInt * 0.5f;
        Drawable iconDrawable = key.getIcon(this.keyboard.mIconsSet, formatter.animAlpha);
        if (iconDrawable != null && KeyboardColorManager.INSTANCE.isInitialized()) {
            applyIconTint(key, iconDrawable);
        }
        float labelBaselineY = (formatter.labelVerticalBaselineRatio + 1.0f) * centerY;
        String labelText = labelFor(key);
        if (suppressLabel || labelText == null) {
            label = labelText;
            keyHeight = keyHeightInt;
            textY = labelBaselineY;
            textX = centerX;
        } else {
            paint.setTypeface(key.getTypeface(formatter));
            paint.setTextSize(key.getLabelSize(formatter));
            StyleSpec labelSpec = KeyboardColorManager.styleSpec();
            if (labelSpec.getLabelSizeScale() != 1f) {
                paint.setTextSize(paint.getTextSize() * labelSpec.getLabelSizeScale());
            }
            if (labelSpec.getMediumKeyTypeface()) {
                paint.setTypeface(mediumWeightTypeface(paint.getTypeface()));
            }
            float textHeight = TypefaceUtils.getReferenceCharHeight(paint);
            float textWidth = TypefaceUtils.getReferenceCharWidth(paint);
            textY = labelBaselineY + (textHeight / 2.0f);
            if (key.isAlignLabelOffCenter()) {
                float labelOffCenterX = centerX + (formatter.labelOffCenterRatio * textWidth);
                paint.setTextAlign(Paint.Align.LEFT);
                textX = labelOffCenterX;
            } else {
                paint.setTextAlign(Paint.Align.CENTER);
                textX = centerX;
            }
            if (key.needsAutoXScale()) {
                float textScale = Math.min(1.0f, (0.9f * keyWidthFloat) / TypefaceUtils.getStringWidth(labelText, paint));
                if (key.needsAutoScale()) {
                    paint.setTextSize(paint.getTextSize() * textScale);
                } else {
                    paint.setTextScaleX(textScale);
                }
            }
            if (key.isActive()) {
                paint.setColor(isSlideKey(key)
                        ? KeyboardColorManager.INSTANCE.getSlideTextColor()
                        : key.getLabelColor(formatter));
                float shadowRadius = this.textShadowRadius;
                if (shadowRadius > 0.0f) {
                    paint.setShadowLayer(shadowRadius, 0.0f, 0.0f, formatter.getTextShadowColor());
                } else {
                    paint.clearShadowLayer();
                }
            } else {
                paint.setColor(0);
                paint.clearShadowLayer();
            }
            applyAlphaToColor(paint, formatter.animAlpha);
            label = labelText;
            keyHeight = keyHeightInt;
            canvas.drawText(labelText, 0, labelText.length(), textX, textY, paint);
            paint.clearShadowLayer();
            paint.setTextScaleX(1.0f);
        }
        if (this.hintsEnabled && (hintLabel = key.getHintLabel()) != null) {
            paint.setTextSize(key.resolveHintLabelSize(formatter));
            paint.setColor(key.resolveHintLabelColor(formatter));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            applyAlphaToColor(paint, formatter.animAlpha);
            float hintTextHeight = TypefaceUtils.getReferenceCharHeight(paint);
            float hintTextWidth = TypefaceUtils.getReferenceCharWidth(paint);
            if (key.hasMultilineHintLabel()) {
                String[] hintLines = this.multilineHintLines.get(key);
                if (hintLines == null) {
                    hintLines = hintLabel.split("\n");
                }
                for (int lineIndex = 0; lineIndex < hintLines.length; lineIndex++) {
                    String line = hintLines[lineIndex];
                    float multilineHintX = (keyWidthFloat - this.multilineHintLabelPadding) - (TypefaceUtils.getStringWidth(line, paint) / 2.0f);
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(line, 0, line.length(), multilineHintX, (hintTextHeight / 2.0f) + (formatter.multilineHintLabelVerticalAdjustmentRatio * hintTextHeight * lineIndex) + this.multilineHintLabelPadding, paint);
                }
            } else {
                if (key.hasHintLabel()) {
                    float hintOffCenterX = textX + (formatter.hintLabelOffCenterRatio * hintTextWidth);
                    if (!key.isAlignHintLabelToBottom(this.keyboardMode)) {
                        textY = centerY + (hintTextHeight / 2.0f);
                    }
                    paint.setTextAlign(Paint.Align.LEFT);
                    hintY = textY;
                    hintX = hintOffCenterX;
                } else if (key.hasShiftedLetterHint()) {
                    float shiftedHintX = (keyWidthFloat - this.shiftedLetterHintPadding) - (hintTextWidth / 2.0f);
                    paint.getFontMetrics(this.fontMetrics);
                    hintY = -this.fontMetrics.top;
                    paint.setTextAlign(Paint.Align.CENTER);
                    hintX = shiftedHintX;
                } else {
                    float hintLetterX = (keyWidthFloat - this.hintLetterPadding) - (Math.max(TypefaceUtils.getReferenceDigitWidth(paint), TypefaceUtils.getStringWidth(hintLabel, paint)) / 2.0f);
                    hintY = -paint.ascent();
                    paint.setTextAlign(Paint.Align.CENTER);
                    hintX = hintLetterX;
                }
                canvas.drawText(hintLabel, 0, hintLabel.length(), hintX, hintY + (formatter.hintLabelVerticalAdjustment * hintTextHeight), paint);
            }
        }
        if (label == null && iconDrawable != null) {
            if (key.getCode() == 32) {
                if (iconDrawable instanceof NinePatchDrawable) {
                    iconWidth = (int) (keyWidthFloat * this.spacebarIconWidth);
                } else {
                    iconWidth = (int) (keyWidthFloat * 0.35f);
                }
            } else {
                iconWidth = Math.min(iconDrawable.getIntrinsicWidth(), keyWidth);
            }
            int intrinsicHeight = iconDrawable.getIntrinsicHeight();
            int iconHeight;
            if (key.getCode() == 32) {
                iconHeight = (int) (6 * this.density);
            } else {
                iconHeight = intrinsicHeight;
            }
            int iconY = key.isAlignIconToBottom() ? keyHeight - iconHeight : (keyHeight - iconHeight) / 2;
            if (key.getCode() == 32) {
                iconY += (int) (8 * this.density);
            }
            int iconX = (keyWidth - iconWidth) / 2;
            if (!suppressLabel && !shouldSkipSpacebarIcon(key)) {
                drawDrawable(canvas, iconDrawable, iconX, iconY, iconWidth, iconHeight);
            }
        }
        if (key.hasLongPressKey()) {
            drawPopupHint(key, canvas, paint, formatter);
        }
        if (!key.hasPopupHint() || key.getMoreKeys() == null) {
            return;
        }
        drawPopupHintLetter(key, canvas, paint, formatter);
    }

    /**
     * Cap-styled spacebars are plain. A normal-width space key that needs the glyph to read as
     * space (the number pad's utility column) overrides this.
     */
    protected boolean shouldSkipSpacebarIcon(Key key) {
        return key.getCode() == 32 && !KeyboardColorManager.styleSpec().getDrawSpacebarIcon();
    }

    /**
     * Whether a hint icon anchors to the key's main icon as a badge instead of the corner. The aux
     * bar's wide icon keys say yes; typing keys keep corner hints.
     */
    protected boolean anchorsHintToMainIcon() {
        return false;
    }

    protected void applyIconTint(Key key, Drawable iconDrawable) {
        if (KeyboardColorManager.styleSpec().getAccentEnterKey() && key.getCode() == CODE_ENTER) {
            // Background color for contrast on the accent-filled action key.
            KeyboardColorManager.INSTANCE.tint(
                    iconDrawable, KeyboardColorManager.INSTANCE.getBackgroundColor());
            return;
        }
        KeyboardColorManager.INSTANCE.tint(iconDrawable);
    }

    @Override // android.view.View
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo accessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(accessibilityNodeInfo);
        accessibilityNodeInfo.getExtras().putBoolean("bbry.inputmethod.HintsEnabled", this.hintsEnabled);
        accessibilityNodeInfo.getExtras().putBoolean("bbry.inputmethod.MappedMode", this.mappedMode);
    }

    /** @param hintLabel the hint label, or null to draw {@code drawable} */
    protected void drawHintLabelOrIcon(Key key, String hintLabel, Drawable drawable, KeyHintPosition mode, Canvas canvas, Paint paint, KeyDrawParams source) {
        float hintX;
        float hintY;
        if (hintLabel == null && drawable == null) {
            if (BuildConfig.DEBUG) Log.w("KeyboardView", "Skipping key render: no label or icon (possible legacy theme reference)");
            return;
        }
        if (mode != KeyHintPosition.HIDDEN) {
            int keyWidth = key.getDrawWidth();
            int keyHeight = key.getHeight();
            Rect rect = this.hintBounds;
            rect.setEmpty();
            boolean hasLabel = !TextUtils.isEmpty(hintLabel);
            boolean hasIcon = drawable != null;
            paint.setColor(source.getHintLabelColor());
            if (hasLabel) {
                paint.setTypeface(source.typeface);
                paint.setTextSize(source.hintLetterSizePixels);
                paint.setTextAlign(Paint.Align.LEFT);
                paint.getTextBounds(hintLabel, 0, hintLabel.length(), rect);
            } else if (hasIcon) {
                // Every hint icon at the one hint size, whatever its intrinsic size.
                int hintSize = this.hintIconSize;
                int iconW = drawable.getIntrinsicWidth();
                int iconH = drawable.getIntrinsicHeight();
                if (iconW > 0 && iconH > 0 && Math.max(iconW, iconH) != hintSize) {
                    float scale = hintSize / (float) Math.max(iconW, iconH);
                    iconW = Math.round(iconW * scale);
                    iconH = Math.round(iconH * scale);
                }
                rect.set(0, 0, iconW, iconH);
            }
            // Pad corner hints by the cap insets (0 when flat) to keep them inside the cap.
            StyleSpec spec = KeyboardColorManager.styleSpec();
            boolean insetCaps = spec.getKeyCap() != KeyCap.FLAT;
            final float density = this.density;
            float capInsetH = spec.getKeyInsetHDp() * density;
            float capInsetV = spec.getKeyInsetVDp() * density;
            // Badge anchoring; clamped so partial redraws (clipped to key bounds) keep it.
            if (insetCaps && hasIcon && mode == KeyHintPosition.TOP_RIGHT_CORNER && anchorsHintToMainIcon()) {
                Drawable mainIcon = key.getIcon(this.keyboard.mIconsSet, 255);
                if (mainIcon != null && mainIcon.getIntrinsicWidth() > 0) {
                    int mainIconWidth = Math.min(mainIcon.getIntrinsicWidth(), keyWidth);
                    int mainIconHeight = mainIcon.getIntrinsicHeight();
                    float badgeX = (keyWidth / 2.0f) + (mainIconWidth / 2.0f) - (rect.width() * 0.3f);
                    float badgeY = (keyHeight / 2.0f) - (mainIconHeight / 2.0f) - (rect.height() * 0.3f);
                    hintX = Math.min(badgeX, keyWidth - rect.width() - 2);
                    hintY = Math.max(badgeY, 2);
                    canvas.translate(hintX, hintY);
                    KeyboardColorManager.INSTANCE.tint(drawable, source.getHintLabelColor());
                    drawable.setBounds(rect);
                    drawable.draw(canvas);
                    canvas.translate(-hintX, -hintY);
                    return;
                }
            }
            switch (mode) {
                case TOP_LEFT_CORNER:
                case BOTTOM_LEFT_CORNER:
                    hintX = this.hintLetterPadding + capInsetH;
                    break;
                case TOP_RIGHT_CORNER:
                case BOTTOM_RIGHT_CORNER:
                    hintX = (keyWidth - this.hintLetterPadding - capInsetH) - rect.width();
                    break;
                default:
                    hintX = 0.0f;
                    break;
            }
            switch (mode) {
                case TOP_LEFT_CORNER:
                case TOP_RIGHT_CORNER:
                    hintY = this.popupHintLetterPadding + capInsetV + (hasLabel ? rect.height() : 0);
                    break;
                case BOTTOM_LEFT_CORNER:
                case BOTTOM_RIGHT_CORNER:
                    hintY = (keyHeight - this.popupHintLetterPadding - capInsetV) - (hasIcon ? rect.height() : 0);
                    break;
                default:
                    hintY = 0.0f;
                    break;
            }
            canvas.translate(hintX, hintY);
            if (hasLabel) {
                paint.setColor(source.getHintLabelColor());
                canvas.drawText(hintLabel, 0.0f, 0.0f, paint);
            } else if (hasIcon) {
                KeyboardColorManager.INSTANCE.tint(drawable, source.getHintLabelColor());
                drawable.setBounds(rect);
                drawable.draw(canvas);
            }
            canvas.translate(-hintX, -hintY);
        }
    }

    /** @param keyDrawParams the key's draw params */
    protected void drawPopupHint(Key key, Canvas canvas, Paint paint, KeyDrawParams keyDrawParams) {
        drawHintLabelOrIcon(key, key.getLongPressLabel(), key.getIcon(this.keyboard.mIconsSet), key.getLongPressKeyHintPosition(), canvas, paint, keyDrawParams);
    }

    /** @param keyDrawParams the key's draw params */
    protected void drawPopupHintLetter(Key key, Canvas canvas, Paint paint, KeyDrawParams keyDrawParams) {
        if (TextUtils.isEmpty(this.popupHintLetter)) {
            return;
        }
        drawHintLabelOrIcon(key, this.popupHintLetter, null, KeyHintPosition.BOTTOM_RIGHT_CORNER, canvas, paint, keyDrawParams);
    }

    protected static void drawDrawable(Canvas canvas, Drawable drawable, int x, int y, int width, int height) {
        canvas.translate(x, y);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        canvas.translate(-x, -y);
    }

    public Paint createPaintForKey(Key key) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        if (key == null) {
            paint.setTypeface(this.textFormatter.typeface);
            paint.setTextSize(this.textFormatter.labelSizePixels);
        } else {
            paint.setColor(key.getLabelColor(this.textFormatter));
            paint.setTypeface(key.getTypeface(this.textFormatter));
            paint.setTextSize(key.getLabelSize(this.textFormatter));
        }
        return paint;
    }

    public void invalidateAllKeys() {
        this.invalidatedKeys.clear();
        this.needsFullRedraw = true;
        invalidate();
    }

    public void invalidateKey(Key key) {
        if (this.needsFullRedraw || key == null) {
            return;
        }
        this.invalidatedKeys.add(key);
        int keyX = key.getX() + getPaddingLeft();
        int keyY = key.getY() + getPaddingTop();
        invalidate(keyX, keyY, key.getWidth() + keyX, key.getHeight() + keyY);
    }

    /**
     * Repaints a key whose per-tap state ({@link #hasPerTapContent}) changed: only its layer when
     * key layers are in the display list, otherwise {@link #invalidateKey}.
     */
    protected void invalidateKeyState(Key key) {
        if (key == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !this.keyLayersInDisplayList) {
            invalidateKey(key);
            return;
        }
        if (this.needsFullRedraw) {
            // A full re-record is already on its way and brings every layer up to date.
            return;
        }
        if (!this.keyboardKeys.contains(key)) {
            // Not this keyboard's key (PointerTracker routes input-board keys through the main view).
            return;
        }
        if (!updateKeyLayer(key)) {
            // Out of layers: the new one has to be referenced from this view's display list.
            invalidate();
            return;
        }
        requestKeyLayerFrame();
    }

    /**
     * Gets changed key layers on screen: any frame that does not re-record this view will do.
     * This default does re-record; MainKeyboardView requests a damage-free frame instead.
     */
    protected void requestKeyLayerFrame() {
        invalidate();
    }

    /** Whether {@code key} shows something the at-rest keyboard does not (pressed, by default). */
    protected boolean hasPerTapContent(Key key) {
        return key.isPressed() && !suppressesPressedState();
    }

    /** True where pressed keys are never drawn pressed (an e-ink panel cannot show it in time). */
    protected boolean suppressesPressedState() {
        return false;
    }

    /** Whether a key is drawn in its pressed state in the pass being recorded. */
    protected final boolean isKeyDrawnPressed(Key key) {
        return !this.drawingRestingKeys && key.isPressed() && !suppressesPressedState();
    }

    /** True while recording the at-rest keyboard under the key layers: draw no per-tap content. */
    protected final boolean isDrawingRestingKeys() {
        return this.drawingRestingKeys;
    }

    /** Opt-in for the per-key layers; see {@link #keyLayers}. */
    protected boolean usesKeyLayers() {
        return false;
    }

    private boolean canUseKeyLayers() {
        if (!usesKeyLayers() || this.keyboard == null) {
            return false;
        }
        // A layer repaints the background under its key, which would double a translucent one.
        Drawable background = getBackground();
        return background != null && background.getOpacity() == PixelFormat.OPAQUE;
    }

    /** Paints {@code key} with the background and frets under it, clipped to its rectangle. */
    private void drawKeyRegion(Key key, Canvas canvas) {
        int left = key.getX() + getPaddingLeft();
        int top = key.getY() + getPaddingTop();
        canvas.save();
        canvas.clipRect(left, top, left + key.getWidth(), top + key.getHeight());
        Drawable background = getBackground();
        if (background != null) {
            background.draw(canvas);
        }
        if (drawsFrets() && KeyboardColorManager.styleSpec().getFretHeightDp() > 0f) {
            drawFrets(canvas);
        }
        drawKey(key, canvas, this.paint);
        canvas.restore();
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void drawKeyLayers(Canvas canvas) {
        KeyLayers layers = this.keyLayers;
        if (layers == null) {
            layers = new KeyLayers();
            this.keyLayers = layers;
        }
        // Bring every layer up to date: palette, keyboard or pressed keys may have changed.
        layers.beginSync();
        for (Key key : this.keyboard.getKeys()) {
            if (hasPerTapContent(key)) {
                recordKeyLayer(layers.acquire(key), key);
            }
        }
        layers.endSync();
        layers.drawAll(canvas);
    }

    /** @return false when the key needs a layer and every layer in the display list is taken */
    @RequiresApi(Build.VERSION_CODES.Q)
    private boolean updateKeyLayer(Key key) {
        KeyLayers layers = this.keyLayers;
        if (layers == null) {
            return false;
        }
        int index = layers.indexOf(key);
        if (!hasPerTapContent(key)) {
            if (index >= 0) {
                layers.release(index);
            }
            return true;
        }
        if (index < 0) {
            index = layers.acquireFree(key);
            if (index < 0) {
                return false;
            }
        }
        recordKeyLayer(index, key);
        return true;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void recordKeyLayer(int index, Key key) {
        int left = key.getX() + getPaddingLeft();
        int top = key.getY() + getPaddingTop();
        Canvas layerCanvas = this.keyLayers.beginRecording(index, left, top, key.getWidth(), key.getHeight());
        try {
            layerCanvas.translate(-left, -top);
            drawKeyRegion(key, layerCanvas);
        } finally {
            this.keyLayers.endRecording(index);
        }
    }

    private void releaseKeyLayers() {
        this.keyLayersInDisplayList = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && this.keyLayers != null) {
            this.keyLayers.releaseAll();
        }
    }

    /** Software rendering of what the layered hardware path composites, for pixel-identity tests. */
    @VisibleForTesting
    void drawLayeredForTest(Canvas canvas) {
        invalidateAllKeys();
        this.drawingRestingKeys = true;
        try {
            drawKeyboard(canvas);
        } finally {
            this.drawingRestingKeys = false;
        }
        for (Key key : this.keyboard.getKeys()) {
            if (hasPerTapContent(key)) {
                drawKeyRegion(key, canvas);
            }
        }
    }

    /** The software full redraw, for the same tests. */
    @VisibleForTesting
    void drawFullForTest(Canvas canvas) {
        invalidateAllKeys();
        drawKeyboard(canvas);
    }

    /**
     * The per-key RenderNodes and the key each shows (null when free). All RenderNode calls live
     * here so the view's own methods verify before API 29.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private static final class KeyLayers {

        /** Enough for two pointers, the shift keys they press, and the spacebar's word. */
        private static final int INITIAL_LAYERS = 4;

        private final ArrayList<RenderNode> nodes = new ArrayList<>();

        private final ArrayList<Key> keys = new ArrayList<>();

        private final IdentityHashMap<Key, Boolean> synced = new IdentityHashMap<>();

        KeyLayers() {
            for (int i = 0; i < INITIAL_LAYERS; i++) {
                addNode();
            }
        }

        private int addNode() {
            this.nodes.add(new RenderNode("KeyLayer"));
            this.keys.add(null);
            return this.nodes.size() - 1;
        }

        int indexOf(Key key) {
            for (int i = 0; i < this.keys.size(); i++) {
                if (this.keys.get(i) == key) {
                    return i;
                }
            }
            return -1;
        }

        /** A free layer for {@code key} without growing (growing needs a re-record), or -1. */
        int acquireFree(Key key) {
            int free = indexOf(null);
            if (free >= 0) {
                this.keys.set(free, key);
            }
            return free;
        }

        Canvas beginRecording(int index, int left, int top, int width, int height) {
            RenderNode node = this.nodes.get(index);
            node.setPosition(left, top, left + width, top + height);
            return node.beginRecording(width, height);
        }

        void endRecording(int index) {
            this.nodes.get(index).endRecording();
        }

        void drawAll(Canvas canvas) {
            for (int i = 0; i < this.nodes.size(); i++) {
                canvas.drawRenderNode(this.nodes.get(i));
            }
        }

        void beginSync() {
            this.synced.clear();
        }

        /** During a re-record: the key's layer, growing the pool if needed. */
        int acquire(Key key) {
            this.synced.put(key, Boolean.TRUE);
            int index = indexOf(key);
            if (index >= 0) {
                return index;
            }
            index = acquireFree(key);
            if (index >= 0) {
                return index;
            }
            index = addNode();
            this.keys.set(index, key);
            return index;
        }

        /** Frees every layer whose key was not acquired since {@link #beginSync}. */
        void endSync() {
            for (int i = 0; i < this.keys.size(); i++) {
                Key key = this.keys.get(i);
                if (key != null && !this.synced.containsKey(key)) {
                    release(i);
                }
            }
            this.synced.clear();
        }

        void release(int index) {
            this.keys.set(index, null);
            // Dropping the content damages the layer's rectangle, uncovering the at-rest key.
            this.nodes.get(index).discardDisplayList();
        }

        void releaseAll() {
            for (int i = 0; i < this.keys.size(); i++) {
                if (this.keys.get(i) != null || this.nodes.get(i).hasDisplayList()) {
                    release(i);
                }
            }
        }
    }

    @Override // android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyboardColorManager.INSTANCE.addObserver(colorObserver);
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        KeyboardColorManager.INSTANCE.removeObserver(colorObserver);
        super.onDetachedFromWindow();
        releaseBitmap();
        releaseKeyLayers();
    }

    // Custom symbol page preferences: each family reads its own keys, on purpose. The getIsPkb*
    // pair is not a copy of its twin: on a device with a physical keyboard only the "_pkb" keys
    // are written, and KeyboardState sizes the PKB page cycle from them (SymbolPageIndicatorTest).

    protected boolean getIsCustomSymbolPageEnabled() {
        return this.sharedPreferences.getBoolean("enable_symbol_customization_vkb", false);
    }

    protected boolean getIsPkbCustomSymbolPageEnabled() {
        return this.sharedPreferences.getBoolean("enable_symbol_customization_pkb", false);
    }

    protected boolean getIsCustomSymbolPageFirst() {
        return this.sharedPreferences.getBoolean("vkb_custom_page_first", false);
    }

    protected boolean getIsPkbCustomSymbolPageFirst() {
        return this.sharedPreferences.getBoolean("pkb_custom_page_first", false);
    }

    public void cleanup() {
        releaseBitmap();
        releaseKeyLayers();
    }

    public void setHintsEnabled(boolean enabled) {
        this.hintsEnabled = enabled;
    }

    public void setMappedMode(boolean enabled) {
        this.mappedMode = enabled;
    }

    private String getKeyIdentifier(Key key) {
        return key.getCode() == -4 ? key.getKeySpecOutputText() : Character.toString((char) key.getCode());
    }
}
