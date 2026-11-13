package dev.bbkb.ime.keyboard

/** How a theme style paints the key surfaces. */
enum class KeyCap {
    /** Full-bleed rects with hairline edge dividers and an opaque pressed fill. */
    FLAT,
    /** Inset rounded caps with flat fills and a translucent pressed state layer. */
    ROUNDED,
    /** Inset caps with a top-lit gradient, dark border and top highlight; opaque blue press fill. */
    SCULPTED,
}

/**
 * Everything a theme style controls besides colors ([KeyboardColorManager.Palette]).
 * Renderers read [KeyboardColorManager.styleSpec] instead of branching on the style.
 * Read at draw time: a theme switch recreates the input view.
 */
data class StyleSpec(
    val keyCap: KeyCap,
    /** Cap inset from the key cell, in dp (the gap between caps is twice this); 0 = full bleed. */
    val keyInsetHDp: Float,
    val keyInsetVDp: Float,
    /** Cap corner radius, in dp. Unused for [KeyCap.FLAT]. */
    val keyRadiusDp: Float,
    /** Key label size, as a fraction of the legacy label ratio. */
    val labelSizeScale: Float,
    /** Medium-weight (500) labels, also used by the preview bubble and on-key flick predictions. */
    val mediumKeyTypeface: Boolean,
    /** On-key flick prediction text size, as a fraction of the keyboard letter size. */
    val predictionTextScale: Float,
    /** On-key flick predictions get a drop shadow, for legibility over sculpted caps. */
    val predictionDropShadow: Boolean,
    /**
     * Height in dp of the full-width "fret" bars between key rows and at the top edge; 0 disables.
     * Drawn only by views that opt in via KeyboardView.drawsFrets (the main board).
     */
    val fretHeightDp: Float,
    /** Spacebar autocorrect word uses the palette accent instead of autoCorrectionOnSpacebarTextColor. */
    val accentSpacebarPrediction: Boolean,
    /** Enter/action key drawn as an accent-filled pill with a background-tinted icon. */
    val accentEnterKey: Boolean,
    /** The legacy bar icon on the spacebar. */
    val drawSpacebarIcon: Boolean,
    /** Emoji-page keys get caps. Read only by the cap painters; the flat painter never draws them. */
    val emojiCaps: Boolean,
    /** Key-preview bubble corner radius, in dp; 0 = square bubble. */
    val previewRadiusDp: Float,
    /** Key-preview bubble elevation, in dp. */
    val previewElevationDp: Float,
    /**
     * Modern board treatment: boards on the background surface, rounded translucent press layer,
     * clipboard cards, trackpad FCC, emphasized 48dp suggestion strip, flat aux bars.
     */
    val modernBoards: Boolean,
) {
    companion object {
        /** Classic/Modern: full-bleed rendering everywhere. */
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

        /** Material: floating caps, smaller medium-weight labels, accent enter pill, elevated preview. */
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
         * BB10: sculpted caps, fret bars, shadowed predictions, accent spacebar word.
         * Geometry: row gap (~2dp) + 2x insetV = fret + two ~2dp seams; 2x insetH matches the seam.
         */
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
