/**
 * Per-language composition processors (Vietnamese Telex {@link dev.bbkb.ime.core.inputmethod.TextComposer},
 * Korean {@link dev.bbkb.ime.core.inputmethod.HangulInputProcessor}, Japanese Romaji
 * {@link dev.bbkb.ime.core.inputmethod.RomajiInputProcessor}) on
 * {@link dev.bbkb.ime.core.inputmethod.AbstractInputProcessor}, which owns the undo history and
 * touch-coordinate realignment. {@code ComposingTextTracker} picks one per subtype from
 * {@code CONVERTER_REGISTRY}.
 */
package dev.bbkb.ime.core.inputmethod;
