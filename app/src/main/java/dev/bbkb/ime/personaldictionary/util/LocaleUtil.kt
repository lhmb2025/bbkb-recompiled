package dev.bbkb.ime.personaldictionary.util

import android.content.Context
import android.view.inputmethod.InputMethodManager
import dev.bbkb.ime.R
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudLocaleException
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.TreeSet

/** Locale string parsing and conversion between BASL and AUD locale formats. */
object LocaleUtil {
    /** Hoisted: parse() runs once per substitution and per AUD row. */
    private val SEPARATORS_REGEX = Regex("_|-")

    fun convertAudLocaleToBaslLocale(audLocale: String?): String {
        return audLocale ?: PersonalDictionaryConstants.LOCALE_ALL
    }

    @JvmStatic
    @Throws(AudLocaleException::class)
    fun parse(localeString: String?): Locale? {
        if (localeString == null) {
            return null
        }
        val parts = localeString.split(SEPARATORS_REGEX)
        val builder = Locale.Builder()
        return try {
            when (parts.size) {
                3 -> {
                    builder.setVariant(parts[2])
                    builder.setRegion(parts[1])
                    builder.setLanguage(parts[0])
                    builder.build()
                }
                2 -> {
                    builder.setRegion(parts[1])
                    builder.setLanguage(parts[0])
                    builder.build()
                }
                1 -> {
                    builder.setLanguage(parts[0])
                    builder.build()
                }
                else -> throw AudLocaleException("Cannot parse locale: $localeString")
            }
        } catch (e: IllformedLocaleException) {
            // Unchecked from Locale.Builder; the input is third-party AUD data, so map it to the
            // declared exception.
            throw AudLocaleException("Cannot parse locale: $localeString")
        }
    }

    fun convertBaslLocaleToAudLocale(baslLocale: String): String? {
        return if (PersonalDictionaryConstants.LOCALE_ALL == baslLocale) {
            null
        } else {
            baslLocale
        }
    }

    fun isLocaleStringInLocaleList(locales: List<Locale>, localeString: String): Boolean {
        require(locales.isNotEmpty()) { "Locales list must not be empty" }
        for (l in locales) {
            if (localeString == l.toString()) {
                return true
            }
        }
        return false
    }

    /** The current input locale plus "" for "All languages". */
    @JvmStatic
    fun getAvailableLocaleStrings(context: Context): TreeSet<String> {
        val treeSet = TreeSet<String>()
        val currentLocale = getCurrentInputMethodLocale(context)
        if (currentLocale != null) {
            treeSet.add("")
            treeSet.add(currentLocale)
        }
        return treeSet
    }

    @JvmStatic
    fun getAvailableLocales(context: Context): List<Locale> {
        val list = mutableListOf<Locale>()
        val currentLocale = getCurrentInputMethodLocale(context)
        if (currentLocale != null) {
            parse(currentLocale)?.let { list.add(it) }
        }
        return list
    }

    private fun getCurrentInputMethodLocale(context: Context): String? {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return null
        val currentSubtype = inputMethodManager.currentInputMethodSubtype ?: return null
        return currentSubtype.languageTag
    }

    /** The locale's display name, or "All languages" when empty. */
    @JvmStatic
    fun getDisplayNameForDictionary(context: Context, localeString: String?): String {
        return if (localeString.isNullOrEmpty()) {
            context.resources.getString(R.string.user_dict_settings_all_languages)
        } else {
            val displayLocale = androidx.core.os.ConfigurationCompat
                .getLocales(context.resources.configuration)
                .get(0) ?: Locale.getDefault()
            parse(localeString)?.getDisplayName(displayLocale) ?: localeString
        }
    }
}
