/**
 * Composing text state: tracks the in-progress word being typed, its coordinate history,
 * recorrection state, and multi-tap highlight spans.
 *
 * <p>This nested subpackage under {@code core.textinput} holds the state owned by the
 * composing pipeline — classes that are implementation details of {@link
 * dev.bbkb.ime.core.textinput.InputLogic} and its controllers, not
 * general-purpose utilities.
 *
 * <h3>Key classes</h3>
 * <ul>
 *   <li>{@link dev.bbkb.ime.core.textinput.composing.ComposingTextTracker} —
 *       central composing state owner: typed text buffer, cursor position, case stats,
 *       shift state, NuanceSDK interaction, and language-processor dispatch. The primary
 *       collaborator of {@code InputLogic}.</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.composing.RecorrectionState} —
 *       holds the original-word / current-word snapshot used during recorrection and
 *       revert-auto-correction. Extracted from {@code ComposingTextTracker} in Phase 4.7.</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker} —
 *       parallel arrays of per-pointer coordinate samples (x, y, timestamp, code point,
 *       intentional flag) accumulated during a touch or gesture event and passed to
 *       NuanceSDK for gesture-word recognition.</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.composing.TouchHighlightTracker} —
 *       manages the background-highlight span applied to a key during multi-tap sequences.
 *       Renamed from {@code TouchEventProcessor}.</li>
 *   <li>{@link dev.bbkb.ime.core.textinput.composing.CommitEventRecord} —
 *       immutable record of a single commit event: the committed text, the original typed word
 *       (before auto-correction), the separator, the previous-words context, and a flag
 *       controlling revert eligibility. Used by {@code InputLogic.revertAutoCorrection()} and the
 *       learning subsystem. Renamed from {@code InputEventDispatcher}. It lived in a sibling
 *       {@code commit} package until §5.3 merged the two: {@code ComposingTextTracker} is its
 *       factory ({@code createEventDispatcher()}) and it embeds this package's
 *       {@code TouchPointerCoordTracker}, so the package line ran straight through one unit and
 *       produced a two-way import cycle for three types, one of which was dead.</li>
 * </ul>
 *
 * <h3>Access</h3>
 * <p>This package is <em>not</em> private to the text-editing subsystem, and a previous version of
 * this file claiming otherwise was simply wrong — there are external importers today in
 * {@code core.engine}, {@code core.inputmethod}, {@code core.spellcheck} and
 * {@code core.suggestion}. What they reach for is the composing word and the coordinate samples
 * behind it: {@code ComposingTextTracker} (15 sites) and {@code TouchPointerCoordTracker} (11),
 * with {@code CommitEventRecord} (5), {@code TouchHighlightTracker} (3) and
 * {@code RecorrectionState} (1) behind them.
 *
 * <p>Narrowing that to a read-only view of the composing word would be worth doing, and it is what
 * the aspirational version of this note wanted — but it is a design change, not a package move, and
 * it is deliberately out of scope here. Until it happens, treat the classes below as a published
 * surface: read them freely, and think hard before mutating one from outside
 * {@code core.textinput}, because {@code ComposingTextTracker} is one of the documented copies of
 * the current word (see {@code docs/2026-07_state-sync-matrix_reference.md}).
 */
package dev.bbkb.ime.core.textinput.composing;
