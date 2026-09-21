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
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.PointerTracker;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.core.gesture.MultiPointerGestureDetector;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.GestureInputAvailability;

import java.util.List;


public class NumericSubpanelKeyboardView extends SimplifiedKeyboardView implements SlideboardComponent {

    private MultiPointerGestureDetector gestureDetector;

    private final GestureInputAvailability gestureAvailability;

    private SettingsManager settingsManager;

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void setListener(SlideboardComponent.Listener aVar) {
    }

    public NumericSubpanelKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    public NumericSubpanelKeyboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.gestureAvailability = new GestureInputAvailability();
        this.settingsManager = SettingsManager.getInstance();
    }

    public void setGestureDetector(MultiPointerGestureDetector c0924a) {
        this.gestureDetector = c0924a;
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    public void updateKey(String str) {
        Key keyM6597a;
        char cCharAt;
        Keyboard keyboard = getKeyboard();
        if (str.isEmpty() || (keyM6597a = keyboard.getKeyByOutputText("currencyExtendedKeyStyle")) == null || (cCharAt = str.charAt(0)) == keyM6597a.getCode()) {
            return;
        }
        if (keyboard.replaceKey(keyM6597a, new Key(new MoreKeySpec(String.valueOf(cCharAt), 0, cCharAt, String.valueOf(cCharAt)), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, keyM6597a.getHintLabel(), 0, 9, keyM6597a.getX(), keyM6597a.getY(), keyboard.mHorizontalGap + keyM6597a.getWidth(), keyboard.mVerticalGap + keyM6597a.getHeight(), keyboard.mHorizontalGap, keyboard.mVerticalGap, "currencyExtendedKeyStyle", keyM6597a.getMoreKeys(), keyM6597a.getKeyLabelSet(), keyM6597a.getMoreKeysFlags(), 8, keyM6597a.getScanCode()))) {
            keyboard.rebuildProximityGrid();
            return;
        }
        Logger.error("NumSubpanel", "Cannot replace currency key: " + cCharAt + " on keyboard");
    }

    public void setNumericKeys(List<String> list) {
        Keyboard keyboard = getKeyboard();
        if (keyboard != null && list.size() == keyboard.getKeys().size()) {
            List<Key> listMo6608c = keyboard.getKeys();
            for (int i = 0; i < list.size(); i++) {
                Key key = listMo6608c.get(i);
                keyboard.replaceKey(key, createReplacementKey(list.get(i), key));
            }
            keyboard.rebuildProximityGrid();
        }
    }

    public void setSymbolKeys(List<String> list) {
        Keyboard keyboard = getKeyboard();
        if (keyboard != null && list.size() == keyboard.getKeys().size()) {
            List<Key> listMo6608c = keyboard.getKeys();
            for (int i = 0; i < list.size(); i++) {
                if (i != 8) {
                    Key key = listMo6608c.get(i);
                    keyboard.replaceKey(key, createReplacementKey(list.get(i), key));
                }
            }
            keyboard.rebuildProximityGrid();
        }
    }

    private Key createReplacementKey(String str, Key key) {
        Keyboard keyboard = getKeyboard();
        return (keyboard == null || str.length() == 0) ? key : new Key(new MoreKeySpec(str, 0, str.codePointAt(0), str), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, str, 0, key.getBackgroundType(), key.getX(), key.getY(), key.getWidth() + keyboard.mHorizontalGap, key.getHeight() + keyboard.mVerticalGap, keyboard.mHorizontalGap, keyboard.mVerticalGap, key.getOutputText(), null, key.getKeyLabelSet(), key.getMoreKeysFlags(), key.getActionFlags(), key.getScanCode());
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void show() {
        if (isShowing()) {
            return;
        }
        setVisibility(View.VISIBLE);
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void hide() {
        setVisibility(View.GONE);
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public int getSlideBoardWidth() {
        return ResourceConfigManager.getScreenWidthPixels(getResources()) / 2;
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView, android.view.View
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

    @Override // dev.bbkb.ime.keyboard.slideboard.SlideboardComponent
    public void setXTranslation(float f) {
        setTranslationX(f);
    }
}
