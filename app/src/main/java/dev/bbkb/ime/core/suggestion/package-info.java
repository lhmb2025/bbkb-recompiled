/**
 * Suggestion pipeline: asks the engine for candidates ({@link dev.bbkb.ime.core.suggestion.SuggestionEngine}),
 * runs requests on the engine thread ({@link dev.bbkb.ime.core.suggestion.SuggestionRequestQueue}) and delivers
 * {@link dev.bbkb.ime.core.suggestion.SuggestedWords} to the strip. Also attaches system correction spans to
 * committed words ({@link dev.bbkb.ime.core.suggestion.SuggestionSpanBuilder}) and learns picks from them
 * ({@link dev.bbkb.ime.core.suggestion.SuggestionSpanReceiver}).
 */
package dev.bbkb.ime.core.suggestion;
