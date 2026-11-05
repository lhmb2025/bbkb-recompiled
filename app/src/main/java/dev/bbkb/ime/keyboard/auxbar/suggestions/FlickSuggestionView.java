package dev.bbkb.ime.keyboard.auxbar.suggestions;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.MainKeyboardView;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.engine.EngineOps;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.GeometryUtils;
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.BuildConfig;

import java.util.ArrayList;


public final class FlickSuggestionView extends RelativeLayout {

    private static final String TAG = "FlickSuggestionView";

    /** How long drawing waits for the engine's Hangul decode before drawing the raw text. */
    private static final long FLICK_DECODE_TIMEOUT_MS = 50L;

    private float mHorizontalWordDistance;

    private float mVerticalWordDistance;

    private float mMinHorizontalWordDistance;

    private float mMinVerticalWordDistance;

    private int mSuggestedTextColor;

    private int mTypedTextColor;

    private final int mShadowColor;

    private final float mLetterSize;

    private final float mHorizontalWordDistanceFactor;

    private final float mVerticalWordDistanceFactor;

    private final float mHorizontalOffsetFactor;

    private final float mTextVerticalPaddingFactor;

    private final float mShadowRadius;

    private ForegroundColorSpan mSuggestedColorSpan;

    /** De-emphasized variants for non-default candidates (see [deEmphasized]). */
    private ForegroundColorSpan mSuggestedColorSpanDim;

    private ForegroundColorSpan mTypedColorSpanDim;

    private ForegroundColorSpan mTypedColorSpan;

    private ArrayList<PredictionView> mPredictionViews;

    private int mVisibleCount;

    private boolean mIsRtl;

    private MainKeyboardView mKeyboardView;

    private SuggestedWords mSuggestedWords;

    private long mLastSelectionTime;

    private FlickSuggestionAnimationView mAnimationView;

    private Listener mListener;

    /**
     * What the last full render drew from, so an identical update can return before clear()
     * tears it down. Valid only while nothing has hidden or re-laid-out the predictions since
     * ({@link #hide}, {@link #updateKeyMetrics} and a flick pick drop it).
     */
    private boolean mShownValid;
    private SuggestedWords mShownWords;
    private Keyboard mShownKeyboard;
    private MainKeyboardView mShownKeyboardView;
    private boolean mShownRtl;
    private int mShownVisibility;
    private boolean mShownSupported;
    private boolean mShownOnKey;
    private boolean mShownEmojiBoard;
    private boolean mShownGesture;
    private boolean mShownEmojiPredictions;
    private Object mShownStyleSpec;
    private final int[] mShownKeyboardLocation = new int[2];

    /** While true, {@link #hide} leaves the spacebar word for the render in progress to set. */
    private boolean mSpacebarTextDeferred;

    
    public interface Listener {
        void onSuggestionPicked(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, InputSource enumC0690f);
    }

