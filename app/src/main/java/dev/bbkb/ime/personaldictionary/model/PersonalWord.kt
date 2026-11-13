package dev.bbkb.ime.personaldictionary.model

import androidx.annotation.VisibleForTesting

/** A word the user added to the personal dictionary. */
class PersonalWord @VisibleForTesting constructor(
    word: String,
    locale: String,
    autoCapsEnabled: Boolean
) : DictionaryWord(word, locale, autoCapsEnabled) {

    val version: Long = 1L

    override fun toString(): String {
        return "PersonalWord{mWord='$word', mLocale='$locale', mAutoCapsEnabled=$isAutoCapsEnabled}"
    }

    companion object {
        const val serialVersionUID = 1L
    }
}
