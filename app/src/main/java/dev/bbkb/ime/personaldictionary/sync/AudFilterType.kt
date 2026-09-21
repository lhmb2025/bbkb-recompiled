package dev.bbkb.ime.personaldictionary.sync

import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.macro.DynamicMacroType
import dev.bbkb.ime.personaldictionary.model.WordSubstitution

/**
 * Predefined filters for excluding words from AUD synchronization.
 *
 * - [DYNAMIC_WORD_SUBSTITUTION]: Excludes substitutions containing dynamic macros (`%D`, `%T`, etc.)
 * - [TOO_LONG_WORD_SUBSTITUTION]: Excludes words/keys exceeding AUD's 48-character limit
 */
enum class AudFilterType(val filter: AudFilter) {
    DYNAMIC_WORD_SUBSTITUTION(AudFilter { dictionaryWord ->
        if (dictionaryWord is WordSubstitution) {
            val word = dictionaryWord.word
            for (type in DynamicMacroType.values()) {
                if (word.contains(type.tag)) {
                    return@AudFilter true
                }
            }
        }
        false
    }),
    
    TOO_LONG_WORD_SUBSTITUTION(AudFilter { dictionaryWord ->
        if (dictionaryWord.word.length > PersonalDictionaryConstants.AUD_MAX_LENGTH) {
            return@AudFilter true
        }
        if (dictionaryWord is WordSubstitution
            && dictionaryWord.key.length > PersonalDictionaryConstants.AUD_MAX_LENGTH) {
            return@AudFilter true
        }
        false
    });

    companion object {
        val TAG: String = AudFilterType::class.java.simpleName
    }
}
