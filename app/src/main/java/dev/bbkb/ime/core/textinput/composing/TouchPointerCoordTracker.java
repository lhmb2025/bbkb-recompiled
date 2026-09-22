package dev.bbkb.ime.core.textinput.composing;

import dev.bbkb.ime.core.shared.IntArrayList;

/**
 * Parallel arrays that record per-pointer coordinate and metadata samples during a touch
 * or gesture input event.
 *
 * <p>For each pointer sample the tracker stores six values in lockstep across six
 * {@link IntArrayList} buffers:
 * <ol>
 *   <li><b>code points</b> ({@code codePoints}) — the key code point hit by this sample</li>
 *   <li><b>x</b> ({@code xCoordinates}) — horizontal touch coordinate (pixels)</li>
 *   <li><b>y</b> ({@code yCoordinates}) — vertical touch coordinate (pixels)</li>
 *   <li><b>pointer id</b> ({@code pointerIds}) — Android pointer identifier</li>
 *   <li><b>timestamp</b> ({@code times}) — event time in milliseconds</li>
 *   <li><b>intentional</b> ({@code intentionalFlags}) — flag (0/1) indicating whether the sample
 *       was intentional (not accidental or noise)</li>
 * </ol>
 *
 * <p>Used by {@link dev.bbkb.ime.core.inputmethod.AbstractInputProcessor}
 * to pass gesture stroke data into NuanceSDK for gesture-word recognition, and by
 * {@link dev.bbkb.ime.core.textinput.composing.ComposingTextTracker} to
 * track coordinate history for the current composing session.
 *
 * <p>All six arrays are always the same length; operations ({@code add}, {@code set},
 * {@code trim}, {@code reset}) are applied to all six simultaneously.
 */

public final class TouchPointerCoordTracker {

    private static final String TAG = "TouchPointerCoordTracker";

    /** Initial and reset capacity for all six coordinate arrays. */
    private final int defaultCapacity;

    /** Code points buffer — the key code point for each pointer sample. */
    private final IntArrayList codePoints;

    /** X-coordinate buffer — horizontal touch position for each sample. */
    private final IntArrayList xCoordinates;

    /** Y-coordinate buffer — vertical touch position for each sample. */
    private final IntArrayList yCoordinates;

    /** Pointer-ID buffer — Android pointer identifier for each sample. */
    private final IntArrayList pointerIds;

    /** Timestamp buffer — event time (ms) for each sample. */
    private final IntArrayList times;

    /** Intentional-flag buffer — whether each sample was intentional (non-noise). */
    private final IntArrayList intentionalFlags;

    /**
     * Creates a new tracker with all six buffers pre-allocated to {@code i} entries.
     *
     * @param i initial capacity for each coordinate array
     */
    public TouchPointerCoordTracker(int i) {
        this.defaultCapacity = i;
        this.codePoints = new IntArrayList(i);
        this.xCoordinates = new IntArrayList(i);
        this.yCoordinates = new IntArrayList(i);
        this.pointerIds = new IntArrayList(i);
        this.times = new IntArrayList(i);
        this.intentionalFlags = new IntArrayList(i);
    }

    /**
     * Overwrites the sample at index {@code i} with the provided values across all
     * six coordinate arrays.
     *
     * @param i   index of the sample to overwrite
     * @param i2  code point
     * @param i3  x coordinate
     * @param i4  y coordinate
     * @param i5  pointer id
     * @param i6  timestamp
     * @param i7  intentional flag
     */
    public void addPointerAt(int i, int i2, int i3, int i4, int i5, int i6, int i7) {
        this.codePoints.addAt(i, i2);
        this.xCoordinates.addAt(i, i3);
        this.yCoordinates.addAt(i, i4);
        this.pointerIds.addAt(i, i5);
        this.times.addAt(i, i6);
        this.intentionalFlags.addAt(i, i7);
    }

    /**
     * Appends one new sample to all six coordinate arrays.
     *
     * @param i   code point
     * @param i2  x coordinate
     * @param i3  y coordinate
     * @param i4  pointer id
     * @param i5  timestamp
     * @param i6  intentional flag
     */
    public void addPointer(int i, int i2, int i3, int i4, int i5, int i6) {
        this.codePoints.add(i);
        this.xCoordinates.add(i2);
        this.yCoordinates.add(i3);
        this.pointerIds.add(i4);
        this.times.add(i5);
        this.intentionalFlags.add(i6);
    }

