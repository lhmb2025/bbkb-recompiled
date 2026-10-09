package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

/**
 * The touch keypad's coordinate frame: the one answer to "how big is the pad, in the units its
 * MotionEvents carry", which the gesture arbiter, swipe typing's stroke analyser, the Gesture Lab
 * and the touch thresholds in {@code SettingsValues} all used to answer for themselves with KEY2
 * literals (1080 / 525 / 144 / 610).
 *
 * <p>Each axis is resolved on its own, first source that knows wins:
 * <ol>
 *   <li>{@link Source#INPUT_DEVICE} — the pad InputDevice's motion ranges (a real KEY2 reports
 *       0..1080 by 0..525, so it lands here and every derived value is exactly what it was);</li>
 *   <li>{@link Source#PROFILE} — the device config's {@code <touch-keypad range-x range-y>}, for a
 *       pad the OS does not enumerate with ranges, or a synthesised source that has no device;</li>
 *   <li>{@link Source#LEGACY_Y_WARP} — Y only, and only on a forced-CKB config with no pad of its
 *       own: the last sensor breakpoint of its {@code <ckb-y-warp>} ("0:0,450:324" gives 450).
 *       That is the emulator posing as a KEY2; the value describes the warp's letter band, not a
 *       pad, so it feeds {@link #sensorYMax()} and nothing that scales a frame (see
 *       {@link #frameHeight()});</li>
 *   <li>{@link Source#KEY2_DEFAULT} — the KEY2's pad, 1080 by 525.</li>
 * </ol>
 *
 * <p>The stroke analyser's two numbers ({@link #strokeKeyWidth()}, {@link #strokeBoardHeight()})
 * are the original app's KEY2 literals 144 and 610 scaled by the frame against the KEY2 frame, so
 * they are exactly 144 and 610 whenever the frame is the KEY2's.
 */
public final class TouchKeypadGeometry {

    /** Where one axis of the frame came from. */
    public enum Source { INPUT_DEVICE, PROFILE, LEGACY_Y_WARP, KEY2_DEFAULT }

    /** The KEY2's pad width in sensor units (athena {@code touch_keypad} ABS_MT_POSITION_X). */
    public static final int KEY2_WIDTH = 1080;
    /** The KEY2's pad height in sensor units (ABS_MT_POSITION_Y). */
    public static final int KEY2_HEIGHT = 525;
    /** The stroke analyser's key width on the KEY2 frame (the original app's literal). */
    public static final int KEY2_STROKE_KEY_WIDTH = 144;
    /** The stroke analyser's board height on the KEY2 frame (the original app's literal). */
    public static final int KEY2_STROKE_BOARD_HEIGHT = 610;

    /** The KEY2 frame with nothing measured: what a device with no pad at all resolves to. */
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
     * Resolve the frame from what is known about the pad.
     *
     * @param device the scanned pad, or null when the OS enumerates none
     * @param declared the config's {@code <touch-keypad>}, or null
     * @param ckbYWarp the config's {@code <ckb-y-warp>}, or null
     * @param forcedCkb whether the config forces device-type CKB (the only case the warp counts)
     */
    @NonNull
    public static TouchKeypadGeometry resolve(@Nullable TouchKeypadInfo device,
                                              @Nullable TouchKeypadConfig declared,
                                              @Nullable String ckbYWarp,
                                              boolean forcedCkb) {
        float w;
        Source xs;
        if (device != null && device.getXRangeMax() > 0f) {
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
        if (device != null && device.getYRangeMax() > 0f) {
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

    /**
     * The sensor coordinate of a {@code <ckb-y-warp>}'s last breakpoint ("0:0,450:324" gives 450),
     * or 0 when the spec is absent or unreadable. Moved here, unchanged, from
     * {@code DeviceProfile.getTouchKeypadYMax}.
     */
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

    /** Pad width in sensor units, as resolved. */
    public float width() { return width; }

    /** Pad height in sensor units, as resolved (the warp's band on the emulator rig). */
    public float height() { return height; }

    /** The pad's reported resolution, or 0 when no InputDevice reported one. */
    public float resolution() { return resolution; }

    public Source xSource() { return xSource; }

    public Source ySource() { return ySource; }

    /** True when either axis came from the pad itself or its profile rather than a fallback. */
    public boolean isMeasured() {
        return isMeasured(xSource) || isMeasured(ySource);
    }

    private static boolean isMeasured(Source s) {
        return s == Source.INPUT_DEVICE || s == Source.PROFILE;
    }

    /**
     * The Y extent {@code DeviceProfile.getTouchKeypadYMax()} reports: the resolved height, or 0
     * when nothing but the KEY2 default stands behind it (the "no pad" answer callers test for).
     */
    public float sensorYMax() {
        return ySource == Source.KEY2_DEFAULT ? 0f : height;
    }

    /** The pad frame's width, in whole sensor units. */
    public int frameWidth() {
        return Math.round(width);
    }

    /**
     * The pad frame's height, in whole sensor units. The legacy warp tier is the forced-CKB rig's
     * letter band, not a pad, so the frame stays the KEY2's there.
     */
    public int frameHeight() {
        return ySource == Source.LEGACY_Y_WARP ? KEY2_HEIGHT : Math.round(height);
    }

    /** Width over height of the frame (the Gesture Lab letterboxes the pad with it). */
    public float frameAspect() {
        return frameWidth() / (float) frameHeight();
    }

    /**
     * The isotropic reference the gesture recorder divides by when an event's own InputDevice
     * reports no ranges: the larger frame dimension (1080 on the KEY2 frame).
     */
    public float normalizationReference() {
        return Math.max(frameWidth(), frameHeight());
    }

    /** The stroke analyser's key width: 144 on the KEY2 frame, scaled with the frame's width. */
    public int strokeKeyWidth() {
        return Math.round(KEY2_STROKE_KEY_WIDTH * (frameWidth() / (float) KEY2_WIDTH));
    }

    /** The stroke analyser's board height: 610 on the KEY2 frame, scaled with the frame's height. */
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
