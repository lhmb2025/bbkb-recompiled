package dev.bbkb.ime.personaldictionary.tokenizer

import android.view.textservice.TextInfo

/**
 * Represents a single word within a tokenized sentence.
 *
 * @property textInfo The TextInfo for this word segment
 * @property start Start position in the original text
 * @property length Length of this word segment
 */
class SentenceWordItem(
    @JvmField val textInfo: TextInfo,
    @JvmField val start: Int,
    end: Int
) {
    @JvmField val length: Int = end - start
}
