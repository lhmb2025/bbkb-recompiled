package dev.bbkb.ime.core.layout

import android.content.Context
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template

/**
 * The engine's physical-keypad KDBs, built from one geometry file per device.
 *
 * Every keypad KDB the app used to ship per language had the same 30 key boxes; only the letters
 * and the header's engine language ids differed. So a device ships one geometry,
 * `assets/kdb/keypad_pkb_<device>.xml` (`keypad_pkb.xml` for any device whose config names no
 * `<kdb-variant>`), and the files the engine loads are built from it at run time: [forDevice]
 * stamps each engine id into the header and writes on the keys the letters of the built-in letter
 * layout that id's file used to carry ([SHIPPED]). They are registered under the device's variant
 * name (`Xt9KdbVariant`), where the engine's lookup by (primaryId, secondaryId) finds them.
 *
 * The keyboards whose letters no built-in letter layout holds yet (the native-script keyboards
 * whose keypad types Latin, the language packs' scripts, Cangjie's own file) keep their shipped
 * `assets/kdb/<name>_pkb.xml`, which the engine falls back to for any id the device's files lack.
 */
object KeypadKdbs {

    /** The variant name of a device whose config names no `<kdb-variant>`. */
    const val GENERIC: String = "keypad"

    private const val FILE_PREFIX = "keypad_pkb"

    /** The engine's id for the physical keypad (`ET9SKIDPhonePad`): every keypad KDB's `secondaryId`. */
    const val KEYPAD_SECONDARY_ID: Int = 6

    /**
     * Which built-in letter layout holds the letters of the keypad KDB the engine loads for
     * [primaryId]: the keyboard of [language] built from the layout set [layoutSet], on a QWERTY
     * keypad. Each was the shipped `<name>_pkb.xml` of that id, measured to load for that keyboard
     * (`LetterLayoutBuiltInKdbTest`, whose copies of those files live in
     * `xt9kdb-scaffold/reference/kdb_pkb/`).
     */
    class Source(val primaryId: Int, val language: String, val layoutSet: String?)

    /** The engine ids whose keypad KDB is built rather than shipped, and where their letters come from. */
    @JvmField
    val SHIPPED: List<Source> = listOf(
        Source(1, "ar", "arabic"),
        Source(3, "ca", "spanish"),
        Source(6, "da", null),
        Source(7, "de", null),
        Source(8, "el", "greek"),
        Source(9, "en", null),
        Source(10, "es", null),
        Source(11, "fi", null),
        Source(12, "fr", null),
        Source(13, "iw", "hebrew"),
        Source(17, "ja", "romaji"),
        Source(18, "ko", "hangul"),
        Source(20, "nb", null),
        Source(25, "ru", "east_slavic"),
        Source(31, "tr", null),
        Source(34, "uk", "east_slavic"),
        Source(35, "be", "east_slavic"),
        Source(37, "et", "nordic"),
    )

    /** The geometry file of [device]'s keypad under `assets/kdb/`. */
    @JvmStatic
    fun templateFile(device: String): String =
        if (device.isEmpty() || device == GENERIC) "$FILE_PREFIX.xml" else "${FILE_PREFIX}_$device.xml"

    /** Whether [name], a file under `assets/kdb/`, is a device's geometry rather than a language's KDB. */
    @JvmStatic
    fun isTemplate(name: String): Boolean = name.startsWith(FILE_PREFIX)

    /**
     * The engine keypad KDB of each id in [SHIPPED], built from [template] (a device's geometry
     * file) and the letters of the built-in layout [builtIn] returns for (language, layout set).
     * An id whose layout is missing is left out, so the engine falls back to a shipped file.
     */
    fun forDevice(template: String, builtIn: (language: String, layoutSet: String?) -> LetterLayout?): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (source in SHIPPED) {
            val layout = builtIn(source.language, source.layoutSet) ?: continue
            val stamped = LetterLayoutKdb.withEngineIds(template, source.primaryId, KEYPAD_SECONDARY_ID) ?: continue
            out[source.primaryId] = LetterLayoutKdb.rewrite(stamped, layout, Template.PKB)
        }
        return out
    }

    /** The letters a [SHIPPED] source names, from [registry]: always on a QWERTY keypad, like the shipped files. */
    @JvmStatic
    fun shippedLetters(registry: LetterLayoutRegistry): (String, String?) -> LetterLayout? =
        { language, layoutSet -> registry.builtIn(language, "qwerty", null, layoutSet) }

    /**
     * [device]'s engine keypad KDBs, from the app's assets: the bytes to register under its
     * variant. Null when the device has no geometry file of its own.
     */
    @JvmStatic
    fun forDevice(context: Context, device: String): List<ByteArray>? {
        val template = try {
            context.assets.open("kdb/" + templateFile(device)).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            return null
        }
        return forDevice(template, shippedLetters(LetterLayoutRegistry(context))).values.map { it.toByteArray(Charsets.UTF_8) }
    }
}
