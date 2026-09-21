package dev.bbkb.ime.core.gesture;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.BuildConfig;



public class ShakeDetector implements SensorEventListener {

    private static final String TAG = "ShakeDetector";

    /** Max sensor report latency, microseconds (batching window for accelerometer samples). */
    private static final int MAX_REPORT_LATENCY_US = 100_000;

    private SettingsValues settings;

    private final Listener listener;

    private final SensorManager sensorManager;

    private final Sensor accelerometer;

    private long lastShakeTimestamp;

    private boolean shakeHandled;

    private final int[] shakeCounts = {0, 0, 0, 0};

    private boolean listening = false;

    private final float[] gravity = {0.0f, 0.0f, 0.0f};

    private final float[] linearAcceleration = {0.0f, 0.0f, 0.0f};

    private float accelerationMagnitude;

    
    public interface Listener {
        boolean onShake(int i, int i2, long j);
    }

    @Override // android.hardware.SensorEventListener
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    public ShakeDetector(Context context, Listener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = this.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public void start() {
        if (this.accelerometer == null || this.listening) {
            return;
        }
        this.settings = SettingsManager.getInstance().getSettingsValues();
        // Audit GD-42: 2 == SENSOR_DELAY_UI; the trailing value is the max report latency in
        // microseconds (100 ms of batching).
        this.listening = this.sensorManager.registerListener(
                this, this.accelerometer, SensorManager.SENSOR_DELAY_UI, MAX_REPORT_LATENCY_US);
    }

    public void stop() {
        if (this.accelerometer == null || !this.listening) {
            return;
        }
        this.sensorManager.unregisterListener(this);
        this.listening = false;
    }

    @Override // android.hardware.SensorEventListener
    public void onSensorChanged(SensorEvent sensorEvent) {
        updateAcceleration(sensorEvent);
        long j = sensorEvent.timestamp / 1000000;
        if (this.accelerationMagnitude < this.settings.shakeAccelerationThreshold) {
            if ((this.shakeHandled || this.shakeCounts[3] > 0) && this.lastShakeTimestamp + this.settings.shakeResetTime < j) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Reset - mLastShakeTimestamp=" + this.lastShakeTimestamp + ", resetTimeMs=" + this.settings.shakeResetTime + ", timeStampMillis=" + j);
                resetShakeCounts();
                return;
            }
            return;
        }
        if (this.lastShakeTimestamp + this.settings.shakeSlopTime > j) {
            return;
        }
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, String.format("accelX=%f, accelY=%f, accelZ=%f, accelTotal=%f", Float.valueOf(this.linearAcceleration[0]), Float.valueOf(this.linearAcceleration[1]), Float.valueOf(this.linearAcceleration[2]), Float.valueOf(this.accelerationMagnitude)));
        }
        this.lastShakeTimestamp = j;
        if (this.shakeHandled) {
            return;
        }
        int iM6030d = dominantAxis();
        if (Math.abs(this.linearAcceleration[iM6030d]) >= this.settings.shakeAccelerationThreshold) {
            int[] iArr = this.shakeCounts;
            iArr[iM6030d] = iArr[iM6030d] + 1;
            this.shakeHandled = this.listener.onShake(iM6030d, iArr[iM6030d], sensorEvent.timestamp);
        }
        if (this.shakeHandled) {
            return;
        }
        int[] iArr2 = this.shakeCounts;
        iArr2[3] = iArr2[3] + 1;
        this.shakeHandled = this.listener.onShake(3, iArr2[3], sensorEvent.timestamp);
    }

    private void resetShakeCounts() {
        Logger.debug(TAG, "Resetting shake counts");
        int[] iArr = this.shakeCounts;
        iArr[0] = 0;
        iArr[1] = 0;
        iArr[2] = 0;
        iArr[3] = 0;
        this.shakeHandled = false;
    }

    private void updateAcceleration(SensorEvent sensorEvent) {
        float[] fArr = this.gravity;
        fArr[0] = (fArr[0] * 0.8f) + (sensorEvent.values[0] * 0.19999999f);
        float[] fArr2 = this.gravity;
        fArr2[1] = (fArr2[1] * 0.8f) + (sensorEvent.values[1] * 0.19999999f);
        float[] fArr3 = this.gravity;
        fArr3[2] = (fArr3[2] * 0.8f) + (sensorEvent.values[2] * 0.19999999f);
        this.linearAcceleration[0] = sensorEvent.values[0] - this.gravity[0];
        this.linearAcceleration[1] = sensorEvent.values[1] - this.gravity[1];
        this.linearAcceleration[2] = sensorEvent.values[2] - this.gravity[2];
        float[] fArr4 = this.linearAcceleration;
        this.accelerationMagnitude = (float) Math.sqrt((fArr4[0] * fArr4[0]) + (fArr4[1] * fArr4[1]) + (fArr4[2] * fArr4[2]));
        if (Logger.isLoggable(TAG, Log.VERBOSE)) {
            if (BuildConfig.DEBUG) Log.v(TAG, String.format("eventX=%f, eventY=%f, eventZ=%f", Float.valueOf(sensorEvent.values[0]), Float.valueOf(sensorEvent.values[1]), Float.valueOf(sensorEvent.values[2])));
            if (BuildConfig.DEBUG) Log.v(TAG, String.format("gravityX=%f, gravityY=%f, gravityZ=%f", Float.valueOf(this.gravity[0]), Float.valueOf(this.gravity[1]), Float.valueOf(this.gravity[2])));
            if (BuildConfig.DEBUG) Log.v(TAG, String.format("accelX=%f, accelY=%f, accelZ=%f", Float.valueOf(this.linearAcceleration[0]), Float.valueOf(this.linearAcceleration[1]), Float.valueOf(this.linearAcceleration[2])));
            if (BuildConfig.DEBUG) Log.v(TAG, "Overall acceleration=" + this.accelerationMagnitude);
        }
    }

    private int dominantAxis() {
        int i = 0;
        for (int i2 = 1; i2 < 3; i2++) {
            if (Math.abs(this.linearAcceleration[i2]) > Math.abs(this.linearAcceleration[i])) {
                i = i2;
            }
        }
        return i;
    }
}
