package dev.bbkb.ime.personaldictionary.sync

import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.macro.DynamicMacroType
import dev.bbkb.ime.personaldictionary.model.WordSubstitution

/**
 * Words excluded from AUD sync: [DYNAMIC_WORD_SUBSTITUTION] (dynamic macros such as `%D`) and
 * [TOO_LONG_WORD_SUBSTITUTION] (word or key over AUD's 48-character limit).
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
