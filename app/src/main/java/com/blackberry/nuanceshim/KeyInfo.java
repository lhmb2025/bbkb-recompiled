package com.blackberry.nuanceshim;

/**
 * KeyInfo - Data structure for keyboard key position and metadata
 * Stores key bounds (left, top, right, bottom), center coordinates (x, y), and keyCode.
 * Used by NuanceSDK for tap position to key mapping.
 */

public class KeyInfo {
    public int bottom;
    public int keyCode;
    public int left;
    public int right;
    public int top;

    public int x;

    public int y;
}
