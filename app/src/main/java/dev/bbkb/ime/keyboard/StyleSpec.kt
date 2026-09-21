package dev.bbkb.ime.keyboard

/** How a theme style paints the key surfaces. */
enum class KeyCap {
    /** Full-bleed rects with hairline edge dividers and an opaque pressed fill. */
    FLAT,
    /** Inset rounded caps with flat fills and a translucent pressed state layer. */
    ROUNDED,
    /**
     * Inset rounded caps with a sculpted finish — top-lit vertical gradient, dark
     * grounding border, thin top inner highlight — and an opaque re-sculpted
     * pressed fill (the BB10 blue press). See KeyboardView.drawSculptedKeyBackground.
     */
    SCULPTED,
}

/**
 * Everything a theme style controls besides colors (colors are
 * [KeyboardColorManager.Palette]). One immutable instance per
 * [KeyboardColorManager.Style]: renderers read fields off
 * [KeyboardColorManager.styleSpec] instead of branching on the style, so adding a
 * styled theme means writing a new spec — not another branch in every view.
 *
 * Like the palette, the spec is pull-at-draw: a theme switch recreates the input
 * view, so draw/inflate-path reads always see the active style.
 */
data class StyleSpec(
    /** Key-surface painter family. */
    val keyCap: KeyCap,
    /**
     * Visible cap inset from the key cell bounds, in dp; the gap between neighboring
     * caps is twice this. 0 = full bleed ([KeyCap.FLAT]).
     */
    val keyInsetHDp: Float,
    val keyInsetVDp: Float,
    /** Cap corner radius, in dp. Unused for [KeyCap.FLAT]. */
    val keyRadiusDp: Float,
    /** Key label size, as a fraction of the legacy label ratio. */
    val labelSizeScale: Float,
    /**
     * Medium-weight (500) key labels, matched by the preview bubble and the on-key
     * flick predictions. See [KeyboardView.mediumWeightTypeface].
     */
    val mediumKeyTypeface: Boolean,
    /** On-key flick prediction text size, as a fraction of the keyboard letter size. */
    val predictionTextScale: Float,
    /**
     * On-key flick predictions cast a drop shadow beneath the text (the BB10 look
     * — floating words kept legible over the sculpted caps). Other styles render
     * clean unshadowed text.
     */
    val predictionDropShadow: Boolean,
    /**
     * Height, in dp, of the horizontal "fret" bars drawn across the full board
     * width in the gaps between key rows (and hugging the top edge) — the BB10
     * signature. 0 disables frets. Drawn only by views that opt in via
     * KeyboardView.drawsFrets (the main board, not popups/aux bars/emoji pages).
     */
    val fretHeightDp: Float,
    /**
     * The autocorrect word drawn on the spacebar uses the palette accent instead of
     * the static autoCorrectionOnSpacebarTextColor theme attr (BB10's blue
     * spacebar word).
     */
    val accentSpacebarPrediction: Boolean,
    /** Enter/action key drawn as an accent-filled pill with a background-tinted icon. */
    val accentEnterKey: Boolean,
    /** The legacy bar icon on the spacebar. */
    val drawSpacebarIcon: Boolean,
    /**
     * Emoji-page keys draw their own key caps (each emoji floats on a cap).
     * Consulted only by the cap painters — the legacy flat painter never gives
     * "empty" keys a surface. False = bare glyphs on the board.
     */
    val emojiCaps: Boolean,
    /** Key-preview bubble corner radius, in dp. 0 = the legacy flat square bubble. */
    val previewRadiusDp: Float,
    /** Key-preview bubble elevation, in dp. */
    val previewElevationDp: Float,
    /**
     * The redesigned board/aux treatment introduced with the Material theme (2026-08
     * UI audit): boards sit on the background surface instead of a keyColor panel,
     * press feedback is a rounded translucent state layer, clipboard rows become
     * cards with the single-sided reveal, the FCC gets the trackpad layout, the
     * suggestion strip gets candidate emphasis and 48dp touch targets, and the aux
     * bars render as one flat surface.
     */
    val modernBoards: Boolean,
) {
    companion object {
        /** Classic/Modern: the original full-bleed rendering everywhere. */
        val LEGACY = StyleSpec(
            keyCap = KeyCap.FLAT,
            keyInsetHDp = 0f,
            keyInsetVDp = 0f,
            keyRadiusDp = 0f,
            labelSizeScale = 1f,
            mediumKeyTypeface = false,
            predictionTextScale = 1f,
            predictionDropShadow = false,
            fretHeightDp = 0f,
            accentSpacebarPrediction = false,
            accentEnterKey = false,
            drawSpacebarIcon = true,
            emojiCaps = false,
            previewRadiusDp = 0f,
            previewElevationDp = 0f,
            modernBoards = false,
        )

        /**
         * Material (K1-K4 of the 2026-08 UI audit): Gboard-like floating caps with
         * vertical gaps wider than horizontal, smaller medium-weight labels, the
         * accent enter pill, a rounded elevated preview bubble, and the redesigned
         * boards.
         */
        val MATERIAL = StyleSpec(
            keyCap = KeyCap.ROUNDED,
            keyInsetHDp = 3f,
            keyInsetVDp = 4.5f,
            keyRadiusDp = 8f,
            labelSizeScale = 0.8f,
            mediumKeyTypeface = true,
            predictionTextScale = 0.9f,
            predictionDropShadow = false,
            fretHeightDp = 0f,
            accentSpacebarPrediction = false,
            accentEnterKey = true,
            drawSpacebarIcon = false,
            emojiCaps = true,
            previewRadiusDp = 12f,
            previewElevationDp = 4f,
            modernBoards = true,
        )

        /**
         * BB10: the BlackBerry 10 virtual keyboard. Sculpted dark caps with tight
         * horizontal gaps, silver fret bars between the rows, full-size
         * medium-weight labels, drop-shadowed on-key predictions, the accent
         * (electric blue) autocorrect word on the spacebar, and the modern board
         * treatment carrying the BB10 palette.
         */
        // BB10 geometry, measured off BB10 screenshots and tuned on-screen: the fret
        // (~8.5% of the row pitch) sits in the vertical band with ~2dp dark seams
        // against the caps (structural ~2dp row gap + 2x insetV = fret + 2 seams),
        // and the horizontal key gap (2x insetH) matches that seam visually.
        // No cap border: key separation is the background showing through.
        val BB10 = StyleSpec(
            keyCap = KeyCap.SCULPTED,
            keyInsetHDp = 1f,
            keyInsetVDp = 3.75f,
            keyRadiusDp = 2.5f,
            labelSizeScale = 1f,
            mediumKeyTypeface = true,
            predictionTextScale = 0.85f,
            predictionDropShadow = true,
            fretHeightDp = 5.5f,
            accentSpacebarPrediction = true,
            accentEnterKey = false,
            drawSpacebarIcon = false,
            emojiCaps = false,
            previewRadiusDp = 6f,
            previewElevationDp = 2f,
            modernBoards = true,
        )
    }
}
