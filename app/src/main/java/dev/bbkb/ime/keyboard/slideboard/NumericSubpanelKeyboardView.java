package dev.bbkb.ime.keyboard.slideboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.PointerTracker;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.core.gesture.MultiPointerGestureDetector;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.GestureInputAvailability;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


public class NumericSubpanelKeyboardView extends SimplifiedKeyboardView implements SlideboardComponent {

    private MultiPointerGestureDetector gestureDetector;

    private final GestureInputAvailability gestureAvailability;

    private SettingsManager settingsManager;

    /** @param listener unused */
    @Override
    public void setListener(SlideboardComponent.Listener listener) {
        // The numeric pad never slides itself shut.
    }

    public NumericSubpanelKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    /** @param defStyleAttr the default style attribute */
    public NumericSubpanelKeyboardView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        this.gestureAvailability = new GestureInputAvailability();
        this.settingsManager = SettingsManager.getInstance();
    }

    /** @param multiPointerGestureDetector the shared gesture detector, or null on release */
    public void setGestureDetector(MultiPointerGestureDetector multiPointerGestureDetector) {
        this.gestureDetector = multiPointerGestureDetector;
    }

    /** Real key surfaces in every style, in the palette's slide tones on the darker panel ground. */
    @Override
    protected boolean drawsFlatModernBackground() {
        return false;
    }

    @Override
    protected int keyboardBackgroundColor() {
        return KeyboardColorManager.INSTANCE.getSlideGroundColor();
    }

    @Override
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    /** @param currencySymbol the currency symbol */
    public void updateKey(String currencySymbol) {
        Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            updateCurrencyKey(keyboard, currencySymbol);
        }
    }

    /** @param currencySymbol the currency setting's symbol, put on the currency key */
    public static void updateCurrencyKey(Keyboard keyboard, String currencySymbol) {
        Key keyM6597a;
        char cCharAt;
        if (currencySymbol.isEmpty() || (keyM6597a = keyboard.getKeyByOutputText("currencyExtendedKeyStyle")) == null || (cCharAt = currencySymbol.charAt(0)) == keyM6597a.getCode()) {
            return;
        }
        if (keyboard.replaceKey(keyM6597a, new Key(new MoreKeySpec(String.valueOf(cCharAt), 0, cCharAt, String.valueOf(cCharAt)), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, keyM6597a.getHintLabel(), 0, 9, keyM6597a.getX(), keyM6597a.getY(), keyboard.mHorizontalGap + keyM6597a.getWidth(), keyboard.mVerticalGap + keyM6597a.getHeight(), keyboard.mHorizontalGap, keyboard.mVerticalGap, "currencyExtendedKeyStyle", keyM6597a.getMoreKeys(), keyM6597a.getKeyLabelSet(), keyM6597a.getMoreKeysFlags(), 8, keyM6597a.getScanCode()))) {
            keyboard.rebuildProximityGrid();
            return;
        }
        Logger.error("NumSubpanel", "Cannot replace currency key: " + cCharAt + " on keyboard");
    }

    /**
     * On-screen position (reading order, 4 rows of 5) to index in the stored
     * {@code custom_slideboard_symbols} list. Match keys by position, never by
     * {@link Keyboard#getKeys()} index (it changes with labels). The settings editor uses this too.
     */
    public static final int[] STORAGE_INDEX_FOR_POSITION = {
            15, 16, 17, 4, 5,
            12, 13, 14, 2, 3,
            9, 10, 11, 19, 1,
            8, 7, 6, 18, 0,
    };

    /** On-screen position of the currency key (row 2, column 4). */
    public static final int CURRENCY_POSITION = 8;

    /** A currency slot still holding a placeholder is left alone, so it keeps following the setting. */
    public void applyCustomKeys(List<String> stored, Set<String> currencyPlaceholders) {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null || stored == null || stored.size() != STORAGE_INDEX_FOR_POSITION.length) {
            return;
        }
        List<String> byPosition = new ArrayList<>(stored.size());
        for (int storageIndex : STORAGE_INDEX_FOR_POSITION) {
            byPosition.add(stored.get(storageIndex));
        }
        applyKeysByPosition(keyboard, byPosition, currencyPlaceholders);
    }

    /** The pad's keys in reading order: 4 rows of 5. */
    public static List<Key> keysByPosition(Keyboard keyboard) {
        List<Key> keys = new ArrayList<>(keyboard.getKeys());
        keys.sort((a, b) -> a.getY() != b.getY()
                ? Integer.compare(a.getY(), b.getY())
                : Integer.compare(a.getX(), b.getX()));
        return keys;
    }

    /** Puts {@code symbols} (reading order) on the keys; also used by the settings editor's preview. */
    public static void applyKeysByPosition(Keyboard keyboard, List<String> symbols, Set<String> currencyPlaceholders) {
        List<Key> keys = keysByPosition(keyboard);
        if (keys.size() != symbols.size()) {
            return;
        }
        for (int pos = 0; pos < keys.size(); pos++) {
            String symbol = symbols.get(pos);
            if (pos == CURRENCY_POSITION && currencyPlaceholders.contains(symbol)) {
                continue;
            }
            Key key = keys.get(pos);
            keyboard.replaceKey(key, createReplacementKey(keyboard, symbol, key));
        }
        keyboard.rebuildProximityGrid();
    }

    /** @param symbol the symbol; empty keeps {@code key} */
    private static Key createReplacementKey(Keyboard keyboard, String symbol, Key key) {
        return symbol.length() == 0 ? key : new Key(new MoreKeySpec(symbol, 0, symbol.codePointAt(0), symbol), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, symbol, 0, key.getBackgroundType(), key.getX(), key.getY(), key.getWidth() + keyboard.mHorizontalGap, key.getHeight() + keyboard.mVerticalGap, keyboard.mHorizontalGap, keyboard.mVerticalGap, key.getOutputText(), null, key.getKeyLabelSet(), key.getMoreKeysFlags(), key.getActionFlags(), key.getScanCode());
    }

    @Override
    public void show() {
        if (isShowing()) {
            return;
        }
        setVisibility(View.VISIBLE);
    }

    @Override
    public void hide() {
        setVisibility(View.GONE);
    }

    @Override
    public int getSlideBoardWidth() {
        return ResourceConfigManager.getScreenWidthPixels(getResources()) / 2;
    }

    @Override
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        MultiPointerGestureDetector c0924a;
        PointerTracker c1084tM7596a = PointerTracker.getPointerTracker(motionEvent.getPointerId(motionEvent.getActionIndex()));
        if ((!this.gestureAvailability.isVkbGestureInputEnabled() || this.settingsManager.getSettingsValues().isVkbSwipeGesturesEnabled) && !c1084tM7596a.isSlideboardDragging() && (c0924a = this.gestureDetector) != null && c0924a.onTouchEvent(motionEvent)) {
            c1084tM7596a.cancelTracking();
            return true;
        }
        c1084tM7596a.processMotionEvent(motionEvent, this.keyboardActionListener);
        return true;
    }

    /** @param translationX the panel's x translation */
    @Override
    public void setXTranslation(float translationX) {
        setTranslationX(translationX);
    }
}
