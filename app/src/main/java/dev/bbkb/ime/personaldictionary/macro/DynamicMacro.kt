package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import java.util.Locale

/**
 * Interface for dynamic macro handlers that generate runtime text.
 *
 * Implementations provide locale-aware dynamic content (date, time, version, etc.)
 * for word substitution expansion.
 */
interface DynamicMacro {
    fun getDynamicText(context: Context, locale: Locale): String?
    
    companion object {
        const val TAG = "DynamicMacro"
    }
}
