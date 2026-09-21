package dev.bbkb.ime.personaldictionary.model

import androidx.annotation.VisibleForTesting
import com.google.gson.annotations.SerializedName

/**
 * Represents a user-added personal dictionary word.
 *
 * Personal words are added to the dictionary to improve prediction/correction
 * for terms not in the standard dictionary (names, jargon, etc.).
 *
 * @see DictionaryWord
 */
class PersonalWord @VisibleForTesting constructor(
    word: String,
    locale: String,
    autoCapsEnabled: Boolean
) : DictionaryWord(word, locale, autoCapsEnabled) {

    @SerializedName("version")
    val version: Long = 1L

    override fun toString(): String {
        return "PersonalWord{mWord='$word', mLocale='$locale', mAutoCapsEnabled=$isAutoCapsEnabled}"
    }

    companion object {
        const val serialVersionUID = 1L
    }
}
