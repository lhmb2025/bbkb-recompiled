package dev.bbkb.ime.personaldictionary.tokenizer

import android.view.textservice.TextInfo

/**
 * Container for tokenized sentence data.
 *
 * Holds the original [TextInfo] and the list of [SentenceWordItem]s produced by tokenization.
 */
class SentenceTextInfoParams(
    @JvmField val originalTextInfo: TextInfo,
    @JvmField val items: ArrayList<SentenceWordItem>
) {
    @JvmField val size: Int = items.size
}
