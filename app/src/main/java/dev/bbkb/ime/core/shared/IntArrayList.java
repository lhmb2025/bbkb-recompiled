package dev.bbkb.ime.core.shared;

import java.util.Arrays;



public final class IntArrayList {

    private int[] array;

    private int length;

    public IntArrayList(int i) {
        reset(i);
    }

    public int get(int i) {
        if (i < this.length) {
            return this.array[i];
        }
        throw new ArrayIndexOutOfBoundsException("length=" + this.length + "; index=" + i);
    }

    public void addAt(int i, int i2) {
        if (i < this.length) {
            this.array[i] = i2;
        } else {
            this.length = i;
            add(i2);
        }
    }

    public void add(int i) {
        int i2 = this.length;
        int i3 = i2 + 1;
        ensureCapacity(i3);
        this.array[i2] = i;
        this.length = i3;
    }

    private int calculateCapacity(int i) {
        int length = this.array.length;
        if (length >= i) {
            return 0;
        }
        int i2 = length * 2;
        return i > i2 ? i : i2;
    }

    private void ensureCapacity(int i) {
        int iM5568f = calculateCapacity(i);
        if (iM5568f > 0) {
            this.array = Arrays.copyOf(this.array, iM5568f);
        }
    }

    public int getLength() {
        return this.length;
    }

    public void setLength(int i) {
        ensureCapacity(i);
        this.length = i;
    }

    public void reset(int i) {
        this.array = new int[i];
        this.length = 0;
    }

    public int[] getPrimitiveArray() {
        return this.array;
    }

    /**
     * Adopts {@code other}'s backing array <b>by reference</b>: after this call both lists share
     * storage, so an {@link #add} on either one can be observed through the other. Use
     * {@link #copy(IntArrayList)} whenever the two lists must stay independent.
     */
    public void setSharingBackingArray(IntArrayList c0865af) {
        this.array = c0865af.array;
        this.length = c0865af.length;
    }

    /** Deep-copies {@code other}'s contents into this list; the two lists stay independent. */
    public void copy(IntArrayList c0865af) {
        int iM5568f = calculateCapacity(c0865af.length);
        if (iM5568f > 0) {
            this.array = new int[iM5568f];
        }
        System.arraycopy(c0865af.array, 0, this.array, 0, c0865af.length);
        this.length = c0865af.length;
    }

    public void append(IntArrayList c0865af, int i, int i2) {
        if (i2 == 0) {
            return;
        }
        int i3 = this.length;
        int i4 = i3 + i2;
        ensureCapacity(i4);
        System.arraycopy(c0865af.array, i, this.array, i3, i2);
        this.length = i4;
    }

    public void dropFirst(int i) {
        int[] iArr = this.array;
        System.arraycopy(iArr, i, iArr, 0, this.length - i);
        this.length -= i;
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
