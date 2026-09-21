package dev.bbkb.ime.personaldictionary.model

import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import java.io.File

/**
 * The three on-disk personal-dictionary stores.
 *
 * PD-28: the filenames are the persistence contract, so they are named once in
 * [PersonalDictionaryConstants] rather than spelled as literals here as well.
 */
enum class LoadConfig(val filename: String) {
    WORD_SUBSTITUTIONS(PersonalDictionaryConstants.WORD_SUBSTITUTION_FILE),
    DELETED_WORD_SUBSTITUTIONS(PersonalDictionaryConstants.DELETED_SUBSTITUTIONS_FILE),
    PERSONAL_WORDS(PersonalDictionaryConstants.PERSONAL_WORDS_FILE);

    fun getFile(dir: File): File {
        return File(dir, filename)
    }

    override fun toString(): String {
        return "LoadConfig{mFilename='$filename'}"
    }
}
