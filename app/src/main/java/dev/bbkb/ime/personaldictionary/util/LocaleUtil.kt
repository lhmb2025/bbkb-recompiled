package dev.bbkb.ime.personaldictionary.util

import android.content.Context
import android.view.inputmethod.InputMethodManager
import dev.bbkb.ime.R
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudLocaleException
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.TreeSet

/**
 * Utility functions for locale string conversion and parsing.
 *
 * Handles conversion between internal (BASL) and AUD locale formats.
 */
object LocaleUtil {
    /**
     * Hoisted: parse() runs once per word substitution in
     * PersonalDictionaryUtil.isWsDefaultForLocale and once per AUD row in
     * AudWrapper.add, and `"_|-".toRegex()` compiled a fresh Regex each time
     * (audit PD-20).
     */
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
            // Locale.Builder throws this *unchecked*, so it escaped both the
            // @Throws(AudLocaleException) contract and AudWrapper.add's
            // `catch (unused: AudLocaleException)` - and these strings come from the
            // system UserDictionary provider, i.e. third-party writable data
            // (audit PD-20).
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

    // ========== Methods merged from InputMethodLocaleUtils.java ==========

    /**
     * Get available locale strings as a TreeSet, including empty string for "All languages".
     */
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

    /**
     * Get available locales as a List<Locale>.
     */
    @JvmStatic
    fun getAvailableLocales(context: Context): List<Locale> {
        val list = mutableListOf<Locale>()
        val currentLocale = getCurrentInputMethodLocale(context)
        if (currentLocale != null) {
            parse(currentLocale)?.let { list.add(it) }
        }
        return list
    }

    /**
     * Get the current input method subtype's locale string.
     */
    private fun getCurrentInputMethodLocale(context: Context): String? {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return null
        val currentSubtype = inputMethodManager.currentInputMethodSubtype ?: return null
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            currentSubtype.languageTag
        } else {
            @Suppress("DEPRECATION")
            currentSubtype.locale
        }
    }

    // ========== Methods merged from core.dictionary.LocaleUtils.java ==========

    /**
     * Returns a display-friendly locale name for dictionary UI.
     * Returns "All languages" string if locale is empty.
     */
    @JvmStatic
    fun getDisplayNameForDictionary(context: Context, localeString: String?): String {
        return if (localeString.isNullOrEmpty()) {
            context.resources.getString(R.string.user_dict_settings_all_languages)
        } else {
            // ConfigurationCompat, not Configuration.getLocales(): the latter is
            // API 24 while minSdk is 23, so opening TextShortcutsScreen or
            // UserDictionaryScreen on an API-23 device threw NoSuchMethodError
            // (audit PD-19).
            val displayLocale = androidx.core.os.ConfigurationCompat
                .getLocales(context.resources.configuration)
                .get(0) ?: Locale.getDefault()
            parse(localeString)?.getDisplayName(displayLocale) ?: localeString
        }
    }
}
