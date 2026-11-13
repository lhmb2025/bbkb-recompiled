package dev.bbkb.ime.core.shared;



public final class GeometryUtils {
    public static float getDistance(float x1, float y1, float x2, float y2) {
        final double dx = x1 - x2;
        final double dy = y1 - y2;
        return (float) Math.sqrt((dx * dx) + (dy * dy));
    }

    public static float getDistanceToRect(float x, float y, float left, float top, float width, float height) {
        float f7 = width + left;
        float f8 = height + top;
        if (x < left) {
            if (y < top) {
                return getDistance(x, y, left, top);
            }
            return (y < top || y > f8) ? getDistance(x, y, left, f8) : left - x;
        }
        if (x < left || x > f7) {
            if (y < top) {
                return getDistance(x, y, f7, top);
            }
            return (y < top || y > f8) ? getDistance(x, y, f7, f8) : x - f7;
        }
        if (y < top) {
            return top - y;
        }
        if (y < top || y > f8) {
            return y - f8;
        }
        return 0.0f;
    }
}