    public FlickSuggestionView(Context context, AttributeSet attributeSet) {
        super(new ContextThemeWrapper(context, R.style.Theme_BlackberryKeyboard_IME), attributeSet);
        this.mHorizontalWordDistance = 0.0f;
        this.mVerticalWordDistance = 0.0f;
        this.mMinHorizontalWordDistance = 0.0f;
        this.mMinVerticalWordDistance = 0.0f;
        this.mPredictionViews = new ArrayList<>();
        this.mVisibleCount = 0;
        this.mSuggestedWords = SuggestedWords.EMPTY;
        Context themedContext = getContext();
        Resources resources = themedContext.getResources();
        
        // Use KeyboardColorManager iconColor for flick suggestions (lighter than textColor)
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            int iconColor = KeyboardColorManager.INSTANCE.getIconColor();
            this.mSuggestedTextColor = iconColor;  // iconColor for suggested part
            this.mTypedTextColor = iconColor;  // iconColor for typed part
        } else {
            // Fallback to theme colors if color manager not initialized
            TypedValue typedValue = new TypedValue();
            themedContext.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
            this.mSuggestedTextColor = typedValue.data;
            this.mTypedTextColor = this.mSuggestedTextColor;
        }
        this.mShadowColor = ContextCompat.getColor(themedContext, R.color.flick_suggestion_shadow_color);
        this.mLetterSize = resources.getDimension(R.dimen.config_flick_prediction_letter_size);
        this.mHorizontalWordDistanceFactor = resources.getFraction(R.fraction.config_flick_prediction_minimum_horizontal_word_distance_factor, 1, 1);
        this.mVerticalWordDistanceFactor = resources.getFraction(R.fraction.config_flick_prediction_minimum_vertical_word_distance_factor, 1, 1);
        this.mHorizontalOffsetFactor = resources.getFraction(R.fraction.config_flick_prediction_horizontal_offset_factor, 1, 1);
        this.mTextVerticalPaddingFactor = resources.getFraction(R.fraction.config_flick_prediction_text_vertical_padding_factor, 1, 1);
        this.mShadowRadius = resources.getDimension(R.dimen.config_flick_prediction_shadow_radius);
        this.mMinHorizontalWordDistance = resources.getDimension(R.dimen.config_flick_prediction_minimum_horizontal_word_distance);
        this.mMinVerticalWordDistance = resources.getDimension(R.dimen.config_flick_prediction_minimum_vertical_word_distance);
        setTextColors(this.mSuggestedTextColor, this.mTypedTextColor);
        for (int i = 0; i < 7; i++) {
            PredictionView c0826b = new PredictionView(themedContext);
            this.mPredictionViews.add(c0826b);
            addView(c0826b);
        }
        this.mAnimationView = new FlickSuggestionAnimationView(themedContext, attributeSet);
    }

    private void setTextColors(int suggested, int typed) {
        this.mSuggestedTextColor = suggested;
        this.mTypedTextColor = typed;
        this.mSuggestedColorSpan = new ForegroundColorSpan(suggested);
        this.mTypedColorSpan = new ForegroundColorSpan(typed);
        this.mSuggestedColorSpanDim = new ForegroundColorSpan(dimColor(suggested));
        this.mTypedColorSpanDim = new ForegroundColorSpan(dimColor(typed));
    }

    /**
     * Follows the palette's icon color. These colors used to be read once in the constructor,
     * which was enough while every theme change re-inflated the input view; a system dark/light
     * switch no longer does (method.xml declares uiMode), so they are checked per render.
     *
     * @return whether the colors changed (what is drawn is then stale)
     */
    private boolean refreshTextColors() {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return false;
        }
        int iconColor = KeyboardColorManager.INSTANCE.getIconColor();
        if (iconColor == this.mSuggestedTextColor && iconColor == this.mTypedTextColor) {
            return false;
        }
        setTextColors(iconColor, iconColor);
        return true;
    }

    public void init(Listener interfaceC0825a, MainKeyboardView mainKeyboardView) {
        this.mListener = interfaceC0825a;
        this.mKeyboardView = mainKeyboardView;
    }

    private Keyboard getKeyboard() {
        return KeyboardSwitcher.getInstance().getMainKeyboardView().getKeyboard();
    }

    private boolean isAlphabetKeyboard() {
        Keyboard keyboard = getKeyboard();
        return keyboard != null && keyboard.mId.isAlphabetKeyboard();
    }

    private boolean isSymbolsKeyboard() {
        Keyboard keyboard = getKeyboard();
        return keyboard != null && keyboard.mId.isSymbolsKeyboard();
    }

    private boolean isPkbKeyboard() {
        Keyboard keyboard = getKeyboard();
        return keyboard != null && keyboard.mId.isPkbKeyboard();
    }

    private boolean isSupportedKeyboard() {
        return isAlphabetKeyboard() || isSymbolsKeyboard() || (DeviceProfile.current().isVkbDevice() && DeviceProfile.isPhysicalKeyboardAvailable(getContext()) && isPkbKeyboard());
    }

    private boolean isEmojiPredictionsEnabled() {
        return SettingsManager.getInstance().getSettingsValues().isEmojiPredictionsEnabled;
    }

    private boolean isEmojiKeyboardShowing() {
        return KeyboardSwitcher.getInstance().isEmojiKeyboardShowing();
    }

    private boolean isOnKeyPredictionEnabled() {
        SettingsValues settingsValues = SettingsManager.getInstance().getSettingsValues();
        return settingsValues.isOnKeyPredictionsEnabled && settingsValues.isPredictionsEnabled;
    }

    private boolean isGestureInputEnabled() {
        return SettingsManager.getInstance().getSettingsValues().isVkbGestureInputEnabledForLocale();
    }

    public void clearSuggestions(String str) {
        setSuggestions(SuggestedWords.EMPTY, this.mIsRtl);
    }

    public void updateKeyMetrics(boolean z) {
        Keyboard keyboard;
        this.mHorizontalWordDistance = this.mMinHorizontalWordDistance;
        this.mVerticalWordDistance = this.mMinVerticalWordDistance;
        if (this.mAnimationView.isRunning()) {
            this.mAnimationView.stop();
        }
        if (isSupportedKeyboard() && (keyboard = getKeyboard()) != null) {
            Key key = keyboard.getKeys().get(1);
            this.mHorizontalWordDistance = key.getHeight() * this.mHorizontalWordDistanceFactor;
            this.mVerticalWordDistance = key.getHeight() * this.mVerticalWordDistanceFactor;
        }
        if (z) {
            return;
        }
        // The metrics changed: lay the same words out again.
        this.mShownValid = false;
        setSuggestions(this.mSuggestedWords, this.mIsRtl);
    }

    public void clear() {
        hide();
        this.mSuggestedWords = SuggestedWords.EMPTY;
    }

    public void hide() {
        this.mShownValid = false;
        if (this.mKeyboardView == null) {
            return;
        }
        // INVISIBLE, not GONE: the next update shows them again, and a GONE->VISIBLE flip of
        // the overlay and its predictions requests a layout of the whole keyboard frame where
        // INVISIBLE->VISIBLE only redraws. Neither the overlay (match_parent, not clickable)
        // nor a prediction draws or takes touches while INVISIBLE.
        setVisibility(View.INVISIBLE);
        for (int i = 0; i < this.mVisibleCount; i++) {
            this.mPredictionViews.get(i).setVisibility(View.INVISIBLE);
        }
        this.mVisibleCount = 0;
        if (!this.mSpacebarTextDeferred) {
            this.mKeyboardView.setAutoCorrectionText("");
        }
    }

    /**
     * The suggestion presenter's entry point. It used to clear() a visible overlay and then call
     * {@link #setSuggestions}, which clears again; that pre-clear flipped the container and every
     * prediction GONE and set the spacebar word to "" on each update, so an unchanged result
     * still relaid out the keyboard frame and re-recorded the keyboard. The pre-clear is kept
     * only where setSuggestions would not clear (the keyboard view slid sideways).
     */
    public void showSuggestions(SuggestedWords words, boolean rtl) {
        MainKeyboardView mainKeyboardView = this.mKeyboardView;
        if (mainKeyboardView == null || mainKeyboardView.getX() != 0.0f) {
            if (getVisibility() == View.VISIBLE) {
                clear();
            }
            return;
        }
        setSuggestions(words, rtl);
    }

    public void setSuggestions(SuggestedWords c0666ac, boolean z) {
        MainKeyboardView mainKeyboardView = this.mKeyboardView;
        if (mainKeyboardView != null && mainKeyboardView.getX() == 0.0f) {
            boolean colorsChanged = refreshTextColors();
            if (!colorsChanged && c0666ac != null && c0666ac.size() > 0 && isShowing(c0666ac, z)) {
                // Same words on the same keyboard: what is drawn is already right. Keep the new
                // object so a flick picks the newest word info.
                this.mSuggestedWords = c0666ac;
                this.mShownWords = c0666ac;
                return;
            }
            // hide() would set the spacebar word to "" and the render below set it again in the
            // same pass, which defeats MainKeyboardView's equality guard and re-records the
            // keyboard. Set it once, to its final value.
            this.mSpacebarTextDeferred = true;
            try {
                clear();
                this.mIsRtl = z;
                Keyboard keyboard = this.mKeyboardView.getKeyboard();
                if (keyboard == null) {
                    return;
                }
                if (c0666ac == null || c0666ac.size() > 0) {
                    this.mSuggestedWords = c0666ac;
                    boolean isVkb = isSupportedKeyboard();
                    boolean onKeyEnabled = isOnKeyPredictionEnabled();
                    boolean notShowingEmoji = !isEmojiKeyboardShowing();
                    if (isVkb && onKeyEnabled && notShowingEmoji) {
                        showOnKeyPredictions(this.mSuggestedWords, keyboard);
                    } else {
                        if (isEmojiKeyboardShowing()) {
                            return;
                        }
                        showAutoCorrectPrediction(this.mSuggestedWords, keyboard);
                    }
                    if (c0666ac != null) {
                        rememberShown(c0666ac, keyboard, z);
                    }
                }
            } finally {
                if (this.mSpacebarTextDeferred) {
                    // Nothing on the render path set it: it ends up empty, as hide() left it.
                    this.mSpacebarTextDeferred = false;
                    this.mKeyboardView.setAutoCorrectionText("");
                }
            }
        }
    }

    /** The spacebar word, set once per render (see {@link #mSpacebarTextDeferred}). */
    private void setSpacebarText(String text) {
        this.mSpacebarTextDeferred = false;
        this.mKeyboardView.setAutoCorrectionText(text);
    }

    /**
     * Whether {@code words} on the current keyboard would draw exactly what the last render left
     * on screen: the same words and flags, keyboard, view position, direction and every setting
     * the render reads, and nothing hid or moved the predictions since.
     */
    private boolean isShowing(SuggestedWords words, boolean rtl) {
        if (!this.mShownValid) {
            return false;
        }
        MainKeyboardView view = this.mKeyboardView;
        if (view != this.mShownKeyboardView || view.getKeyboard() != this.mShownKeyboard
                || rtl != this.mShownRtl || getVisibility() != this.mShownVisibility) {
            return false;
        }
        if (isSupportedKeyboard() != this.mShownSupported
                || isOnKeyPredictionEnabled() != this.mShownOnKey
                || isEmojiKeyboardShowing() != this.mShownEmojiBoard
                || isGestureInputEnabled() != this.mShownGesture
                || isEmojiPredictionsEnabled() != this.mShownEmojiPredictions
                || KeyboardColorManager.styleSpec() != this.mShownStyleSpec) {
            return false;
        }
        int[] location = keyboardLocationInWindow();
        if (location[0] != this.mShownKeyboardLocation[0] || location[1] != this.mShownKeyboardLocation[1]) {
            return false;
        }
        return dev.bbkb.ime.keyboard.auxbar.UnifiedSuggestionView.rendersSame(this.mShownWords, words);
    }

    private void rememberShown(SuggestedWords words, Keyboard keyboard, boolean rtl) {
        this.mShownWords = words;
        this.mShownKeyboard = keyboard;
        this.mShownKeyboardView = this.mKeyboardView;
        this.mShownRtl = rtl;
        this.mShownVisibility = getVisibility();
        this.mShownSupported = isSupportedKeyboard();
        this.mShownOnKey = isOnKeyPredictionEnabled();
        this.mShownEmojiBoard = isEmojiKeyboardShowing();
        this.mShownGesture = isGestureInputEnabled();
        this.mShownEmojiPredictions = isEmojiPredictionsEnabled();
        this.mShownStyleSpec = KeyboardColorManager.styleSpec();
        int[] location = keyboardLocationInWindow();
        this.mShownKeyboardLocation[0] = location[0];
        this.mShownKeyboardLocation[1] = location[1];
        this.mShownValid = true;
    }

    private void showAutoCorrectPrediction(SuggestedWords c0666ac, Keyboard c0965e) {
        if (c0666ac.mAutoCorrectWord != null && c0666ac.mWillAutoCorrect) {
            Key keyM6604b = c0965e.getKeyByCode(32);
            if (keyM6604b != null) {
                setSpacebarText(c0666ac.getWord(1));
                if (isGestureInputEnabled()) {
                    return;
                }
                String strMo4284a = c0666ac.getWord(0);
                this.mPredictionViews.get(this.mVisibleCount).setPrediction(0, (keyM6604b.getWidth() / 2) + keyM6604b.getX(), keyM6604b.getY(), true, strMo4284a, strMo4284a);
                this.mPredictionViews.get(this.mVisibleCount).setVisibility(View.VISIBLE);
                this.mVisibleCount = 1;
                setVisibility(View.VISIBLE);
                return;
            }
            return;
        }
        setSpacebarText("");
    }

    /** The candidate-hierarchy brightness step: #ffffff -> #d8d8d8 (~85%). */
    private static final float DE_EMPHASIS_BRIGHTNESS = 216f / 255f;

    private static int dimColor(int color) {
        return android.graphics.Color.argb(android.graphics.Color.alpha(color),
                Math.round(android.graphics.Color.red(color) * DE_EMPHASIS_BRIGHTNESS),
                Math.round(android.graphics.Color.green(color) * DE_EMPHASIS_BRIGHTNESS),
                Math.round(android.graphics.Color.blue(color) * DE_EMPHASIS_BRIGHTNESS));
    }

    /**
     * Candidate hierarchy for the on-key predictions (all themes), following
     * UnifiedSuggestionAdapter's emphasis: when an auto-correction is pending, the
     * word that commits on space (index 1, drawn on the spacebar and on its key if
     * it has one) keeps full emphasis and every other candidate renders one weight
     * step lighter and slightly darker. Unlike the strip, mTypedWordValid alone
     * does NOT de-emphasize here: the typed word usually has no on-key slot, and
     * dimming everything with the emphasized word nowhere in sight reads as a
     * rendering bug, not hierarchy. Without a pending auto-correction all
     * candidates render uniformly at full emphasis.
     */
    private boolean deEmphasized(int wordIndex) {
        SuggestedWords words = this.mSuggestedWords;
        return words != null && words.mWillAutoCorrect && wordIndex != 1;
    }

    /** One weight step down from the key-face type (500->400, 400->300; bold->normal below P). */
    private static android.graphics.Typeface deEmphasizedTypeface(android.graphics.Typeface base) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            int weight = base != null ? base.getWeight() : 400;
            return android.graphics.Typeface.create(
                    base != null ? base : android.graphics.Typeface.DEFAULT,
                    Math.max(300, weight - 100), false);
        }
        return android.graphics.Typeface.DEFAULT;
    }

    private static int[] toCodePoints(String str) {
        if (!TextUtils.isEmpty(str) && ScriptUtils.isLetterPartOfScript(str.charAt(0), 8)) {
            // Drawn text needs the engine's decode now: a short bounded query (Phase A), the raw
            // text on a timeout. Follow-up: a Java port of the decode would take the engine out of
            // drawing altogether.
            final String raw = str;
            String decoded = EngineOps.query("flickDecodeHangul", FLICK_DECODE_TIMEOUT_MS, raw,
                    sdk -> sdk.decodeHangul(raw, true));
            str = decoded != null ? decoded : raw;
        }
        return toCodePointArray(str, 0, str.length());
    }

    private void showOnKeyPredictions(SuggestedWords c0666ac, Keyboard c0965e) {
        String string = "";
        ArrayList<Integer> arrayList = new ArrayList<>();
        int i;
        int i2;
        
        if (c0666ac.mAutoCorrectWord != null) {
            string = c0666ac.getWord(0).toString();
            i = 0;
        } else {
            i = -1;
        }
        
        int[] iArrM5278b = toCodePoints(string);
        int length = iArrM5278b.length;
        
        if (c0666ac.mWillAutoCorrect) {
            setSpacebarText(c0666ac.getWord(1).toString());
            i2 = 1;
        } else {
            setSpacebarText("");
            i2 = -1;
        }
        
        boolean z6 = false;
        int i12 = 0;
        boolean hasEmojiSuggestion = false;
        int emojiSuggestionIndex = -1;
        
        for (int i13 = 0; i13 < c0666ac.size(); i13++) {
            if (c0666ac.getWordInfo(i13).isKind(11)) {
                // Found emoji suggestion - store for later display on period key via showEmojiPrediction()
                // Don't display on letter keys, but track that we found one
                hasEmojiSuggestion = true;
                if (emojiSuggestionIndex < 0) {
                    emojiSuggestionIndex = i13;
                }
                continue;
            }
            
            String strMo4284a = c0666ac.getWord(i13);
            int[] iArrM5278b2 = toCodePoints(strMo4284a);
            int length2 = iArrM5278b2.length;
            boolean z7 = iArrM5278b.length != 0 && strMo4284a.startsWith(string);
            
            Key keyM6604b = null;
            boolean z2 = false;
            boolean z3 = z6;
            int i3 = -1;
            if (i13 != i2) {
                if (length2 > 1 && i12 < 5) {
                    z2 = true;
                }
            }
            
            // FIX: This check must be SEPARATE, not nested - matches smali line 1218
            if (i13 == i) {
                if (i2 >= 0 && i2 != i) {
                    keyM6604b = c0965e.getKeyByCode(32);
                    i3 = 32;
                }
                z2 = false;
                z3 = true;
            }
            
            int i4 = i3;
            boolean z4 = z7;
            Key keyM6607c = keyM6604b;
            if (z2) {
                int i14 = 0;
                int i15 = 0;
                int i16 = -1;
                
                while (i14 < length2) {
                    int i17 = iArrM5278b2[i14];
                    int i18 = i14 - i15;
                    
                    if (i18 < length) {
                        if (i17 != iArrM5278b[i18]) {
                            if (i17 == 32) {
                                i15++;
                            }
                            z4 = false;
                        }
                    }
                    
                    if (i17 != 32) {
                        keyM6607c = c0965e.getActionKeyForCode(i17);
                        if (keyM6607c != null) {
                            int iM6232c = keyM6607c.getCode();
                            
                            if (i18 < length) {
                                if (iArrM5278b[i18] == i17) {
                                    // FIX: Removed && i15 > 0 - this was blocking all words without spaces
                                    boolean z5 = i14 == length2 - 1;
                                    int i11;
                                    
                                    if (z5) {
                                        if (i16 >= 0) {
                                            i11 = i16;
                                        } else if (i2 < 0) {
                                            i11 = 32;
                                        } else {
                                            i11 = iM6232c;
                                            z5 = false;
                                        }
                                    } else {
                                        i11 = iM6232c;
                                    }
                                    
                                    if (z5) {
                                        if (length2 - i15 <= length && !arrayList.contains(Integer.valueOf(i11))) {
                                            i4 = i11;
                                            break;
                                        } else if (i16 < 0) {
                                            i16 = i11;
                                        }
                                    } else {
                                        i16 = i11;
                                    }
                                } else {
                                    z4 = false;
                                    if (length2 - i15 <= length && !arrayList.contains(Integer.valueOf(iM6232c))) {
                                        i4 = iM6232c;
                                        break;
                                    }
                                }
                            } else if (!arrayList.contains(Integer.valueOf(iM6232c))) {
                                i4 = iM6232c;
                                break;
                            }
                        }
                    } else if (i14 == length2 - 1 && i2 < 0) {
                        keyM6607c = c0965e.getKeyByCode(32);
                        i4 = 32;
                        break;
                    }
                    
                    i14++;
                }
            }
            
            if (i4 >= 0 && keyM6607c != null) {
                this.mPredictionViews.get(this.mVisibleCount).setPrediction(i13, keyM6607c.getX() + (keyM6607c.getWidth() / 2), keyM6607c.getY(), z4, strMo4284a, string);
                
                boolean z8 = i13 == i;
                if (!z8 && !collidesWithVisible(this.mPredictionViews.get(this.mVisibleCount))) {
                    z8 = true;
                }
                
                if (z8) {
                    arrayList.add(Integer.valueOf(i4));
                    this.mPredictionViews.get(this.mVisibleCount).setVisibility(View.VISIBLE);
                    this.mVisibleCount++;
                    
                    if (i13 != i) {
                        i12++;
                    }
                    
                    if (i12 == 5 && (i < 0 || (i >= 0 && z3))) {
                        break;
                    }
                }
            }
            
            z6 = z3;
        }
        
        if (c0965e.getKeyByCode(46) != null && !arrayList.contains(46)) {
            // Display emoji on period key if we found one and emoji predictions are enabled
            if (hasEmojiSuggestion) {
                Logger.debug(TAG, "Emoji suggestion found at index " + emojiSuggestionIndex + ", calling showEmojiPrediction()");
            }
            showEmojiPrediction(c0666ac, c0965e, string);
        }
        
        if (this.mVisibleCount > 0) {
            setVisibility(View.VISIBLE);
        }
    }

    private void showEmojiPrediction(SuggestedWords c0666ac, Keyboard c0965e, String str) {
        Key keyM6604b;
        String strMo4284a;
        if (this.mVisibleCount <= 0 || !isEmojiPredictionsEnabled() || (keyM6604b = c0965e.getKeyByCode(46)) == null) {
            return;
        }
        int i = 0;
        while (true) {
            if (i >= c0666ac.size()) {
                strMo4284a = null;
                break;
            } else {
                if (c0666ac.getWordInfo(i).isKind(11)) {
                    strMo4284a = c0666ac.getWord(i);
                    break;
                }
                i++;
            }
        }
        if (strMo4284a != null) {
            this.mPredictionViews.get(this.mVisibleCount).setPrediction(i, keyM6604b.getX() + (keyM6604b.getWidth() / 2), keyM6604b.getY(), false, strMo4284a, str);
            if (collidesWithVisible(this.mPredictionViews.get(this.mVisibleCount))) {
                return;
            }
            this.mPredictionViews.get(this.mVisibleCount).setVisibility(View.VISIBLE);
            this.mVisibleCount++;
        }
    }

    private boolean collidesWithVisible(PredictionView c0826b) {
        if (this.mVisibleCount <= 0) {
            return false;
        }
        float y = c0826b.getY() + c0826b.getMeasuredHeight();
        float x = c0826b.getX() + c0826b.getMeasuredWidth();
        float f = this.mHorizontalWordDistance * this.mHorizontalOffsetFactor;
        for (int i = 0; i < this.mVisibleCount; i++) {
            PredictionView c0826b2 = this.mPredictionViews.get(i);
            float y2 = c0826b2.getY() - this.mVerticalWordDistance;
            if (c0826b2.getY() + c0826b2.getMeasuredHeight() + this.mVerticalWordDistance >= c0826b.getY() && y2 <= y) {
                float x2 = c0826b2.getX() - this.mHorizontalWordDistance;
                if (c0826b2.getX() + c0826b2.getMeasuredWidth() + this.mHorizontalWordDistance >= c0826b.getX() && x2 <= x) {
                    float x3 = c0826b2.getX() + (c0826b2.getMeasuredWidth() / 2.0f);
                    float x4 = c0826b.getX() + (c0826b.getMeasuredWidth() / 2.0f);
                    if (Math.abs(c0826b2.getY() - c0826b.getY()) > this.mVerticalWordDistance && Math.abs(x3 - x4) > f) {
                        // BuildConfig.DEBUG guard, not Logger's own: Logger.debug gates INSIDE
                        // the callee, so these five-part concatenations were built on every
                        // iteration of an O(visible^2) loop that runs per keystroke.
                        if (BuildConfig.DEBUG) {
                            Logger.debug(TAG, "\"" + ((Object) c0826b.getText()) + "\" and \"" + ((Object) c0826b2.getText()) + "\" are not in the same column.");
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            Logger.debug(TAG, "\"" + ((Object) c0826b.getText()) + "\" and \"" + ((Object) c0826b2.getText()) + "\" are colliding. Remove: \"" + ((Object) c0826b.getText()) + "\"");
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int findNearestPrediction(float f, float f2, float f3) {
                int[] location = new int[2];
        this.mKeyboardView.getLocationInWindow(location);
        float f4 = f + location[0];
        float f5 = f2 + location[1];
        float f6 = f3 + 1.0f;
        int i = -1;
        for (int i2 = 0; i2 < this.mVisibleCount; i2++) {
            float y = this.mPredictionViews.get(i2).getY() + (this.mPredictionViews.get(i2).getMeasuredHeight() * this.mTextVerticalPaddingFactor);
            if (y <= f5) {
                float distanceToRect = GeometryUtils.getDistanceToRect(f4, f5, this.mPredictionViews.get(i2).getX(), y, this.mPredictionViews.get(i2).getMeasuredWidth(), this.mPredictionViews.get(i2).getMeasuredHeight());
                if (distanceToRect < f6) {
                    i = i2;
                    f6 = distanceToRect;
                }
            }
        }
        return i;
    }

    public boolean handleFlick(float f, float f2, float f3) {
        int iM5275b;
        int iM5302a;
        long jUptimeMillis = SystemClock.uptimeMillis();
        if (jUptimeMillis < this.mLastSelectionTime + 200) {
            return false;
        }
        iM5275b = findNearestPrediction(f, f2, f3);
        if (iM5275b < 0) {
            return false;
        }
        iM5302a = this.mPredictionViews.get(iM5275b).getWordIndex();
        if (iM5302a >= this.mSuggestedWords.size()) {
            return false;
        }
        this.mLastSelectionTime = jUptimeMillis;
        this.mListener.onSuggestionPicked(this.mSuggestedWords.getWordInfo(iM5302a), InputSource.SOFTWARE);
        this.mPredictionViews.get(iM5275b).setVisibility(View.INVISIBLE);
        // The picked word is now hidden: the next update must redraw even if its list is equal.
        this.mShownValid = false;
        this.mAnimationView.animateSelection(this.mPredictionViews.get(iM5275b), this.mTypedTextColor, getParent());
        return true;
    }

    /**
     * Deliberate no-ops. VkbGestureListener dispatches every single-tap-up and show-press here;
     * the tutorial overlay they used to drive was deleted, and nothing replaced it. Kept only
     * because the call sites live in another package.
     */
    public void onSingleTap(float f, float f2) {
    }

    public void onShowPress(float f, float f2) {
    }

    /** Reusable scratch for the keyboard view's window position - see PredictionView. */
    private final int[] keyboardLocation = new int[2];

    private int[] keyboardLocationInWindow() {
        this.mKeyboardView.getLocationInWindow(this.keyboardLocation);
        return this.keyboardLocation;
    }

    
    private class PredictionView extends AppCompatTextView {

        private int mWordIndex;

        private SpannableStringBuilder mTextBuilder;

        public PredictionView(Context context) {
            super(context);
            setVisibility(View.GONE);
            setTextSize(0, FlickSuggestionView.this.mLetterSize);
            this.mTextBuilder = new SpannableStringBuilder();
        }

        public int getWordIndex() {
            return this.mWordIndex;
        }

        public void setPrediction(int i, int i2, int i3, boolean z, String str, String str2) {
            this.mWordIndex = i;
            // Sizing/weight follow the style spec, matching the key-face type; the
            // candidate hierarchy (see deEmphasized) drops non-default candidates a
            // weight step and a brightness step. Applied per-show (not in the
            // constructor) so theme switches take effect.
            dev.bbkb.ime.keyboard.StyleSpec spec = KeyboardColorManager.styleSpec();
            boolean deEmphasized = FlickSuggestionView.this.deEmphasized(i);
            setTextSize(0, FlickSuggestionView.this.mLetterSize * spec.getPredictionTextScale());
            android.graphics.Typeface typeface = spec.getMediumKeyTypeface()
                    ? dev.bbkb.ime.keyboard.KeyboardView
                            .mediumWeightTypeface(android.graphics.Typeface.DEFAULT)
                    : android.graphics.Typeface.DEFAULT;
            setTypeface(deEmphasized ? deEmphasizedTypeface(typeface) : typeface);
            ForegroundColorSpan suggestedSpan = deEmphasized
                    ? FlickSuggestionView.this.mSuggestedColorSpanDim
                    : FlickSuggestionView.this.mSuggestedColorSpan;
            ForegroundColorSpan typedSpan = deEmphasized
                    ? FlickSuggestionView.this.mTypedColorSpanDim
                    : FlickSuggestionView.this.mTypedColorSpan;
            int[] iArr = FlickSuggestionView.this.keyboardLocationInWindow();
            this.mTextBuilder.clear();
            this.mTextBuilder.clearSpans();
            this.mTextBuilder.append((CharSequence) str);
            if (z) {
                if (FlickSuggestionView.this.getCurrentKeyboardLocale().equals("hi")) {
                    this.mTextBuilder.setSpan(suggestedSpan, 0, str.length(), 17);
                } else {
                    // FIX: Use ForegroundColorSpan objects, not raw color ints
                    this.mTextBuilder.setSpan(typedSpan, 0, str2.length(), 17);
                    this.mTextBuilder.setSpan(suggestedSpan, str2.length(), str.length(), 17);
                }
            } else {
                this.mTextBuilder.setSpan(suggestedSpan, 0, str.length(), 17);
            }
            setText(this.mTextBuilder);
            measure(0, 0);
            float finalX = i2 - (getMeasuredWidth() / 2);
            float finalY = (i3 + iArr[1]) - (getMeasuredHeight() * FlickSuggestionView.this.mTextVerticalPaddingFactor);
            setX(finalX);
            setY(finalY);
            int width = iArr[0] + FlickSuggestionView.this.mKeyboardView.getWidth();
            if (getX() < iArr[0]) {
                setX(iArr[0]);
            } else if (getX() + getMeasuredWidth() > width) {
                setX(width - getMeasuredWidth());
            }
            // Drop shadow beneath the word when the spec asks for it (BB10 —
            // floating text kept legible over the sculpted caps); other styles
            // keep clean unshadowed rendering.
            if (spec.getPredictionDropShadow()) {
                float density = getResources().getDisplayMetrics().density;
                setShadowLayer(FlickSuggestionView.this.mShadowRadius, 0.0f,
                        1.5f * density, FlickSuggestionView.this.mShadowColor);
            } else {
                setShadowLayer(0.0f, 0.0f, 0.0f, 0);
            }
        }

    }

    public String getCurrentKeyboardLocale() {
        SettingsManager sharedPreferencesOnSharedPreferenceChangeListenerC0774c = this.mKeyboardView.getmSettings();
        return sharedPreferencesOnSharedPreferenceChangeListenerC0774c != null ? sharedPreferencesOnSharedPreferenceChangeListenerC0774c.getSettingsValues().locale.getLanguage() : "";
    }
}
