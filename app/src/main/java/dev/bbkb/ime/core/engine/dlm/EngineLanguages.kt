package dev.bbkb.ime.core.engine.dlm

import java.util.Locale

/**
 * Names for the engine's two-byte language ids, as they appear on learned-model records. The low
 * byte is the engine's primary language id, which follows the Windows primary language ids (English
 * 0x09, Russian 0x19, Kazakh 0x3f…); the high byte is the engine's regional variant and is ignored
 * here. 0xFFFF marks a word that belongs to no language (added on purpose rather than typed).
 */
object EngineLanguages {

    /** The id of a word that belongs to no language. */
    const val ANY: Int = 0xffff

    private val codes: Map<Int, String> = mapOf(
        0x01 to "ar", 0x02 to "bg", 0x03 to "ca", 0x04 to "zh", 0x05 to "cs", 0x06 to "da", 0x07 to "de",
        0x08 to "el", 0x09 to "en", 0x0a to "es", 0x0b to "fi", 0x0c to "fr", 0x0d to "he", 0x0e to "hu",
        0x0f to "is", 0x10 to "it", 0x11 to "ja", 0x12 to "ko", 0x13 to "nl", 0x14 to "no", 0x15 to "pl",
        0x16 to "pt", 0x17 to "rm", 0x18 to "ro", 0x19 to "ru", 0x1a to "hr", 0x1b to "sk", 0x1c to "sq",
        0x1d to "sv", 0x1e to "th", 0x1f to "tr", 0x20 to "ur", 0x21 to "id", 0x22 to "uk", 0x23 to "be",
        0x24 to "sl", 0x25 to "et", 0x26 to "lv", 0x27 to "lt", 0x28 to "tg", 0x29 to "fa", 0x2a to "vi",
        0x2b to "hy", 0x2c to "az", 0x2d to "eu", 0x2e to "hsb", 0x2f to "mk", 0x32 to "tn", 0x34 to "xh",
        0x35 to "zu", 0x36 to "af", 0x37 to "ka", 0x38 to "fo", 0x39 to "hi", 0x3a to "mt", 0x3b to "se",
        0x3c to "ga", 0x3d to "yi", 0x3e to "ms", 0x3f to "kk", 0x40 to "ky", 0x41 to "sw", 0x42 to "tk",
        0x43 to "uz", 0x44 to "tt", 0x45 to "bn", 0x46 to "pa", 0x47 to "gu", 0x48 to "or", 0x49 to "ta",
        0x4a to "te", 0x4b to "kn", 0x4c to "ml", 0x4d to "as", 0x4e to "mr", 0x4f to "sa", 0x50 to "mn",
        0x51 to "bo", 0x52 to "cy", 0x53 to "km", 0x54 to "lo", 0x55 to "my", 0x56 to "gl", 0x57 to "kok",
        0x5b to "si", 0x5e to "am", 0x61 to "ne", 0x62 to "fy", 0x63 to "ps", 0x64 to "fil", 0x65 to "dv",
        0x68 to "ha", 0x6a to "yo", 0x6c to "nso", 0x6d to "ba", 0x6e to "lb", 0x6f to "kl", 0x70 to "ig",
        0x73 to "ti", 0x78 to "ii", 0x7e to "br", 0x80 to "ug", 0x81 to "mi", 0x82 to "oc", 0x83 to "co",
        0x84 to "gsw", 0x85 to "sah", 0x87 to "rw", 0x88 to "wo", 0x91 to "gd",
    )

    /** The ISO 639 code of [languageId]'s language, or null for [ANY] and ids this table lacks. */
    fun code(languageId: Int): String? = if (languageId == ANY) null else codes[languageId and 0xff]

    /** The language's name in [locale] ("English", "Русский" when the phone is in Russian), or null as [code]. */
    fun displayName(languageId: Int, locale: Locale = Locale.getDefault()): String? =
        code(languageId)?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale) }
}
