package dev.bbkb.ime.personaldictionary.tokenizer

import android.icu.text.BreakIterator
import android.view.textservice.TextInfo
import java.util.ArrayList
import java.util.Locale

/** Splits text into words with the ICU BreakIterator (spell checker). */
class BlackBerryTokenizer(locale: Locale) {
    private val breakIterator: BreakIterator = BreakIterator.getWordInstance(locale)

    private fun getWordItem(textInfo: TextInfo, text: String, start: Int, end: Int, includeWhitespace: Boolean): SentenceWordItem? {
        val substring = text.substring(start, end)
        return if (includeWhitespace || substring.trim().isNotEmpty()) {
            SentenceWordItem(
                TextInfo(substring, 0, substring.length, textInfo.cookie, substring.hashCode()),
                start,
                end
            )
        } else null
    }

    @JvmOverloads
    fun split(textInfo: TextInfo, includeWhitespace: Boolean = false): SentenceTextInfoParams {
        val hasNext: Boolean
        var current: Int
        var previous = 0
        // Read once: TextInfo.getText() copies the whole text on every call.
        val text = textInfo.text
        
        breakIterator.setText(text)
        current = breakIterator.next()
        hasNext = current != -1

        val items = ArrayList<SentenceWordItem>()
        val length = text.length
        
        var nextCurrent = current
        var isRunning = hasNext

        while (isRunning) {
            val wordItem = getWordItem(textInfo, text, previous, nextCurrent, includeWhitespace)
            if (wordItem != null) {
                items.add(wordItem)
            }
            
            val next = breakIterator.next()
            isRunning = next != -1
            previous = nextCurrent
            nextCurrent = next
        }

        if (previous < length) {
            val wordItem = getWordItem(textInfo, text, previous, length, includeWhitespace)
            if (wordItem != null) {
                items.add(wordItem)
            }
        }

        return SentenceTextInfoParams(textInfo, items)
    }

    fun split(text: String): SentenceTextInfoParams {
        return split(TextInfo(text), false)
    }

    companion object {
        private const val TAG = "BlackBerryTokenizer"

        @JvmStatic
        fun toSequence(params: SentenceTextInfoParams): List<String> {
            val list = ArrayList<String>()
            for (item in params.items) {
                list.add(item.textInfo.text)
            }
            return list
        }
    }
}
