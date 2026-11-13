package dev.bbkb.ime.core.textinput.composing;

import dev.bbkb.ime.core.shared.IntArrayList;

/**
 * Six parallel {@link IntArrayList} buffers of per-pointer samples (code point, x, y, pointer id,
 * time in ms, intentional flag 0/1), always the same length and mutated together. Carries gesture
 * stroke data to the engine and the coordinate history of the composing word.
 */

public final class TouchPointerCoordTracker {

    private static final String TAG = "TouchPointerCoordTracker";

    private final int defaultCapacity;

    private final IntArrayList codePoints;

    private final IntArrayList xCoordinates;

    private final IntArrayList yCoordinates;

    private final IntArrayList pointerIds;

    private final IntArrayList times;

    private final IntArrayList intentionalFlags;

    /**
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
     * Overwrites sample {@code i} (args: code point, x, y, pointer id, time, intentional).
     * @param i index of the sample to overwrite
     * @param i2 code point
     * @param i3 x coordinate
     * @param i4 y coordinate
     * @param i5 pointer id
     * @param i6 timestamp
     * @param i7 intentional flag
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
     * Appends one sample (args: code point, x, y, pointer id, time, intentional).
     * @param i code point
     * @param i2 x coordinate
     * @param i3 y coordinate
     * @param i4 pointer id
     * @param i5 timestamp
     * @param i6 intentional flag
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
     * Adopts {@code c0697m}'s arrays by reference: both trackers share storage afterwards.
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
     * Replaces this tracker's contents with an independent copy of {@code c0697m}'s samples.
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
     * Keeps the first {@code i} samples; {@code i <= 0} clears all.
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
     * A new tracker (capacity 48) holding the first {@code i} samples.
     * @param i number of samples to copy into the new tracker
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

    public void reset() {
        int i = this.defaultCapacity;
        this.codePoints.reset(i);
        this.xCoordinates.reset(i);
        this.yCoordinates.reset(i);
        this.pointerIds.reset(i);
        this.times.reset(i);
        this.intentionalFlags.reset(i);
    }

    public int getPointerSize() {
        return this.xCoordinates.getLength();
    }

    public int[] getXCoordinates() {
        return this.xCoordinates.getPrimitiveArray();
    }

    public int[] getYCoordinates() {
        return this.yCoordinates.getPrimitiveArray();
    }

    /** Each element is {@code 0} (noise/accidental) or {@code 1} (intentional). */
    public int[] getIntentionalFlags() {
        return this.intentionalFlags.getPrimitiveArray();
    }

    @Override
    public String toString() {
        return "size=" + getPointerSize() + " id=" + this.pointerIds + " time=" + this.times + " code points=" + this.codePoints + " x=" + this.xCoordinates + " y=" + this.yCoordinates + " intentionals=" + this.intentionalFlags;
    }
}
