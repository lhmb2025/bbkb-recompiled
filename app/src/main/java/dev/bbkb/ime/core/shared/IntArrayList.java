package dev.bbkb.ime.core.shared;

import java.util.Arrays;



public final class IntArrayList {

    private int[] array;

    private int length;

    public IntArrayList(int capacity) {
        reset(capacity);
    }

    public int get(int index) {
        if (index < this.length) {
            return this.array[index];
        }
        throw new ArrayIndexOutOfBoundsException("length=" + this.length + "; index=" + index);
    }

    /**
     * @param index index; past the end, the list grows to it
     * @param value value
     */
    public void addAt(int index, int value) {
        if (index < this.length) {
            this.array[index] = value;
        } else {
            this.length = index;
            add(value);
        }
    }

    public void add(int value) {
        int i2 = this.length;
        int i3 = i2 + 1;
        ensureCapacity(i3);
        this.array[i2] = value;
        this.length = i3;
    }

    private int calculateCapacity(int minimumCapacity) {
        int length = this.array.length;
        if (length >= minimumCapacity) {
            return 0;
        }
        int i2 = length * 2;
        return minimumCapacity > i2 ? minimumCapacity : i2;
    }

    private void ensureCapacity(int minimumCapacity) {
        int iM5568f = calculateCapacity(minimumCapacity);
        if (iM5568f > 0) {
            this.array = Arrays.copyOf(this.array, iM5568f);
        }
    }

    public int getLength() {
        return this.length;
    }

    public void setLength(int newLength) {
        ensureCapacity(newLength);
        this.length = newLength;
    }

    public void reset(int capacity) {
        this.array = new int[capacity];
        this.length = 0;
    }

    public int[] getPrimitiveArray() {
        return this.array;
    }

    /** Adopts {@code other}'s backing array by reference, so the lists share storage; see {@link #copy(IntArrayList)}. */
    public void setSharingBackingArray(IntArrayList other) {
        this.array = other.array;
        this.length = other.length;
    }

    /** Deep-copies {@code other}'s contents into this list; the two lists stay independent. */
    public void copy(IntArrayList other) {
        int iM5568f = calculateCapacity(other.length);
        if (iM5568f > 0) {
            this.array = new int[iM5568f];
        }
        System.arraycopy(other.array, 0, this.array, 0, other.length);
        this.length = other.length;
    }

    public void append(IntArrayList src, int startPos, int count) {
        if (count == 0) {
            return;
        }
        int i3 = this.length;
        int i4 = i3 + count;
        ensureCapacity(i4);
        System.arraycopy(src.array, startPos, this.array, i3, count);
        this.length = i4;
    }

    public void dropFirst(int count) {
        int[] iArr = this.array;
        System.arraycopy(iArr, count, iArr, 0, this.length - count);
        this.length -= count;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.length; i++) {
            if (i != 0) {
                sb.append(",");
            }
            sb.append(this.array[i]);
        }
        return "[" + ((Object) sb) + "]";
    }
}
