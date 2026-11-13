package dev.bbkb.ime.core.device.config.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A device's {@code <ckb-key-grid>} letter grid, cells normalized [0,1] within the keypad rect. Used
 * by the gesture arbiter's key-topology check and the Gesture Lab overlay; never fed to the engine.
 */
public class CkbKeyGridConfig {
    /** Keypad coordinate space (informational; cells are already normalized). */
    public int width;
    public int height;

    public final List<Cell> cells = new ArrayList<>();

    public static class Cell {
        public final String label;
        public final float cx;
        public final float cy;
        public final float w;
        public final float h;

        /**
         * @param cx normalized center x
         * @param cy normalized center y
         * @param w normalized width
         * @param h normalized height
         */
        public Cell(String label, float cx, float cy, float w, float h) {
            this.label = label;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }
    }
}
