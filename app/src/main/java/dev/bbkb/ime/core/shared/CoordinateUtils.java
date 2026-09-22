package dev.bbkb.ime.core.shared;



public final class CoordinateUtils {
    public static int[] newCoordinateArray() {
        return new int[2];
    }

    public static int x(int[] iArr) {
        return iArr[0];
    }

    public static int y(int[] iArr) {
        return iArr[1];
    }

    public static void set(int[] iArr, int i, int i2) {
        iArr[0] = i;
        iArr[1] = i2;
    }

    public static void copy(int[] iArr, int[] iArr2) {
        iArr[0] = iArr2[0];
        iArr[1] = iArr2[1];
    }

    public static int[] newCoordinateArray(int i) {
        return new int[i * 2];
    }

    public static int[] newCoordinateArray(int i, int i2, int i3) {
        int[] iArr = new int[i * 2];
        for (int i4 = 0; i4 < i; i4++) {
            setXYInArray(iArr, i4, i2, i3);
        }
        return iArr;
    }

    public static int xFromArray(int[] iArr, int i) {
        return iArr[(i * 2) + 0];
    }

    public static int yFromArray(int[] iArr, int i) {
        return iArr[(i * 2) + 1];
    }

    public static void setXYInArray(int[] iArr, int i, int i2, int i3) {
        int i4 = i * 2;
        iArr[i4 + 0] = i2;
        iArr[i4 + 1] = i3;
    }
}
