package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import java.util.Locale

/**
 * Evaluates dynamic macro tags within word substitutions.
 *
 * Replaces macro placeholders (e.g., `%D`, `%T`) with runtime-generated
 * content when a substitution is expanded. Also expands user-defined
 * custom macros.
 */
object DynamicContentHandler {
    private const val TAG = "DynamicContentHandler"

    fun handleDynamicContent(context: Context, ws: WordSubstitution, locale: Locale): String {
        var word = ws.word
        if (word.contains("%")) {
            // 1. Expand built-in macros
            // `entries` is the allocation-free view; `values()` clones the backing
            // array on every call, and this runs once per substitution expansion.
            for (type in DynamicMacroType.entries) {
                if (!word.contains("%")) break
                word = evaluateMacro(context, ws, type, word, locale)
            }
            // 2. Expand custom user-defined macros
            word = expandCustomMacros(context, word)
        }
        return word
    }
    
    /**
     * Expands user-defined custom macros in the text.
     */
    private fun expandCustomMacros(context: Context, text: String): String {
        if (!text.contains("%")) return text
        
        var result = text
        val customMacros = CustomMacroRepository(context).getAllMacros()
        for (macro in customMacros) {
            result = result.replace(macro.fullTag, macro.value)
        }
        return result
    }

    @VisibleForTesting
    fun evaluateMacro(
        context: Context,
        ws: WordSubstitution,
        type: DynamicMacroType,
        text: String,
        locale: Locale
    ): String {
        val tag = type.tag
        if (!text.contains(tag)) {
            return text
        }
        val dynamicText = type.handler.getDynamicText(context, locale)
        if (!dynamicText.isNullOrEmpty()) {
            return text.replace(tag, dynamicText)
        }
        if (!type.canExpectNullOrEmpty) {
            LogUtil.e(TAG, "Expected that the macro would be expanded but null/empty was returned so replacing tag with WS key, locale: $locale, macroType: $type")
        }
        return text.replace(tag, ws.key)
    }
}
