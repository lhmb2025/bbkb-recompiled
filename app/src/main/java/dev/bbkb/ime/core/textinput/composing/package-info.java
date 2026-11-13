/**
 * Composing text state: the in-progress word, its coordinate history, recorrection state and
 * multi-tap highlight spans. {@link dev.bbkb.ime.core.textinput.composing.ComposingTextTracker}
 * is the central owner and the primary collaborator of {@code InputLogic}.
 *
 * <p>Other packages read these classes (the composing word and its coordinate samples). Think hard
 * before mutating one from outside {@code core.textinput}: the tracker is one of the copies of the
 * current word that must stay in sync.
 */
package dev.bbkb.ime.core.textinput.composing;
