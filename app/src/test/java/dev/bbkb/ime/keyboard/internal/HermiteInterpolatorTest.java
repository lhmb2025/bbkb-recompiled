package dev.bbkb.ime.keyboard.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the Hermite tangents used to interpolate the drawn gesture trail
 * ({@code GestureStrokeRecognizer.appendAllBatchPoints}).
 *
 * <p>The decompiled {@code setInterval} substituted the local holding the DELTA {@code P2 - P1}
 * where the endpoint {@code P2} belonged in the "P0 available" branch, and where {@code P1}
 * belonged in the mirrored branch. The two symmetric branches at the bottom of the method were
 * correct, which is what made the substitution visible.
 *
 * <p>The effect was visual only — the recogniser's own point list is unaffected — but a tangent
 * that is a function of ABSOLUTE coordinates makes the drawn trail diverge from the finger the
 * further right / further down the keyboard the stroke runs, i.e. worst exactly where the keyboard
 * is widest.
 *
 * <p>Two properties are pinned. Collinear evenly-spaced control points, for which a correct spline
 * must reproduce the straight line exactly; and translation invariance, which is the property the
 * bug actually broke and which holds in every branch.
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
     * Translating the whole stroke must translate the interpolated point by the same amount. The
     * decompiled tangents were a function of absolute coordinates, so this failed for any non-zero
     * offset — and a real swipe is nowhere near the origin.
     *
     * <p>{@code minPos = 0}, so the "P0 available" branch computes the tangent at P1.
     */
    @Test
    public void interpolationIsTranslationInvariantWithP0Available() {
        assertTranslationInvariant(0 /* minPos */);
    }

    /**
     * Same property in the mirrored branch: no P0, so the tangent at P1 is derived by reflecting
     * the tangent at P2 about the P1 -> P2 vector. This is the branch whose {@code P1} the
     * decompiled source replaced with the delta.
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
