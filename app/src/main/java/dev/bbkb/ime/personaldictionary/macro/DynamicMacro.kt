package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import java.util.Locale

/** Produces a macro's text at expansion time. */
interface DynamicMacro {
    fun getDynamicText(context: Context, locale: Locale): String?
    
    companion object {
        const val TAG = "DynamicMacro"
    }
}
