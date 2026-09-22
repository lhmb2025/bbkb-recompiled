package dev.bbkb.ime.core.gesture;

import android.util.Log;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.BuildConfig;



public class ShakeGestureHandler implements ShakeDetector.Listener {

    private static final String TAG = "ShakeGestureHandler";

    private ShakeDetector shakeDetector;

    private SettingsValues settingsValues;

    private final BlackBerryIME ime;

    private final KeyboardActionListenerInterface actionListener;

    public ShakeGestureHandler(BlackBerryIME blackBerryIME, KeyboardActionListenerInterface actionListener) {
        this.ime = blackBerryIME;
        this.actionListener = actionListener;
    }

    @Override // dev.bbkb.ime.core.gesture.ShakeDetector.Listener
    public boolean onShake(int i, int i2, long j) {
        if (this.ime == null) {
            return false;
        }
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "onShake type= " + i + ", count=" + i2 + ", eventTime=" + j);
        }
        int action = getActionForShakeType(i);
        int i3 = i == 3 ? this.settingsValues.shakeFallbackTriggerCount : this.settingsValues.shakeOnAxisTriggerCount;
        if (action == 0 || i2 != i3) {
            return false;
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "Invoking action " + action + " for shake of type " + i);
        switch (action) {
            case 1:
                this.ime.toggleCursorMode();
                break;
            case 2:
                sendCodeInput(-37, j);
                break;
            case 3:
                sendCodeInput(-10, j);
                break;
            case 4:
                sendCodeInput(-26, j);
                break;
            default:
                if (BuildConfig.DEBUG) Log.w(TAG, "Unexpected shake action code " + action + " for type " + i);
                break;
        }
        return false;
    }

    private int getActionForShakeType(int i) {
        switch (i) {
            case 0:
                return this.settingsValues.shakeActionX;
            case 1:
                return this.settingsValues.shakeActionY;
            case 2:
                return this.settingsValues.shakeActionZ;
            case 3:
                return this.settingsValues.shakeActionFallback;
            default:
                if (BuildConfig.DEBUG) Log.w(TAG, "Unknown shake type " + i);
                return 0;
        }
    }

    private void sendCodeInput(int i, long j) {
        this.actionListener.onCodeInput(i, -1, -1, j / 1000000, false);
    }

    public void start() {
        this.settingsValues = SettingsManager.getInstance().getSettingsValues();
        if (this.settingsValues.shakeActionX == 0 && this.settingsValues.shakeActionY == 0 && this.settingsValues.shakeActionZ == 0 && this.settingsValues.shakeActionFallback == 0) {
            ShakeDetector detector = this.shakeDetector;
            if (detector != null) {
                detector.stop();
                this.shakeDetector = null;
            }
        } else if (this.shakeDetector == null) {
            this.shakeDetector = new ShakeDetector(this.ime.getApplicationContext(), this);
        }
        ShakeDetector detector = this.shakeDetector;
        if (detector != null) {
            detector.start();
        }
    }

    public void stop() {
        ShakeDetector detector = this.shakeDetector;
        if (detector != null) {
            detector.stop();
        }
    }
}
