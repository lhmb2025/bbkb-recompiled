package dev.bbkb.ime.personaldictionary.tokenizer

import android.view.textservice.TextInfo

/** One token: its [TextInfo] and its [start] offset in the original text. */
class SentenceWordItem(
    @JvmField val textInfo: TextInfo,
    @JvmField val start: Int,
    end: Int
) {
    @JvmField val length: Int = end - start
}
