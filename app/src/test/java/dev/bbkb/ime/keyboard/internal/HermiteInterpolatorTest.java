package dev.bbkb.ime.keyboard.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the Hermite tangents for the drawn gesture trail
 * ({@code GestureStrokeRecognizer.appendAllBatchPoints}): collinear evenly spaced points reproduce
 * the line, and the result is translation invariant (tangents use the endpoints, not the delta
 * {@code P2 - P1}). The effect is visual only; the recogniser's point list is unaffected.
 */
public class HermiteInterpolatorTest {

    /** Interpolating between two interior points of a straight line must stay on the line. */
    @Test
    public void straightLineIsReproducedWhenBothNeighboursExist() {
        final int[] xs = {0, 10, 20, 30};
        final int[] ys = {0, 0, 0, 0};
        final HermiteInterpolator interpolator = new HermiteInterpolator();
        interpolator.reset(xs, ys, 0, xs.length);
        // p0 = 0 (>= minPos, so the "tangent at P1 from P0->P2" branch runs)
        // p3 = 3 (<  maxPos, so the "tangent at P2 from P1->P3" branch runs)
        interpolator.setInterval(0, 1, 2, 3);

        assertEquals(10.0f, interpolator.mSlope1X, 0.0001f);
        assertEquals(10.0f, interpolator.mSlope2X, 0.0001f);

        interpolator.interpolate(0.5f);
        assertEquals(15.0f, interpolator.mInterpolatedX, 0.0001f);
        assertEquals(0.0f, interpolator.mInterpolatedY, 0.0001f);
    }

    /** Same, on a diagonal, so an X-only or Y-only regression cannot hide. */
    @Test
    public void straightDiagonalIsReproduced() {
        final int[] xs = {100, 200, 300, 400};
        final int[] ys = {50, 150, 250, 350};
        final HermiteInterpolator interpolator = new HermiteInterpolator();
        interpolator.reset(xs, ys, 0, xs.length);
        interpolator.setInterval(0, 1, 2, 3);

        interpolator.interpolate(0.25f);
        assertEquals(225.0f, interpolator.mInterpolatedX, 0.001f);
        assertEquals(175.0f, interpolator.mInterpolatedY, 0.001f);

        interpolator.interpolate(0.75f);
        assertEquals(275.0f, interpolator.mInterpolatedX, 0.001f);
        assertEquals(225.0f, interpolator.mInterpolatedY, 0.001f);
    }

    /**
     * Translating the stroke translates the interpolated point by the same amount (a real swipe is far
     * from the origin). {@code minPos = 0}, so the "P0 available" branch computes the tangent at P1.
     */
    @Test
    public void interpolationIsTranslationInvariantWithP0Available() {
        assertTranslationInvariant(0 /* minPos */);
    }

    /**
     * Same property in the mirrored branch: no P0, so the tangent at P1 is the tangent at P2 reflected
     * about the P1 -> P2 vector.
     */
    @Test
    public void interpolationIsTranslationInvariantWithoutP0() {
        assertTranslationInvariant(1 /* minPos: puts p0 = 0 out of range */);
    }

    private static void assertTranslationInvariant(int minPos) {
        final int[] baseXs = {0, 10, 25, 30};
        final int[] baseYs = {0, 8, 9, 40};
        final int offsetX = 900;
        final int offsetY = 700;

        final HermiteInterpolator atOrigin = new HermiteInterpolator();
        atOrigin.reset(baseXs.clone(), baseYs.clone(), minPos, baseXs.length);
        atOrigin.setInterval(0, 1, 2, 3);
        atOrigin.interpolate(0.4f);

        final int[] movedXs = new int[baseXs.length];
        final int[] movedYs = new int[baseYs.length];
        for (int i = 0; i < baseXs.length; i++) {
            movedXs[i] = baseXs[i] + offsetX;
            movedYs[i] = baseYs[i] + offsetY;
        }
        final HermiteInterpolator moved = new HermiteInterpolator();
        moved.reset(movedXs, movedYs, minPos, movedXs.length);
        moved.setInterval(0, 1, 2, 3);
        moved.interpolate(0.4f);

        assertEquals(atOrigin.mInterpolatedX + offsetX, moved.mInterpolatedX, 0.05f);
        assertEquals(atOrigin.mInterpolatedY + offsetY, moved.mInterpolatedY, 0.05f);
    }
}
