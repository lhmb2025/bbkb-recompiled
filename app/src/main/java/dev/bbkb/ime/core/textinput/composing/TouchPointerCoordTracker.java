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
     * @param defaultCapacity initial capacity for each coordinate array
     */
    public TouchPointerCoordTracker(int defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
        this.codePoints = new IntArrayList(defaultCapacity);
        this.xCoordinates = new IntArrayList(defaultCapacity);
        this.yCoordinates = new IntArrayList(defaultCapacity);
        this.pointerIds = new IntArrayList(defaultCapacity);
        this.times = new IntArrayList(defaultCapacity);
        this.intentionalFlags = new IntArrayList(defaultCapacity);
    }

    /**
     * Overwrites sample {@code index} (args: code point, x, y, pointer id, time, intentional).
     * @param index index of the sample to overwrite
     * @param codePoint code point
     * @param x x coordinate
     * @param y y coordinate
     * @param pointerId pointer id
     * @param time timestamp
     * @param intentionalFlag intentional flag
     */
    public void addPointerAt(int index, int codePoint, int x, int y, int pointerId, int time, int intentionalFlag) {
        this.codePoints.addAt(index, codePoint);
        this.xCoordinates.addAt(index, x);
        this.yCoordinates.addAt(index, y);
        this.pointerIds.addAt(index, pointerId);
        this.times.addAt(index, time);
        this.intentionalFlags.addAt(index, intentionalFlag);
    }

    /**
     * Appends one sample (args: code point, x, y, pointer id, time, intentional).
     * @param codePoint code point
     * @param x x coordinate
     * @param y y coordinate
     * @param pointerId pointer id
     * @param time timestamp
     * @param intentionalFlag intentional flag
     */
    public void addPointer(int codePoint, int x, int y, int pointerId, int time, int intentionalFlag) {
        this.codePoints.add(codePoint);
        this.xCoordinates.add(x);
        this.yCoordinates.add(y);
        this.pointerIds.add(pointerId);
        this.times.add(time);
        this.intentionalFlags.add(intentionalFlag);
    }

    /**
     * Adopts {@code touchPointerCoordTracker}'s arrays by reference: both trackers share storage afterwards.
     * @param touchPointerCoordTracker the source tracker whose arrays are adopted
     */
    public void setTo(TouchPointerCoordTracker touchPointerCoordTracker) {
        this.codePoints.setSharingBackingArray(touchPointerCoordTracker.codePoints);
        this.xCoordinates.setSharingBackingArray(touchPointerCoordTracker.xCoordinates);
        this.yCoordinates.setSharingBackingArray(touchPointerCoordTracker.yCoordinates);
        this.pointerIds.setSharingBackingArray(touchPointerCoordTracker.pointerIds);
        this.times.setSharingBackingArray(touchPointerCoordTracker.times);
        this.intentionalFlags.setSharingBackingArray(touchPointerCoordTracker.intentionalFlags);
    }

    /**
     * Replaces this tracker's contents with an independent copy of {@code touchPointerCoordTracker}'s samples.
     * @param touchPointerCoordTracker the source tracker whose samples are copied
     */
    public void copyFrom(TouchPointerCoordTracker touchPointerCoordTracker) {
        this.codePoints.copy(touchPointerCoordTracker.codePoints);
        this.xCoordinates.copy(touchPointerCoordTracker.xCoordinates);
        this.yCoordinates.copy(touchPointerCoordTracker.yCoordinates);
        this.pointerIds.copy(touchPointerCoordTracker.pointerIds);
        this.times.copy(touchPointerCoordTracker.times);
        this.intentionalFlags.copy(touchPointerCoordTracker.intentionalFlags);
    }

    /**
     * Keeps the first {@code length} samples; {@code length <= 0} clears all.
     * @param length maximum number of samples to retain
     */
    public void truncate(int length) {
        if (length <= 0) {
            length = 0;
        }
        this.codePoints.setLength(length);
        this.xCoordinates.setLength(length);
        this.yCoordinates.setLength(length);
        this.pointerIds.setLength(length);
        this.times.setLength(length);
        this.intentionalFlags.setLength(length);
    }

    /**
     * @param count number of leading samples to drop
     */
    public void dropFirstSamples(int count) {
        this.codePoints.dropFirst(count);
        this.xCoordinates.dropFirst(count);
        this.yCoordinates.dropFirst(count);
        this.pointerIds.dropFirst(count);
        this.times.dropFirst(count);
        this.intentionalFlags.dropFirst(count);
    }

    /**
     * A new tracker (capacity 48) holding the first {@code count} samples.
     * @param count number of samples to copy into the new tracker
     */
    public TouchPointerCoordTracker copyFirst(int count) {
        TouchPointerCoordTracker c0697m = new TouchPointerCoordTracker(48);
        if (count != 0) {
            c0697m.codePoints.append(this.codePoints, 0, count);
            c0697m.xCoordinates.append(this.xCoordinates, 0, count);
            c0697m.yCoordinates.append(this.yCoordinates, 0, count);
            c0697m.pointerIds.append(this.pointerIds, 0, count);
            c0697m.times.append(this.times, 0, count);
            c0697m.intentionalFlags.append(this.intentionalFlags, 0, count);
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
