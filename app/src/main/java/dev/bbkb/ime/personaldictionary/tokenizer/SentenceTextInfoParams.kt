package dev.bbkb.ime.personaldictionary.tokenizer

import android.view.textservice.TextInfo

/** The original [TextInfo] and its [SentenceWordItem]s. */
class SentenceTextInfoParams(
    @JvmField val originalTextInfo: TextInfo,
    @JvmField val items: ArrayList<SentenceWordItem>
) {
    @JvmField val size: Int = items.size
}
