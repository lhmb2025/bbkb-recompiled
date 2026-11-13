package dev.bbkb.ime.core.ime;

import android.view.MotionEvent;

import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.core.gesture.MultiPointerGestureDetector;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.BlackBerryIME;

/**
 * On-screen keyboard fling gestures, fed from MainKeyboardView and NumericSubpanelKeyboardView
 * (gated by isVkbSwipeGesturesEnabled there). Capacitive-keypad gestures live in core/gesture/arbiter.
 *  - horizontal: backward (RTL-aware) deletes a word (-18); forward on the spacebar switches
 *    language; forward elsewhere closes an open slideboard translation
 *  - vertical: down dismiss-or-symbols (-19), up prediction selection (-17, FlickSuggestionView)
 *  - suppressed within vkbGestureSuppressionTimeout of a key event
 */
public final class VkbGestureListener extends MultiPointerGestureDetector.SimpleOnGestureListener {
    // Logger.info calls are DEBUG-guarded at the call site: Logger checks Log.isLoggable (a system
    // property lookup) before DEBUG, and the guard lets R8 strip them.

    private BlackBerryIME ime;

    private SettingsValues settings;

    private long lastKeyEventTime;

    /** @param c0804d current settings values */
    public VkbGestureListener(BlackBerryIME blackBerryIME, SettingsValues c0804d) {
        this.ime = blackBerryIME;
        updateSettings(c0804d);
    }

    /** @param f horizontal displacement in px (negative is leftward) */
    private boolean isBackwardSwipe(float f) {
        if (this.ime.isCurrentLanguageRtl()) {
            if (f > 0.0f) {
                return true;
            }
        } else if (f < 0.0f) {
            return true;
        }
        return false;
    }

