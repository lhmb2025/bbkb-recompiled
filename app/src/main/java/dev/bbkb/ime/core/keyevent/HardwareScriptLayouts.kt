package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.content.res.AssetManager
import android.view.KeyEvent
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import java.util.concurrent.ConcurrentHashMap

/**
 * The physical keys type the active keyboard's alphabet.
 *
 * With an Arabic, Cyrillic, Hebrew, Greek or Korean keyboard active, a physical letter key
 * produces that layout's letter instead of the Latin one the system key map reports. The letters
 * come from the engine's own hardware layout for the language ([HardwareScriptLayout]); every
 * language typed on Latin keys has no such layout and is untouched. The user setting
 * [PREF_KEY] ("Use active language's alphabet for physical keyboard", on by default; on the
 * Language screen, shown once such a keyboard is enabled on a phone with a physical keyboard)
 * turns the whole thing off for someone with Latin keycaps who wants the on-screen alphabet only.
 *
 * Shift gives the upper-case letter where the script has one. On a caseless script it gives the
 * key's second legend: the `characterMapKeys` of the layout's physical-key table (`pkbd_*.xml`,
 * where BlackBerry recorded the firmware's Shift layer: ص→ض, ة→ء, Hebrew finals), else the second
 * `keyCodes` entry of the engine layout. On a cased script the second legend (ё on the й key) is
 * reached by double-tapping instead, through the multitap tables: see [alternatesFor].
 */
object HardwareScriptLayouts {

    const val PREF_KEY = "pref_pkb_active_language_alphabet"
    private const val TAG = "HardwareScript"

    /** Language code (the app's spelling) -> `assets/kdb/<name>_pkb.xml`. */
    private val FILES = mapOf(
        "ar" to "arabic",
        "ru" to "east_slavic",
        "uk" to "ukranian",
        "be" to "belarusian",
        "iw" to "hebrew",
        "el" to "greek",
        "ko" to "korean",
    )

    private val NONE = Any()

    @Volatile
    private var assets: AssetManager? = null
    private val cache = ConcurrentHashMap<String, Any>()

    @JvmStatic
    fun init(context: Context) {
        assets = context.applicationContext.assets
    }

    /**
     * Whether a keyboard locale (`ar`, `ru`, `iw_IL`, either spelling of Hebrew) has an alphabet
     * of its own on the physical keys. The Language screen offers the setting only when one of
     * the enabled keyboards does.
     */
    @JvmStatic
    fun supportsLocale(locale: String?): Boolean {
        if (locale.isNullOrEmpty()) return false
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        return FILES.containsKey(LocaleUtils.languageCode(parsed))
    }

    /** The layout for a language code in the app's spelling (`ar`, `iw`), or null. */
    @JvmStatic
    fun forLanguage(languageCode: String): HardwareScriptLayout? {
        val name = FILES[languageCode] ?: return null
        val am = assets ?: return null
        val cached = cache[name]
        if (cached != null) return cached as? HardwareScriptLayout
        val parsed = try {
            am.open("kdb/${name}_pkb.xml").use { HardwareScriptLayout.parse(it) }
        } catch (e: Exception) {
            null
        }
        cache[name] = parsed ?: NONE
        return parsed
    }

    /** The layout the physical keys follow right now, or null when they type Latin. */
    @JvmStatic
    fun current(): HardwareScriptLayout? {
        if (!enabled()) return null
        val locale = SubtypeManager.getInstance()?.currentSubtypeLocale ?: return null
        return forLanguage(LocaleUtils.languageCode(locale))
    }

    private fun enabled(): Boolean = try {
        SettingsManager.getInstance()?.settingsValues?.pkbUsesActiveLanguageAlphabet ?: true
    } catch (e: Exception) {
        true
    }

