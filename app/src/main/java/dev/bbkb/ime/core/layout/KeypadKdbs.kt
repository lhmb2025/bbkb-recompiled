package dev.bbkb.ime.core.layout

import android.content.Context
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template

/**
 * The engine's physical-keypad KDBs, built at run time from one geometry file per device,
 * `assets/kdb/keypad_pkb_<device>.xml` (`keypad_pkb.xml` when the config names no `<kdb-variant>`).
 * [forDevice] stamps each [SHIPPED] engine id into the header and writes that id's built-in letters
 * on the keys; the result is registered under the device's variant name (`Xt9KdbVariant`). Ids not
 * built here (native-script keypads typing Latin, language-pack scripts, Cangjie) fall back to the
 * shipped `assets/kdb/<name>_pkb.xml`.
 */
object KeypadKdbs {

    /** The variant name of a device whose config names no `<kdb-variant>`. */
    const val GENERIC: String = "keypad"

    private const val FILE_PREFIX = "keypad_pkb"

    /** The engine's id for the physical keypad (`ET9SKIDPhonePad`): every keypad KDB's `secondaryId`. */
    const val KEYPAD_SECONDARY_ID: Int = 6

    /**
     * The built-in letter layout ([language], [layoutSet], on a QWERTY keypad) whose letters go into
     * the keypad KDB for [primaryId]. Pinned by `LetterLayoutBuiltInKdbTest` against the reference
     * files in `xt9kdb-scaffold/reference/kdb_pkb/`.
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
     * The keypad KDB of each [SHIPPED] id, built from [template] (device geometry) and [builtIn]'s
     * letters. An id whose layout is missing is left out, so the engine falls back to a shipped file.
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

    /** [device]'s keypad KDB bytes to register under its variant, or null when it has no geometry file. */
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
