/**
 * Language-specific input processors for multi-character composition (Vietnamese, Korean,
 * Japanese Romaji).
 *
 * <p>This package provides the {@link dev.bbkb.ime.core.inputmethod.AbstractInputProcessor}
 * base class and its concrete language-specific subclasses. Each processor owns the
 * character-by-character undo stack and the NuanceSDK symbol-feed loop for its language.
 *
 * <h3>Key classes</h3>
 * <ul>
 *   <li>{@link dev.bbkb.ime.core.inputmethod.InputMethodCallback} — interface
 *       defining the contract between the composing pipeline and a language processor:
 *       reset, process text, build word, and report language type.</li>
 *   <li>{@link dev.bbkb.ime.core.inputmethod.AbstractInputProcessor} — base
 *       implementation managing the character undo stack and
 *       {@link dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker}
 *       instances used for gesture input.</li>
 *   <li>{@link dev.bbkb.ime.core.inputmethod.TextComposer} — Vietnamese Telex
 *       input processor; feeds diacritic-resolved characters into NuanceSDK.</li>
 *   <li>{@link dev.bbkb.ime.core.inputmethod.HangulInputProcessor} — Korean
 *       (Hangul) syllable-block composition processor; decomposes syllables and feeds
 *       individual jamo into NuanceSDK. Renamed from {@code InputModeManager}.</li>
 *   <li>{@link dev.bbkb.ime.core.inputmethod.RomajiInputProcessor} — Japanese
 *       Romaji inline-word resolver; compares typed text against NuanceSDK's inline
 *       prediction and returns the better candidate. Renamed from {@code CursorPosition}.</li>
 * </ul>
 *
 * <h3>Integration point</h3>
 * <p>Processors are instantiated and managed by
 * {@link dev.bbkb.ime.core.textinput.composing.ComposingTextTracker} via the
 * {@code CONVERTER_REGISTRY} map. {@code ComposingTextTracker} selects the active processor
 * based on the current input subtype and delegates composition and word-building calls to it.
 */
package dev.bbkb.ime.core.inputmethod;
