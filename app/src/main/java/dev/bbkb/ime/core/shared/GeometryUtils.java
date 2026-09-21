package dev.bbkb.ime.core.shared;



public final class GeometryUtils {
    public static float getDistance(float f, float f2, float f3, float f4) {
        final double dx = f - f3;
        final double dy = f2 - f4;
        return (float) Math.sqrt((dx * dx) + (dy * dy));
    }

    public static float getDistanceToRect(float f, float f2, float f3, float f4, float f5, float f6) {
        float f7 = f5 + f3;
        float f8 = f6 + f4;
        if (f < f3) {
            if (f2 < f4) {
                return getDistance(f, f2, f3, f4);
            }
            return (f2 < f4 || f2 > f8) ? getDistance(f, f2, f3, f8) : f3 - f;
        }
        if (f < f3 || f > f7) {
            if (f2 < f4) {
                return getDistance(f, f2, f7, f4);
            }
            return (f2 < f4 || f2 > f8) ? getDistance(f, f2, f7, f8) : f - f7;
        }
        if (f2 < f4) {
            return f4 - f2;
        }
        if (f2 < f4 || f2 > f8) {
            return f2 - f8;
        }
        return 0.0f;
    }
}
