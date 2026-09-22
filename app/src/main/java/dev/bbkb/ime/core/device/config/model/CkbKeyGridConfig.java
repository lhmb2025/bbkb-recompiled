package dev.bbkb.ime.core.device.config.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-device capacitive-keyboard letter grid, parsed from a {@code <ckb-key-grid>} block in a
 * device config XML. Lets us inject known geometry for any device without reading the live ET9
 * engine. Cells are normalized [0,1] within the keypad rect, so they're resolution-independent.
 *
 * <p>This serves the gesture arbiter's key-topology check and the Gesture Lab overlay — it is NOT
 * fed to ET9/Nuance (which computes its own rectangles from its built-in keyboard database).</p>
 */
public class CkbKeyGridConfig {

    /** Keypad coordinate-space dimensions (informational; cells are already normalized). */
    public int width;
    public int height;

    public final List<Cell> cells = new ArrayList<>();

    public static class Cell {
        public final String label;
        public final float cx;
        public final float cy;
        public final float w;
        public final float h;

        public Cell(String label, float cx, float cy, float w, float h) {
            this.label = label;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }
    }
}
