package dev.bbkb.ime.personaldictionary.tokenizer

import android.icu.text.BreakIterator
import android.os.Build
import android.view.textservice.TextInfo
import java.util.ArrayList
import java.util.Locale

/**
 * Tokenizes text into words using ICU or Java BreakIterator.
 *
 * Used by spell-checking and text analysis components.
 * Selects ICU BreakIterator on API 24+ for better Unicode support.
 */
class BlackBerryTokenizer(locale: Locale) {
    private var breakIterator: BreakIterator? = null
    private var textBreakIterator: java.text.BreakIterator? = null

    init {
        if (Build.VERSION.SDK_INT >= 24) {
            breakIterator = BreakIterator.getWordInstance(locale)
        } else {
            textBreakIterator = java.text.BreakIterator.getWordInstance(locale)
        }
    }

    private fun getWordItem(textInfo: TextInfo, start: Int, end: Int, includeWhitespace: Boolean): SentenceWordItem? {
        val substring = textInfo.text.substring(start, end)
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
        
        if (Build.VERSION.SDK_INT >= 24) {
            breakIterator!!.setText(textInfo.text)
            current = breakIterator!!.next()
            hasNext = current != -1
        } else {
            textBreakIterator!!.setText(textInfo.text)
            current = textBreakIterator!!.next()
            hasNext = current != -1
        }

        val items = ArrayList<SentenceWordItem>()
        val length = textInfo.text.length
        
        var nextCurrent = current
        var isRunning = hasNext

        while (isRunning) {
            val wordItem = getWordItem(textInfo, previous, nextCurrent, includeWhitespace)
            if (wordItem != null) {
                items.add(wordItem)
            }
            
            if (Build.VERSION.SDK_INT >= 24) {
                val next = breakIterator!!.next()
                isRunning = next != -1
                previous = nextCurrent
                nextCurrent = next
            } else {
                val next = textBreakIterator!!.next()
                isRunning = next != -1
                previous = nextCurrent
                nextCurrent = next
            }
        }

        if (previous < length) {
            val wordItem = getWordItem(textInfo, previous, length, includeWhitespace)
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
