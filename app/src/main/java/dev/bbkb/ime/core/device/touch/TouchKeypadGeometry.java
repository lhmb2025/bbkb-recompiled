package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

/**
 * The touch keypad's coordinate frame (pad size in MotionEvent units). Each axis takes the first
 * source that knows:
 * <ol>
 *   <li>{@link Source#MEASURED}: ranges a running synthetic source read from the evdev node
 *       (EVIOCGABS), so a synthesised stream always uses its own frame;</li>
 *   <li>{@link Source#INPUT_DEVICE}: the pad InputDevice's motion ranges;</li>
 *   <li>{@link Source#PROFILE}: the config's {@code <touch-keypad range-x range-y>};</li>
 *   <li>{@link Source#LEGACY_Y_WARP}: Y only, forced-CKB config with no pad: the last sensor
 *       breakpoint of {@code <ckb-y-warp>}. It is a letter band, not a pad, so it feeds
 *       {@link #sensorYMax()} but never {@link #frameHeight()};</li>
 *   <li>{@link Source#KEY2_DEFAULT}: 1080 by 525.</li>
 * </ol>
 * Stroke key width and board height are 144 and 610 on the KEY2 frame, scaled with the frame.
 */
public final class TouchKeypadGeometry {
    public enum Source { MEASURED, INPUT_DEVICE, PROFILE, LEGACY_Y_WARP, KEY2_DEFAULT }

    /** KEY2 pad ABS_MT_POSITION_X / Y ranges in sensor units. */
    public static final int KEY2_WIDTH = 1080;
    public static final int KEY2_HEIGHT = 525;
    public static final int KEY2_STROKE_KEY_WIDTH = 144;
    public static final int KEY2_STROKE_BOARD_HEIGHT = 610;

    /** What a device with no pad resolves to. */
    public static final TouchKeypadGeometry KEY2_DEFAULT = new TouchKeypadGeometry(
            KEY2_WIDTH, KEY2_HEIGHT, 0f, Source.KEY2_DEFAULT, Source.KEY2_DEFAULT);

    private final float width;
    private final float height;
    private final float resolution;
    private final Source xSource;
    private final Source ySource;

    private TouchKeypadGeometry(float width, float height, float resolution,
                                Source xSource, Source ySource) {
        this.width = width;
        this.height = height;
        this.resolution = resolution;
        this.xSource = xSource;
        this.ySource = ySource;
    }

    /**
     * @param measured the ranges a running synthetic source measured, or null
     * @param device the scanned pad, or null when the OS enumerates none
     * @param declared the config's {@code <touch-keypad>}, or null
     * @param ckbYWarp the config's {@code <ckb-y-warp>}, or null
     * @param forcedCkb whether the config forces device-type CKB (only then the warp counts)
     */
    @NonNull
    public static TouchKeypadGeometry resolve(@Nullable TouchKeypadInfo measured,
                                              @Nullable TouchKeypadInfo device,
                                              @Nullable TouchKeypadConfig declared,
                                              @Nullable String ckbYWarp,
                                              boolean forcedCkb) {
        float w;
        Source xs;
        if (measured != null && measured.getXRangeMax() > 0f) {
            w = measured.getXRangeMax();
            xs = Source.MEASURED;
        } else if (device != null && device.getXRangeMax() > 0f) {
            w = device.getXRangeMax();
            xs = Source.INPUT_DEVICE;
        } else if (declared != null && declared.rangeX > 0) {
            w = declared.rangeX;
            xs = Source.PROFILE;
        } else {
            w = KEY2_WIDTH;
            xs = Source.KEY2_DEFAULT;
        }

        float h;
        Source ys;
        final float warpY = forcedCkb ? lastWarpBreakpoint(ckbYWarp) : 0f;
        if (measured != null && measured.getYRangeMax() > 0f) {
            h = measured.getYRangeMax();
            ys = Source.MEASURED;
        } else if (device != null && device.getYRangeMax() > 0f) {
            h = device.getYRangeMax();
            ys = Source.INPUT_DEVICE;
        } else if (declared != null && declared.rangeY > 0) {
            h = declared.rangeY;
            ys = Source.PROFILE;
        } else if (warpY > 0f) {
            h = warpY;
            ys = Source.LEGACY_Y_WARP;
        } else {
            h = KEY2_HEIGHT;
            ys = Source.KEY2_DEFAULT;
        }

        final float resolution = device != null ? device.getResolution() : 0f;
        return new TouchKeypadGeometry(w, h, resolution, xs, ys);
    }

    /** Sensor coordinate of a {@code <ckb-y-warp>}'s last breakpoint ("0:0,450:324" gives 450), or 0. */
    static float lastWarpBreakpoint(@Nullable String ckbYWarp) {
        if (ckbYWarp == null) return 0f;
        try {
            String[] pts = ckbYWarp.trim().split(",");
            String last = pts[pts.length - 1];
            float v = Float.parseFloat(last.split(":")[0].trim());
            return v > 0 ? v : 0f;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    /** The active profile's frame; the KEY2 frame when no profile can be had. */
    @NonNull
    public static TouchKeypadGeometry current() {
        return DeviceProfile.current().getTouchKeypadGeometry();
    }

    public float width() { return width; }

    /** Includes the warp's band on the emulator rig. */
    public float height() { return height; }

    /** 0 when no InputDevice reported one. */
    public float resolution() { return resolution; }

    public Source xSource() { return xSource; }

    public Source ySource() { return ySource; }

    /** True when either axis came from the pad itself or its profile rather than a fallback. */
    public boolean isMeasured() {
        return isMeasured(xSource) || isMeasured(ySource);
    }

    private static boolean isMeasured(Source source) {
        return source == Source.MEASURED || source == Source.INPUT_DEVICE || source == Source.PROFILE;
    }

    /** Resolved height, or 0 (the "no pad" answer callers test for) when only the KEY2 default backs it. */
    public float sensorYMax() {
        return ySource == Source.KEY2_DEFAULT ? 0f : height;
    }

    public int frameWidth() {
        return Math.round(width);
    }

    /** The warp tier is a letter band, not a pad, so the frame stays the KEY2's there. */
    public int frameHeight() {
        return ySource == Source.LEGACY_Y_WARP ? KEY2_HEIGHT : Math.round(height);
    }

    public float frameAspect() {
        return frameWidth() / (float) frameHeight();
    }

    /** What the gesture recorder divides by when an event's InputDevice reports no ranges. */
    public float normalizationReference() {
        return Math.max(frameWidth(), frameHeight());
    }

    public int strokeKeyWidth() {
        return Math.round(KEY2_STROKE_KEY_WIDTH * (frameWidth() / (float) KEY2_WIDTH));
    }

    public int strokeBoardHeight() {
        return Math.round(KEY2_STROKE_BOARD_HEIGHT * (frameHeight() / (float) KEY2_HEIGHT));
    }

    @Override
    public String toString() {
        return "TouchKeypadGeometry{" + width + "x" + height
                + " (x:" + xSource + ", y:" + ySource + ")"
                + ", resolution=" + resolution
                + ", stroke=" + strokeKeyWidth() + "/" + strokeBoardHeight() + '}';
    }
}
