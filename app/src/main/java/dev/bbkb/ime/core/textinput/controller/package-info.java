/**
 * Input controllers that talk to the pipeline only through public methods and small callbacks.
 *
 * <p>{@code CommitController}, {@code BackspaceController}, {@code RecorrectionController} and
 * {@code SuggestionCoordinator} stay in the parent package because they use about 24
 * package-private members of {@code InputLogic}; moving them would mean making those public.
 * Narrow interfaces onto {@code InputLogic} would let them move.
 */
package dev.bbkb.ime.core.textinput.controller;
