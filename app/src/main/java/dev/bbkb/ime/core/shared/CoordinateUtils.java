package dev.bbkb.ime.core.shared;



public final class CoordinateUtils {
    public static int[] newCoordinateArray() {
        return new int[2];
    }

    public static int x(int[] coords) {
        return coords[0];
    }

    public static int y(int[] coords) {
        return coords[1];
    }

    public static void set(int[] coords, int x, int y) {
        coords[0] = x;
        coords[1] = y;
    }

    public static void copy(int[] destination, int[] source) {
        destination[0] = source[0];
        destination[1] = source[1];
    }

    public static int[] newCoordinateArray(int arraySize) {
        return new int[arraySize * 2];
    }

    public static int[] newCoordinateArray(int arraySize, int defaultX, int defaultY) {
        int[] iArr = new int[arraySize * 2];
        for (int i4 = 0; i4 < arraySize; i4++) {
            setXYInArray(iArr, i4, defaultX, defaultY);
        }
        return iArr;
    }

    public static int xFromArray(int[] coordsArray, int index) {
        return coordsArray[(index * 2) + 0];
    }

    public static int yFromArray(int[] coordsArray, int index) {
        return coordsArray[(index * 2) + 1];
    }

    public static void setXYInArray(int[] coordsArray, int index, int x, int y) {
        int i4 = index * 2;
        coordsArray[i4 + 0] = x;
        coordsArray[i4 + 1] = y;
    }
}