    /**
     * Replaces this tracker's contents with {@code c0697m}'s, adopting its six
     * arrays BY REFERENCE (no copy — both trackers share storage afterwards).
     * Mirrors AOSP InputPointers.set().
     *
     * @param c0697m the source tracker whose arrays are adopted
     */
    public void setTo(TouchPointerCoordTracker c0697m) {
        this.codePoints.setSharingBackingArray(c0697m.codePoints);
        this.xCoordinates.setSharingBackingArray(c0697m.xCoordinates);
        this.yCoordinates.setSharingBackingArray(c0697m.yCoordinates);
        this.pointerIds.setSharingBackingArray(c0697m.pointerIds);
        this.times.setSharingBackingArray(c0697m.times);
        this.intentionalFlags.setSharingBackingArray(c0697m.intentionalFlags);
    }

    /**
     * Replaces this tracker's contents with a COPY of {@code c0697m}'s samples
     * (independent storage). Mirrors AOSP InputPointers.copy().
     *
     * @param c0697m the source tracker whose samples are copied
     */
    public void copyFrom(TouchPointerCoordTracker c0697m) {
        this.codePoints.copy(c0697m.codePoints);
        this.xCoordinates.copy(c0697m.xCoordinates);
        this.yCoordinates.copy(c0697m.yCoordinates);
        this.pointerIds.copy(c0697m.pointerIds);
        this.times.copy(c0697m.times);
        this.intentionalFlags.copy(c0697m.intentionalFlags);
    }

    /**
     * Trims all six arrays to at most {@code i} entries, keeping the first {@code i}
     * samples. Values of {@code i <= 0} are treated as 0 (clears all samples).
     *
     * @param i maximum number of samples to retain
     */
    public void truncate(int i) {
        if (i <= 0) {
            i = 0;
        }
        this.codePoints.setLength(i);
        this.xCoordinates.setLength(i);
        this.yCoordinates.setLength(i);
        this.pointerIds.setLength(i);
        this.times.setLength(i);
        this.intentionalFlags.setLength(i);
    }

    /**
     * Drops the first {@code i} samples from all six coordinate arrays, shifting
     * the remainder to the front.
     *
     * @param i number of leading samples to drop
     */
    public void dropFirstSamples(int i) {
        this.codePoints.dropFirst(i);
        this.xCoordinates.dropFirst(i);
        this.yCoordinates.dropFirst(i);
        this.pointerIds.dropFirst(i);
        this.times.dropFirst(i);
        this.intentionalFlags.dropFirst(i);
    }

    /**
     * Creates a new {@code TouchPointerCoordTracker} containing the first {@code i}
     * samples from this tracker, allocated with a fixed capacity of 48.
     *
     * @param i number of samples to copy into the new tracker
     * @return a new tracker containing the first {@code i} samples
     */
    public TouchPointerCoordTracker copyFirst(int i) {
        TouchPointerCoordTracker c0697m = new TouchPointerCoordTracker(48);
        if (i != 0) {
            c0697m.codePoints.append(this.codePoints, 0, i);
            c0697m.xCoordinates.append(this.xCoordinates, 0, i);
            c0697m.yCoordinates.append(this.yCoordinates, 0, i);
            c0697m.pointerIds.append(this.pointerIds, 0, i);
            c0697m.times.append(this.times, 0, i);
            c0697m.intentionalFlags.append(this.intentionalFlags, 0, i);
        }
        return c0697m;
    }

    /**
     * Resets all six arrays to the initial capacity ({@code defaultCapacity}), discarding all
     * recorded samples. Called at the start of each new composing session.
     */
    public void reset() {
        int i = this.defaultCapacity;
        this.codePoints.reset(i);
        this.xCoordinates.reset(i);
        this.yCoordinates.reset(i);
        this.pointerIds.reset(i);
        this.times.reset(i);
        this.intentionalFlags.reset(i);
    }

    /**
     * Returns the number of pointer samples currently recorded.
     *
     * @return sample count
     */
    public int getPointerSize() {
        return this.xCoordinates.getLength();
    }

    /**
     * Returns a copy of the x-coordinate array for all recorded samples.
     *
     * @return x-coordinate values as a primitive int array
     */
    public int[] getXCoordinates() {
        return this.xCoordinates.getPrimitiveArray();
    }

    /**
     * Returns a copy of the y-coordinate array for all recorded samples.
     *
     * @return y-coordinate values as a primitive int array
     */
    public int[] getYCoordinates() {
        return this.yCoordinates.getPrimitiveArray();
    }

    /**
     * Returns a copy of the intentional-flag array for all recorded samples.
     * Each element is {@code 0} (noise/accidental) or {@code 1} (intentional).
     *
     * @return intentional-flag values as a primitive int array
     */
    public int[] getIntentionalFlags() {
        return this.intentionalFlags.getPrimitiveArray();
    }

    @Override
    public String toString() {
        return "size=" + getPointerSize() + " id=" + this.pointerIds + " time=" + this.times + " code points=" + this.codePoints + " x=" + this.xCoordinates + " y=" + this.yCoordinates + " intentionals=" + this.intentionalFlags;
    }
}
