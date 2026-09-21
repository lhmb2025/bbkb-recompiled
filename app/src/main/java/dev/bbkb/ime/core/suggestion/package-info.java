/**
 * Suggestion pipeline: generates, ranks, and delivers word suggestions to the UI.
 *
 * <p>This package owns the full lifecycle of a suggestion request — from asking the
 * dictionary for candidates ({@link dev.bbkb.ime.core.suggestion.SuggestionEngine})
 * to formatting and delivering the result list
 * ({@link dev.bbkb.ime.core.suggestion.SuggestedWords}) to the suggestion strip.
 *
 * <h3>Key classes</h3>
 * <ul>
 *   <li>{@link dev.bbkb.ime.core.suggestion.SuggestionEngine} — retrieves
 *       suggestions from the dictionary layer, applies case transforms, and determines
 *       auto-correct eligibility. Renamed from {@code TextDecorator}.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.SuggestionRequestQueue} — runs
 *       suggestion requests on a background thread and posts results to the UI thread.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.SuggestedWords} — immutable value
 *       object holding the current suggestion list shown in the strip.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.PunctuationSuggestions} — specialized
 *       {@code SuggestedWords} subclass for the punctuation suggestion row. Renamed from
 *       {@code TextInputState}.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.SuggestionSpanBuilder} — attaches
 *       {@link android.text.style.SuggestionSpan} instances to committed words so Android's
 *       long-press correction popup is populated. Moved from {@code compat}.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.SuggestionSpanReceiver} — receives
 *       {@code android.text.style.SUGGESTION_PICKED} broadcasts when the user picks an
 *       alternative from the system correction popup.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.PrevWordsInfo} — n-gram context
 *       carrier: holds the previous word(s) used to seed prediction.</li>
 *   <li>{@link dev.bbkb.ime.core.suggestion.SuggestionResult} — lightweight
 *       container for raw suggestion data returned by the dictionary layer.</li>
 * </ul>
 *
 * <h3>Consumers outside this package</h3>
 * <ul>
 *   <li>{@code core.textinput.SuggestionCoordinator} — orchestrates async/sync requests
 *       and delivers results to the strip listener.</li>
 *   <li>{@code core.textinput.CommitController} — uses {@code SuggestionSpanBuilder} when
 *       committing words to attach correction alternatives.</li>
 *   <li>{@code core.spellcheck} — shares {@code SuggestionEngine} and the dictionary layer
 *       for spell-check sessions.</li>
 * </ul>
 */
package dev.bbkb.ime.core.suggestion;
