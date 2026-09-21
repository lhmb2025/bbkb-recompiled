package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import dev.bbkb.ime.keyboard.KeyboardColorManager;

/**
 * Live audio-level bars for the Material voice board. Levels arrive from the
 * recognizer's onRmsChanged and scroll through a fixed set of rounded bars;
 * when inactive the bars sit at their minimum height as a resting row of dots.
 */
public class VoiceWaveformView extends View {

    private static final int BAR_COUNT = 7;

    /** SpeechRecognizer RMS dB roughly spans -2..10. */
    private static final float RMS_MIN = -2f;
    private static final float RMS_RANGE = 12f;

    private final float[] levels = new float[BAR_COUNT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;

    public VoiceWaveformView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        float density = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(5 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setActive(boolean isActive) {
        this.active = isActive;
        if (!isActive) {
            java.util.Arrays.fill(levels, 0f);
        }
        invalidate();
    }

    public void setLevel(float rmsDb) {
        if (!active) {
            return;
        }
        float normalized = (rmsDb - RMS_MIN) / RMS_RANGE;
        normalized = Math.max(0f, Math.min(1f, normalized));
        System.arraycopy(levels, 1, levels, 0, BAR_COUNT - 1);
        levels[BAR_COUNT - 1] = normalized;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        paint.setColor(KeyboardColorManager.INSTANCE.getAccentColor());
        float density = getResources().getDisplayMetrics().density;
        float minHalf = 2 * density;
        float maxHalf = (getHeight() / 2f) - (3 * density);
        float slot = getWidth() / (float) BAR_COUNT;
        float centerY = getHeight() / 2f;
        for (int i = 0; i < BAR_COUNT; i++) {
            float x = (i + 0.5f) * slot;
            float half = Math.max(minHalf, levels[i] * maxHalf);
            canvas.drawLine(x, centerY - half, x, centerY + half, paint);
        }
    }
}
