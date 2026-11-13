package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import java.util.Locale

/** Expands built-in macro tags (`%D`, `%T`, ...) and custom macros in a substitution's word. */
object DynamicContentHandler {
    private const val TAG = "DynamicContentHandler"

    fun handleDynamicContent(context: Context, ws: WordSubstitution, locale: Locale): String {
        var word = ws.word
        if (word.contains("%")) {
            // entries, not values(): values() clones the array on every expansion.
            for (type in DynamicMacroType.entries) {
                if (!word.contains("%")) break
                word = evaluateMacro(context, ws, type, word, locale)
            }
            word = expandCustomMacros(context, word)
        }
        return word
    }

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