    /**
     * The letter [event] types under the current layout, or 0 when the key is not a letter key,
     * a modifier other than Shift is active, the physical symbol page is mapping the keys, or no
     * script layout is active — every case in which the caller should ask the system key map as
     * before. [interpretedMeta] is the meta state the caller interprets characters against.
     */
    @JvmStatic
    fun letterFor(event: KeyEvent, interpretedMeta: Int): Int {
        val keyCode = event.keyCode
        val isLetterKey = keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        val isExtraKey = !isLetterKey && keyCode == KeyEvent.KEYCODE_4
                && Character.getType(event.unicodeChar) == Character.CURRENCY_SYMBOL.toInt()
        if (!isLetterKey && !isExtraKey) return 0
        if (!KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event)) return skipped("not a physical keyboard event")
        val layout = current() ?: return skipped("no script layout for the current keyboard")
        val switcher = KeyboardSwitcher.getInstance()
        if (switcher != null && switcher.isPhysicalSymbolMappingActive) return skipped("symbol page is mapping the keys")
        val modifiers = ModifierState.builder().event(event).interpretedMeta(interpretedMeta).build()
        if (modifiers.isAltActiveForCharacter || modifiers.isCtrlActive || modifiers.isSymActive) return skipped("Alt, Ctrl or Sym is active")
        if (event.metaState and (KeyEvent.META_META_ON or KeyEvent.META_FUNCTION_ON) != 0) return skipped("Meta or Fn is held")
        val cell = (if (isLetterKey) layout.cell(keyCode) else layout.extraKey?.takeIf { Character.isLetter(it.letter) })
            ?: return skipped("no letter on this key")
        val shifted = modifiers.isShiftActive || modifiers.isShiftLockedForLayout
                || (interpretedMeta and ModifierState.SHIFT_ANY_MASK) != 0
        val letter = letterOf(cell, shifted) { label -> switcher?.getPhysicalShiftLetter(label) }
        if (InputPathDebug.on()) {
            Logger.info(TAG, "keyCode=$keyCode shifted=$shifted -> U+${Integer.toHexString(letter).uppercase()}")
        }
        return letter
    }

    private fun skipped(reason: String): Int {
        if (InputPathDebug.on()) Logger.info(TAG, "system key map: $reason")
        return 0
    }

    /**
     * The letter a key [cell] produces: its unshifted letter, or with Shift the upper case where
     * one exists, else the layout table's Shift legend from [shiftTable], else the key's second
     * letter, else the letter itself.
     */
    @JvmStatic
    fun letterOf(cell: HardwareScriptLayout.Cell, shifted: Boolean, shiftTable: (String) -> String?): Int {
        val base = cell.letter
        if (!shifted) return base
        val upper = Character.toUpperCase(base)
        if (upper != base) return upper
        val fromTable = shiftTable(String(Character.toChars(base)))
        if (!fromTable.isNullOrEmpty()) return fromTable.codePointAt(0)
        return if (cell.hasAlternates) cell.codes[1] else base
    }

    /** True while a script layout drives the physical keys. */
    @JvmStatic
    fun isActive(): Boolean = current() != null

    /**
     * The double-tap sequence for a letter under the current layout: the further legends of its
     * key when the script is cased (Shift is taken by upper case there: й twice gives ё), null
     * otherwise. Upper-case labels get upper-case alternates. Null when no script layout is
     * active, so callers can fall through to their own tables.
     */
    @JvmStatic
    fun alternatesFor(label: String?): Array<String>? {
        if (label.isNullOrEmpty()) return null
        val layout = current() ?: return null
        val labelCodePoint = label.codePointAt(0)
        val lower = Character.toLowerCase(labelCodePoint)
        val cell = layout.cellForLetter(labelCodePoint) ?: layout.cellForLetter(lower) ?: return null
        if (!cell.hasAlternates) return null
        if (Character.toUpperCase(cell.letter) == cell.letter) return null
        val upperCase = labelCodePoint != lower
        return cell.alternates
            .map { if (upperCase) Character.toUpperCase(it) else it }
            .map { String(Character.toChars(it)) }
            .toTypedArray()
    }
}