    /**
     * @param f horizontal fling velocity
     * @param f2 vertical fling velocity
     */
    @Override
    public boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
        if (motionEvent == null || motionEvent2 == null || this.ime.getLastKeyEventTime() > motionEvent.getEventTime()) {
            if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: ignored (null event or newer physical key event)");
            return false;
        }
        int actionIndex = motionEvent.getActionIndex();
        int actionIndex2 = motionEvent2.getActionIndex();
        float x = motionEvent2.getX(actionIndex2) - motionEvent.getX(actionIndex);
        float y = motionEvent2.getY(actionIndex2) - motionEvent.getY(actionIndex);
        float fAbs = Math.abs(x);
        float fAbs2 = Math.abs(y);
        double d = fAbs2;
        double d2 = fAbs;
        boolean z = true;
        if (d <= this.settings.horizontalSwipeTanTheta * d2) {
            if (this.ime.shouldHandleGestureEvent(motionEvent) && d > (this.settings.horizontalSwipeTanTheta / 8.0d) * d2) {
                if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: horizontal swipe ignored - exceeded flow typing's reduced threshold");
            } else if (this.ime.shouldHandleGestureEvent(motionEvent) && motionEvent2.getEventTime() - motionEvent.getEventTime() > this.settings.swipeGestureTimeout) {
                if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: flow typing horizontal swipe ignored - too slow");
            } else {
                float fAbs3 = Math.abs(f);
                if ((fAbs >= this.settings.fastHorizontalSwipeMinX && fAbs3 >= this.settings.fastHorizontalSwipeMinVelocity) || (fAbs >= this.settings.slowHorizontalSwipeMinX && fAbs3 >= this.settings.slowHorizontalSwipeMinVelocity)) {
                    if (isBackwardSwipe(x)) {
                        if (this.ime.getKeyboardSwitcher().getMainKeyboardView().getTranslationX() > 0.0f) {
                            this.ime.getKeyboardSwitcher().getMainKeyboardView().setSlideTranslation(0.0f, true);
                            return true;
                        }
                        return dispatchSwipeGesture(-18, motionEvent, motionEvent2);
                    }
                    if (isSpacebarLanguageSwitchSwipe(motionEvent, motionEvent2)) {
                        return switchToNextLanguage();
                    }
                    if (this.ime.getKeyboardSwitcher().getMainKeyboardView().getTranslationX() < 0.0f) {
                        this.ime.getKeyboardSwitcher().getMainKeyboardView().setSlideTranslation(0.0f, true);
                        return true;
                    }
                } else {
                    if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: horizontal swipe ignored - too slow/short");
                }
                return false;
            }
        } else if (d2 < this.settings.verticalSwipeTanTheta * d) {
            float fAbs4 = Math.abs(f2);
            if (this.ime.shouldHandleGestureEvent(motionEvent) && motionEvent2.getEventTime() - motionEvent.getEventTime() > this.settings.swipeGestureTimeout) {
                if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: flow typing vertical swipe ignored - too slow");
                z = false;
            } else if (fAbs2 >= this.settings.fastVerticalSwipeMinY && fAbs4 >= this.settings.fastVerticalSwipeMinVelocity) {
                if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: vertical fast swipe");
            } else if (fAbs2 < this.settings.slowVerticalSwipeMinY || fAbs4 < this.settings.slowHorizontalSwipeMinVelocity) {
                if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: vertical swipe rejected - too slow/short");
                z = false;
            } else {
                if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: vertical slow swipe");
            }
            if (z) {
                if (y > 0.0f) {
                    if (this.ime.shouldHandleGestureEvent(motionEvent) && (d2 > (this.settings.verticalSwipeTanTheta / 8.0d) * d && fAbs4 <= this.settings.flowModeVerticalSwipeMinVelocity)) {
                        if (BuildConfig.DEBUG) Logger.info("VKBGesture", "onFling: vertical swipe ignored - exceeded flow typing's reduced threshold");
                    } else {
                        return dispatchSwipeGesture(-19, motionEvent, motionEvent2);
                    }
                } else if (y < 0.0f && this.ime.isInputViewShown()) {
                    return dispatchSwipeGesture(-17, motionEvent, motionEvent2);
                }
            }
        }
        return false;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent motionEvent) {
        if (!this.ime.hasFlickSuggestionView() || !this.ime.getFlickSuggestionView().isShown()) {
            return false;
        }
        this.ime.getFlickSuggestionView().onSingleTap(motionEvent.getX(), motionEvent.getY());
        return false;
    }

    @Override
    public void onShowPress(MotionEvent motionEvent) {
        if (motionEvent == null) {
            return;
        }
        if (this.ime.hasFlickSuggestionView() && this.ime.getFlickSuggestionView().isShown()) {
            this.ime.getFlickSuggestionView().onShowPress(motionEvent.getX(), motionEvent.getY());
        }
    }

    /** @param c0804d current settings values */
    public void updateSettings(SettingsValues c0804d) {
        this.settings = c0804d;
    }

    /** @param i swipe gesture code (-17 up, -18 delete word, -19 down) */
    private boolean dispatchSwipeGesture(int i, MotionEvent motionEvent, MotionEvent motionEvent2) {
        if (this.settings.vkbGestureSuppressionTimeout <= (this.settings.applySwipeSuppressionToEnd ? motionEvent2.getEventTime() : motionEvent.getEventTime()) - this.lastKeyEventTime) {
            this.ime.handleSwipeGesture(i, motionEvent, motionEvent2);
            return true;
        }
        if (BuildConfig.DEBUG) Logger.info("VKBGesture", "Suppressing gesture around key event");
        return false;
    }

    public void clearLastKeyEventTime() {
        setLastKeyEventTime(0L);
    }

    /** @param j event time of the last key event (uptime ms) */
    public void setLastKeyEventTime(long j) {
        this.lastKeyEventTime = j;
    }

    private boolean isSpacebarLanguageSwitchSwipe(MotionEvent motionEvent, MotionEvent motionEvent2) {
        RichInputMethodManager c0910yM5863a = RichInputMethodManager.getInstance();
        if (c0910yM5863a == null || !c0910yM5863a.hasMultipleEnabledSubtypesInThisIme(false) || !this.settings.isSpacebarLanguageSwitchingEnabled) {
            return false;
        }
        float x = motionEvent.getX(motionEvent.getActionIndex());
        float y = motionEvent.getY(motionEvent.getActionIndex());
        float y2 = motionEvent2.getY(motionEvent2.getActionIndex());
        Key keyM6604b = KeyboardSwitcher.getInstance().getCurrentKeyboard().getKeyByCode(32);
        if (keyM6604b == null) {
            return false;
        }
        float fM6215aa = (keyM6604b.getHeight() * 0.1f) / 2.0f;
        float fMo6216ab = keyM6604b.getX();
        float fMo6216ab2 = keyM6604b.getX() + keyM6604b.getWidth();
        float fMo6217ac = keyM6604b.getY() - fM6215aa;
        float fMo6217ac2 = keyM6604b.getY() + keyM6604b.getHeight() + fM6215aa;
        return y > fMo6217ac && y < fMo6217ac2 && y2 > fMo6217ac && y2 < fMo6217ac2 && x > fMo6216ab && x < fMo6216ab2;
    }

    public boolean switchToNextLanguage() {
        return this.ime.updateSuggestionsFromSubtype(InputSource.SOFTWARE);
    }
}
