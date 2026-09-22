package dev.bbkb.ime.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.TypefaceUtils;
import dev.bbkb.ime.keyboard.internal.KeyVisualAttributes;
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.KeyDrawParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * Per-key draw state, rebuilt whenever the keyboard or the palette changes.
     *
     * <p>Both used to be recomputed inside {@code drawKey}/{@code drawKeyContent}: a
     * {@code Character.toString(code)} plus a HashMap lookup plus a formatter copy per key per
     * frame, and a regex {@code split()} per multiline-hint key per frame. The inputs - key
     * style data, key height, hint label - only change when the keyboard is swapped.
     */
    private final HashMap<Key, KeyDrawParams> keyFormatters = new HashMap<>();

    private final HashMap<Key, String[]> multilineHintLines = new HashMap<>();

    /** Keys of the current partial-redraw pass; see drawKeyboard. */
    private final ArrayList<Key> drawList = new ArrayList<>();

    private static final int[] EMPTY_ROW_EXTENTS = new int[0];

    /** Row tops/bottoms of the current keyboard, ascending - see drawFrets. */
    private int[] rowTops = EMPTY_ROW_EXTENTS;

    private int[] rowBottoms = EMPTY_ROW_EXTENTS;

    /** Display density, cached: it cannot change without the view being recreated. */
    private final float density;

    /** Reusable per-key background scratch state - see drawKeyBackground and its cap painters. */
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

    // Color observer for runtime theme changes
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
            // Key backgrounds are painted as flat fills from KeyboardColorManager (see
            // drawKeyBackground); the keyBackground/functionalKeyBackground/... theme
            // attributes and their shape drawables are gone.
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
        this.backgroundPaint.setStyle(Paint.Style.FILL);
        this.dividerPaint.setStyle(Paint.Style.FILL);
        this.capStrokePaint.setStyle(Paint.Style.STROKE);
        this.pageIndicatorActiveColor = ContextCompat.getColor(context, R.color.page_switcher_active_color);
        this.pageIndicatorInactiveColor = ContextCompat.getColor(context, R.color.page_switcher_inactive_color);
        this.context = context;
        this.sharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);

        // Set keyboard background programmatically from KeyboardColorManager
        // This establishes KeyboardColorManager as the single source of truth for colors
        updateKeyboardBackground();
    }

    public KeyVisualAttributes getKeyVisualAttribute() {
        return this.defaultKeyStyleData;
    }

    /**
     * Update keyboard background color from KeyboardColorManager.
     * Uses GradientDrawable instead of setBackgroundColor() to avoid overlay issues.
     * Reuses the GradientDrawable instance to avoid allocations.
     */
    private void updateKeyboardBackground() {
        setBackground(KeyboardColorManager.fill(KeyboardColorManager.INSTANCE.getBackgroundColor()));
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
        requestLayout();
        rebuildPerKeyDrawState();
    }

    /**
     * Rebuilds {@link #keyFormatters}, {@link #multilineHintLines} and the fret row extents for
     * the current keyboard. Must run after anything that changes the base formatter (a keyboard
     * swap, a palette change, {@link #applyDefaultKeyStyle}) - the cached formatters are copies.
     */
    private void rebuildPerKeyDrawState() {
        this.keyFormatters.clear();
        this.multilineHintLines.clear();
        this.drawList.clear();
        if (this.keyboard == null) {
            this.rowTops = EMPTY_ROW_EXTENTS;
            this.rowBottoms = EMPTY_ROW_EXTENTS;
            return;
        }
        // Style data is registered per key identifier, not per key instance: a key without its
        // own KeyVisualAttributes still picks up the style of a same-code key in the SAME keyboard. The map
        // is rebuilt per keyboard, so styles can no longer leak across layout switches.
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
            drawKeyboard(canvas);
            return;
        }
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
        // Deliberately NOT pooled across views: the bitmap is recycled in releaseBitmap(), and a
        // process-wide pool keyed only by pixel size hands the same buffer to two same-sized
        // views (aux strip / arrow bar), so one erases or recycles the other's backing store.
        // The size check above already covers the only case a pool could serve.
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
            // Membership is resolved ONCE: Keyboard.hasKey is a linear scan on a miss, and this
            // used to be called again for the same key in the draw loop below.
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
        // Frets repaint on EVERY pass, not just full redraws: a partial redraw's
        // clip is the invalidated keys' bounding Rect, which can span the row gap
        // between two invalidated keys (e.g. shift + a letter) — the background
        // repaint above would wipe that fret slice and only keys are redrawn after.
        // The canvas is already clipped, so this only touches affected pixels.
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
     * Whether the page-switch key ({@code -15}, KeyboardCodesSet {@code key_symbols_page_switch})
     * should show the symbol-page dot row instead of its own label - i.e. whether the element
     * currently set is one of the symbol pages the key cycles through.
     *
     * <p>DEFECT 23. {@link #maxVkbSymbolPage} / {@link #maxPkbSymbolPage} carry two meanings at
     * once, and this predicate used to conflate them. They are a DOT COUNT ({@code max - first + 1}),
     * so {@link #setKeyboard} bumps them by one when the custom symbol page is enabled - but the
     * custom page's element id is always symbolsCustom (8) / symbolsPkbCustom (108), never
     * "last base page + 1". Those two only coincide for Chinese, which has three base symbol
     * pages (5,6,7) so that the custom page really is 7+1; every other locale has two (5,6) and
     * the custom page sits a gap away at 8. Using the bumped bound as an element-id RANGE is what
     * produced both pinned failures:
     * <ul>
     *   <li>Chinese + custom pages: {@code maxPkbSymbolPage} is 108, so phonePkb (109) satisfied
     *       {@code elementId == maxPkbSymbolPage + 1} and the phone keyboard drew a dot row.
     *       (The VKB half of that clause carried a {@code !isChinese} guard; the PKB half did
     *       not.) The active index then landed past the last dot, so no dot was painted active.
     *   <li>Any other locale + custom pages: {@code maxVkbSymbolPage} is 7, so symbols2 (7) /
     *       symbols2Pkb (107) fell INSIDE the range even though those elements are Chinese-only.
     *       With custom-page-first they mapped to index 3 of 3 dots - again no active dot.
     * </ul>
     *
     * <p>So strip the custom-page bump back off before using the bound as a range, and admit the
     * custom page by its own element id. Both cases fall out; see {@code SymbolPageIndicatorTest}.
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
        // The custom page is not contiguous with the base pages outside Chinese, so it is matched
        // by id rather than by range.
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
     * Which dot is painted active, as an index into the dot row {@link #drawPageIndicator} lays
     * out. Both families are a plain offset from their first symbol page - symbols0 (5) /
     * symbols0Pkb (105) - then shifted by {@link #adjustPageIndexForCustomPage} to account for
     * where the custom page sits in the cycle.
     *
     * <p>DEFECT 23. This used to take a second {@code basePageIndex} parameter and, on the PKB
     * side, choose between {@code elementId - 105} and {@code elementId - basePageIndex} on
     * {@code elementId >= basePageIndex}. Its only caller passed {@code elementId - 105}, so the
     * condition read {@code elementId >= elementId - 105}: always true, second arm unreachable,
     * and the parameter unused on the VKB side as well. Both are gone; every element still gets
     * the value the live arm gave it.
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
     * Shifts a dot index to account for where the custom page sits in the cycle. Called only when
     * the caller's own family has custom pages enabled.
     *
     * <p>TRAP: every clause below tests {@code basePageIndex} against BOTH families' bounds, even
     * though {@code basePageIndex} belongs to exactly one of them. That was harmless while
     * {@link #maxVkbSymbolPage} and {@link #maxPkbSymbolPage} were bumped by the same preference;
     * since DEFECT 24 gave each family its own preference the two bounds can differ, so the
     * other family's bound is a live foreign value here. It stays benign only because the index
     * it would wrongly match always corresponds to an element id that this family cannot reach in
     * this locale - symbols2 (7) / symbols2Pkb (107) exist for Chinese only, and Chinese takes the
     * {@code isRightToLeft} arm before the foreign bound is consulted. Do not widen the element
     * range without collapsing this onto a single per-family bound; Wave 3's PKB/VKB collapse is
     * where that belongs.
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

    /** KeyboardCodesSet "key_enter" — the enter/action key ('\n'). */
    private static final int CODE_ENTER = 10;

    /**
     * Medium-weight (500) key labels where the platform supports typeface weights
     * (P+), bold below that. Applied when the active {@link StyleSpec} sets
     * mediumKeyTypeface; also used by KeyPreviewView and FlickSuggestionView so the
     * preview bubble and on-key predictions match the key face.
     */
    public static Typeface mediumWeightTypeface(Typeface base) {
        Typeface resolved = base != null ? base : Typeface.DEFAULT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return Typeface.create(resolved, 500, false);
        }
        return Typeface.create(resolved, Typeface.BOLD);
    }

    /** The key-surface color for a key's backgroundType (shared by both render paths). */
    private static int keyBackgroundColorFor(Key key) {
        switch (key.backgroundType) {
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

        // Get the appropriate background color based on key type
        int backgroundColor = keyBackgroundColorFor(key);

        // backgroundType 0 ("empty") paints no key surface at all — the container's own
        // background shows through. That is what lets the arrow bar match the flat
        // suggestion strip it replaces (its container is painted backgroundColor by
        // updateKeyboardBackground). Emoji recents also use "empty"; their viewPager is
        // painted keyColor, which is exactly what they were being filled with before, so
        // they are unaffected.
        if (key.backgroundType != 0) {
            // Draw base background programmatically
            this.backgroundPaint.setColor(backgroundColor);

            // Draw rectangle for all keys (no rounded corners)
            canvas.drawRect(0, 0, keyWidth, keyHeight, this.backgroundPaint);
        }

        // Draw pressed state overlay if key is pressed
        if (key.isPressed()) {
            this.backgroundPaint.setColor(KeyboardColorManager.INSTANCE.getKeyColorPressed());

            // Draw pressed overlay
            canvas.drawRect(0, 0, keyWidth, keyHeight, this.backgroundPaint);
        }

        // Draw vertical divider lines on left and right edges of normal keys and emoji keys
        // backgroundType: 1=normal, 2=functional, 3=stickyOff, 4=stickyOn, 5=action, 6=spacebar, 0=default (emoji)
        if (key.backgroundType == 1 || key.backgroundType == 0) { // normal keys and emoji keys
            final float density = this.density;
            final Paint dividerPaint = this.dividerPaint;
            dividerPaint.setColor(KeyboardColorManager.INSTANCE.getBackgroundColor());
            float dividerWidth = 0.5f * density; // 0.5dp
            float topPadding = keyHeight * 0.08f; // 8% padding top
            float bottomPadding = keyHeight * 0.05f; // 5% padding bottom
            // Left edge divider
            canvas.drawRect(0, topPadding, dividerWidth, keyHeight - bottomPadding, dividerPaint);
            // Right edge divider
            canvas.drawRect(keyWidth - dividerWidth, topPadding, keyWidth, keyHeight - bottomPadding, dividerPaint);
        }
    }

    /**
     * Whether the cap painters draw caps for backgroundType 0 ("empty") keys.
     * Default false (empty keys stay surfaceless — aux bars, arrow bar); the emoji
     * pages override to true so each emoji floats on its own cap.
     */
    protected boolean capPainterDrawsEmptyKeys() {
        return false;
    }

    /**
     * Inset-cap key rendering (introduced as K1-K4 of the 2026-08 UI audit): floating
     * rounded caps with gaps instead of full-bleed rects with hairline dividers, a
     * translucent state layer instead of the opaque pressed fill, and — when the spec
     * sets accentEnterKey — the enter/action key as an accent-filled pill. Geometry
     * comes from the spec. backgroundType 0 ("empty") paints no key surface (unless
     * [capPainterDrawsEmptyKeys]), but a pressed empty key still gets the state
     * layer so presses stay visible.
     */
    /**
     * The visible cap rectangle of {@code key} in key-local coordinates, filled into the shared
     * {@link #capRect}. Single derivation for both cap painters (and the geometry the fret
     * placement and the corner-hint padding re-derive from the same spec insets).
     *
     * @return false when the cap is degenerate and nothing should be drawn
     */
    private boolean fillCapRect(Key key, StyleSpec spec) {
        float insetH = spec.getKeyInsetHDp() * this.density;
        float insetV = spec.getKeyInsetVDp() * this.density;
        this.capRect.set(insetH, insetV, key.getDrawWidth() - insetH, key.getHeight() - insetV);
        return !this.capRect.isEmpty();
    }

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
        if (key.isPressed()) {
            paint.setColor(KeyboardColorManager.INSTANCE.getPressedStateLayer());
            canvas.drawRoundRect(cap, radius, radius, paint);
        }
    }

    /**
     * Whether this view draws the style's fret bars ([StyleSpec.fretHeightDp]).
     * Default false; the main board opts in. Popups, aux bars, and emoji pages
     * stay fretless — a fret across a one-row popup or a flat aux surface reads
     * as clutter, not structure.
     */
    protected boolean drawsFrets() {
        return false;
    }

    /**
     * Fret finish, relative to the palette's fret color: crisp ~1dp specular rims
     * on the top and bottom edges over a light-to-dark body gradient — the 3D
     * metal-bar look.
     */
    private static final float FRET_RIM_TOP_LIGHTEN = 0.55f;
    private static final float FRET_RIM_BOTTOM_LIGHTEN = 0.40f;
    private static final float FRET_BODY_TOP_LIGHTEN = 0.30f;
    private static final float FRET_BODY_BOTTOM_LIGHTEN = 0.08f;
    private static final float FRET_RIM_DP = 1f;

    /**
     * Fret fill paints keyed by (color, height) — the top fret can be shorter than
     * the interior ones, so a single slot would thrash. Shaders live in fret-local
     * coordinates. Cleared on palette change.
     */
    private final android.util.LongSparseArray<Paint> fretFillCache = new android.util.LongSparseArray<>();

    /**
     * (color, size) cache key. The size half is the raw float bits, not a truncating cast: two
     * heights that differ only fractionally must not share a gradient built for the other span.
     */
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
     * BB10 frets: full-width horizontal metal bars between key rows, plus one
     * hugging the board's top edge. Each interior fret is centered in the VISIBLE
     * band between the cap above and the cap below (computed from the actual key
     * rects plus the symmetric cap insets — NOT from mVerticalGap, whose size the
     * fret can exceed), and its height is clamped so a dark seam always survives
     * on both sides: caps can never overlap a fret. The top fret anchors its
     * bottom edge one seam above the first row's caps and clips at the view top.
     * Drawn after the background and before the keys; the shader lives in
     * fret-local coordinates (canvas translated per bar) so one paint serves all.
     */
    private void drawFrets(Canvas canvas) {
        StyleSpec spec = KeyboardColorManager.styleSpec();
        final float density = this.density;
        float fretH = spec.getFretHeightDp() * density;
        float insetV = spec.getKeyInsetVDp() * density;
        // Row extents are fixed for the life of the Keyboard; they are computed once per
        // keyboard in rebuildPerKeyDrawState instead of rebuilt on every draw pass.
        final int[] rowTops = this.rowTops;
        final int[] rowBottoms = this.rowBottoms;
        if (rowTops.length == 0) {
            return;
        }
        int width = getWidth();
        int paddingTop = getPaddingTop();
        float minSeam = 0.5f * density;

        // Uniform placement: every fret — top and interior alike — is FULL height
        // and bottom-anchored the same seam S above the caps of the row below it,
        // with whatever slack remains sitting above the bar. S is the symmetric
        // interior seam, capped by what the top fret can achieve without clipping
        // at the view top (bottom-anchoring to a seam the top fret can't reach
        // would leave it visibly lower than the rest — the mismatch this fixes).
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
            // Degenerate bands only: keep full height, never overlap the caps
            // above; the below-seam absorbs the shortfall.
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

    /**
     * Sculpted cap fill paints, keyed by (base color, cap height). The gradient
     * shader is what makes a per-key-per-frame Paint too expensive on the
     * software/bitmap render path, and caps only vary by key color and row height,
     * so the cache stays tiny. Cleared on palette change.
     */
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
     * BB10-style sculpted caps: the same inset-cap geometry as
     * [drawCapKeyBackground], finished with a top-lit vertical gradient and a thin
     * top inner highlight — both derived from the key's base color, so functional
     * keys (keyAlt) and the pressed state sculpt consistently. No border stroke:
     * key separation is the board background showing through the gaps, as on the
     * original. Pressed keys re-derive from keyPressed (the BB10 opaque blue
     * press) instead of overlaying a translucent state layer.
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
            if (key.isPressed()) {
                final Paint pressed = this.backgroundPaint;
                pressed.setColor(KeyboardColorManager.INSTANCE.getPressedStateLayer());
                canvas.drawRoundRect(cap, radius, radius, pressed);
            }
            return;
        }
        int base = key.isPressed()
                ? KeyboardColorManager.INSTANCE.getKeyColorPressed()
                : keyBackgroundColorFor(key);
        canvas.drawRoundRect(cap, radius, radius, sculptedFillPaint(base, cap));

        float strokeWidth = density; // 1dp
        final Paint stroke = this.capStrokePaint;
        stroke.setStrokeWidth(strokeWidth);

        // Top inner highlight: the cap outline hugging the cap edge, clipped to the
        // top so only the lit rim (and its corners) shows.
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

    /**
     * @param suppressLabel do not paint the key's label or its icon - something has already been
     *     painted over the key face (MainKeyboardView paints the autocorrect word on the
     *     spacebar). Named for that role: it is NOT "this is a key preview".
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
        String labelText = key.getLabel();
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
                paint.setColor(key.getLabelColor(formatter));
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
                    // NinePatch drawables stretch to fill - use 90% of key width
                    iconWidth = (int) (keyWidthFloat * this.spacebarIconWidth);
                } else {
                    // For vector/layer-list drawables, use 35% of key width
                    iconWidth = (int) (keyWidthFloat * 0.35f);
                }
            } else {
                iconWidth = Math.min(iconDrawable.getIntrinsicWidth(), keyWidth);
            }
            int intrinsicHeight = iconDrawable.getIntrinsicHeight();
            // For spacebar, use 6dp height; otherwise use intrinsic height
            int iconHeight;
            if (key.getCode() == 32) {
                iconHeight = (int) (6 * this.density); // 6dp
            } else {
                iconHeight = intrinsicHeight;
            }
            int iconY = key.isAlignIconToBottom() ? keyHeight - iconHeight : (keyHeight - iconHeight) / 2;
            // Offset spacebar icon 6dp lower
            if (key.getCode() == 32) {
                iconY += (int) (8 * this.density);
            }
            int iconX = (keyWidth - iconWidth) / 2;
            if (!suppressLabel && !shouldSkipSpacebarIcon(key)) {
                drawDrawable(canvas, iconDrawable, iconX, iconY, iconWidth, iconHeight);
            }
            
            // Shift indicator bar is drawn by MainKeyboardView using actual shift state flag
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
     * Cap-styled spacebars are plain — the legacy bar icon reads as clutter between
     * rounded caps (Gboard draws none). A normal-width space key that needs the glyph
     * to be recognizable as space (the number pad's utility column) overrides this.
     */
    protected boolean shouldSkipSpacebarIcon(Key key) {
        return key.getCode() == 32 && !KeyboardColorManager.styleSpec().getDrawSpacebarIcon();
    }

    protected void applyIconTint(Key key, Drawable iconDrawable) {
        if (KeyboardColorManager.styleSpec().getAccentEnterKey() && key.getCode() == CODE_ENTER) {
            // Icon on the accent-filled action key (K4): keyboard background color
            // for contrast against the accent, matching the M3 action-key idiom.
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

    protected void drawHintLabelOrIcon(Key key, String str, Drawable drawable, KeyHintPosition mode, Canvas canvas, Paint paint, KeyDrawParams source) {
        float hintX;
        float hintY;
        if (str == null && drawable == null) {
            // Skip rendering keys with no label or icon (can happen with legacy theme references)
            if (BuildConfig.DEBUG) Log.w("KeyboardView", "Skipping key render: no label or icon (possible legacy theme reference)");
            return;
        }
        if (mode != KeyHintPosition.HIDDEN) {
            int keyWidth = key.getDrawWidth();
            int keyHeight = key.getHeight();
            Rect rect = new Rect();
            boolean hasLabel = !TextUtils.isEmpty(str);
            boolean hasIcon = drawable != null;
            paint.setColor(source.getHintLabelColor());
            if (hasLabel) {
                paint.setTypeface(source.typeface);
                paint.setTextSize(source.hintLetterSizePixels);
                paint.setTextAlign(Paint.Align.LEFT);
                paint.getTextBounds(str, 0, str.length(), rect);
            } else if (hasIcon) {
                rect.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            }
            // Corner hints are positioned from the key bounds; on cap styles the
            // visible cap is inset from those bounds, so pad by the cap insets (0 for
            // full-bleed styles) to keep the hint inside the cap instead of on (or
            // past) its edge.
            StyleSpec spec = KeyboardColorManager.styleSpec();
            boolean insetCaps = spec.getKeyCap() != KeyCap.FLAT;
            final float density = this.density;
            float capInsetH = spec.getKeyInsetHDp() * density;
            float capInsetV = spec.getKeyInsetVDp() * density;
            // Cap styles: an icon hint on an icon-content key (e.g. the settings gear
            // on the UIM show-keyboard key) anchors to the key's main icon as a badge.
            // On wide icon-row keys the cell corner floats far outside the perceived
            // button. Clamped so partial redraws (which clip to key bounds) keep it.
            if (insetCaps && hasIcon && mode == KeyHintPosition.TOP_RIGHT_CORNER) {
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
                canvas.drawText(str, 0.0f, 0.0f, paint);
            } else if (hasIcon) {
                KeyboardColorManager.INSTANCE.tint(drawable, source.getHintLabelColor());
                drawable.setBounds(rect);
                drawable.draw(canvas);
            }
            canvas.translate(-hintX, -hintY);
        }
    }

    protected void drawPopupHint(Key key, Canvas canvas, Paint paint, KeyDrawParams c1061t) {
        drawHintLabelOrIcon(key, key.getLongPressLabel(), key.getIcon(this.keyboard.mIconsSet), key.getLongPressKeyHintPosition(), canvas, paint, c1061t);
    }

    protected void drawPopupHintLetter(Key key, Canvas canvas, Paint paint, KeyDrawParams c1061t) {
        if (TextUtils.isEmpty(this.popupHintLetter)) {
            return;
        }
        drawHintLabelOrIcon(key, this.popupHintLetter, null, KeyHintPosition.BOTTOM_RIGHT_CORNER, canvas, paint, c1061t);
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

    @Override // android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Register for color change notifications
        KeyboardColorManager.INSTANCE.addObserver(colorObserver);
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        // Unregister from color change notifications
        KeyboardColorManager.INSTANCE.removeObserver(colorObserver);
        super.onDetachedFromWindow();
        releaseBitmap();
    }

    // ---------------------------------------------------------------------------------------
    // Custom-symbol-page preferences. THE TWO FAMILIES READ DIFFERENT KEYS, ON PURPOSE.
    //
    // The on-screen keyboard and the physical keyboard each have their own custom-symbol-page
    // setting, stored under its own pair of preference keys:
    //     VKB: "enable_symbol_customization_vkb" / "vkb_custom_page_first"
    //     PKB: "enable_symbol_customization_pkb" / "pkb_custom_page_first"
    // The getIsPkb* pair below is therefore NOT a redundant copy of its non-Pkb twin. Where each
    // key is written and read:
    //   - SymbolCustomizationScreen writes them, and on a device WITH a physical keyboard it
    //     writes ONLY the "_pkb" pair; the "_vkb" keys are never written on such a device.
    //   - KeyboardSwitcher.isPkbSymbolCustomizationEnabled() / isPkbCustomPageFirst() and
    //     KeyboardSwitcher.setPkbSymbolsKeyboard() read the "_pkb" keys, and KeyboardState sizes
    //     and orders the PKB symbol-page cycle from them.
    //
    // DEFECT 24. Until Wave 2.5 the getIsPkb* pair read the "_vkb" keys - aliases of their twins.
    // On a KEY2 that meant this view's dot row was derived from a preference nothing on the device
    // ever wrote, so it disagreed with the very page cycle it annotates: with the custom symbol
    // page enabled, KeyboardState cycles three PKB pages (105, 106, 108) while the dot row drew
    // two dots and symbolsPkbCustom (108) drew no indicator at all. Aligning the accessors on the
    // "_pkb" keys was chosen over the alternative - retiring the "_pkb" setting in favour of one
    // shared preference - because the "_pkb" keys are live on-disk settings on every physical-
    // keyboard device, and dropping them would silently discard what users have already toggled.
    // Pinned by SymbolPageIndicatorTest, which asserts each accessor reads its OWN family's key
    // and is not moved by the other family's; that is what stops the alias coming back.
    // ---------------------------------------------------------------------------------------

    protected boolean getIsCustomSymbolPageEnabled() {
        return this.sharedPreferences.getBoolean("enable_symbol_customization_vkb", false);
    }

    /** The physical keyboard's own setting - see the block comment above. Not an alias. */
    protected boolean getIsPkbCustomSymbolPageEnabled() {
        return this.sharedPreferences.getBoolean("enable_symbol_customization_pkb", false);
    }

    protected boolean getIsCustomSymbolPageFirst() {
        return this.sharedPreferences.getBoolean("vkb_custom_page_first", false);
    }

    /** The physical keyboard's own setting - see the block comment above. Not an alias. */
    protected boolean getIsPkbCustomSymbolPageFirst() {
        return this.sharedPreferences.getBoolean("pkb_custom_page_first", false);
    }

    public void cleanup() {
        releaseBitmap();
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
