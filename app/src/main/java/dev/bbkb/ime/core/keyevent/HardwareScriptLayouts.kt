package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.content.res.AssetManager
import android.view.KeyEvent
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceProfile
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
 *
 * ## The user's own letter map
 *
 * Ahead of all that sits the letter map the user imported and switched on ([UserLetterMap], on
 * Physical keyboard > Custom physical layouts). It is consulted on every keyboard, Latin ones
 * included, whenever it is bound to the current keyboard's language and this phone's keypad: a key
 * it names types its `base` (Shift: its `shift`), double-taps through its `multitap`, offers its
 * `moreKeys` when held, and types its `alt` under Alt ([userAltFor], the first tier of
 * [AuxCharacterResolver]). A key it does not name falls through to the script layout and then to
 * the system key map, exactly as before. The parsed map is cached by
 * [UserLetterMapRepository.activeForTyping], keyed by the active id it re-reads per call, so
 * nothing here needs resetting on a language switch: the binding is checked per key.
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
        val app = context.applicationContext
        assets = app.assets
        userMapSource = { UserLetterMapRepository.activeForTyping(app) }
    }

    // ── the user tier ────────────────────────────────────────────────────────

    /** The active user map, whatever it is bound to. Set by [init]; tests replace it. */
    @Volatile
    internal var userMapSource: () -> UserLetterMap? = { null }

    /** The current keyboard's language in the app's spelling (`iw`, not `he`). Tests replace it. */
    @Volatile
    internal var languageSource: () -> String? = {
        SubtypeManager.getInstance()?.currentSubtypeLocale?.let { LocaleUtils.languageCode(it) }
    }

    /** This phone's keypad layout (`qwerty`/`qwertz`/`azerty`), or null when unknown. Tests replace it. */
    @Volatile
    internal var keypadLayoutSource: () -> String? = { DeviceProfile.current()?.keypadLayout }

    /**
     * The user's letter map when it drives the physical keys right now — switched on, bound to the
     * current keyboard's language (or to none) and to this phone's keypad — else null.
     */
    @JvmStatic
    fun userMap(): UserLetterMap? {
        val map = try {
            userMapSource()
        } catch (e: Exception) {
            null
        } ?: return null
        val language = try { languageSource() } catch (e: Exception) { null }
        val keypad = try { keypadLayoutSource() } catch (e: Exception) { null }
        return if (map.appliesTo(language, keypad)) map else null
    }

    /**
     * The Alt character the user's map gives the key [keyCode] (with [scanCode], -1 when unknown),
     * or 0 when no map applies or it names no Alt character for that key. The first tier of
     * [AuxCharacterResolver].
     */
    @JvmStatic
    fun userAltFor(keyCode: Int, scanCode: Int): Int = userMap()?.key(keyCode, scanCode)?.alt ?: 0

    /**
     * [userAltFor] for an event, and only while Alt is what decides the character. The multitap
     * interpreter runs before [AuxCharacterResolver] and asks the system key map for the Alt
     * character itself; this is what makes the user's Alt character the one it starts from.
     */
    @JvmStatic
    fun userAltForEvent(event: KeyEvent, interpretedMeta: Int): Int {
        val key = userMap()?.key(event.keyCode, event.scanCode) ?: return 0
        if (key.alt == 0) return 0
        if (!KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event)) return 0
        val modifiers = ModifierState.builder().event(event).interpretedMeta(interpretedMeta).build()
        return if (modifiers.isAltActiveForCharacter) key.alt else 0
    }

    /** What holding the key that typed [label] offers under the user's map, or null. */
    @JvmStatic
    fun userMoreKeysFor(label: String?): Array<String>? = userMap()?.moreKeysFor(label)

    /**
     * True while the user's map types a different letter on any of the 26 letter keys than the
     * key typed without it — the script layout's letter on an Arabic or Cyrillic keyboard, the
     * Latin letter otherwise. The touch keypad's swipe typing decodes against the engine's own
     * key geometry, which knows nothing of the map, so it is switched off for as long as this
     * holds (`SettingsValues.isCkbGestureInputEnabledForLocale`).
     */
    @JvmStatic
    fun userMapChangesLetters(): Boolean {
        val map = userMap() ?: return false
        val script = current()
        return map.changesLetters { keyCode ->
            script?.cell(keyCode)?.letter ?: ('a'.code + (keyCode - KeyEvent.KEYCODE_A))
        }
    }

    // ── the script tier ──────────────────────────────────────────────────────

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
     * The letter [event] types under the user's map or the current layout, or 0 when neither
     * names the key (or the key is not a letter key), a modifier other than Shift is active, the
     * physical symbol page is mapping the keys, or no map or script layout applies — every case in
     * which the caller should ask the system key map as before. [interpretedMeta] is the meta
     * state the caller interprets characters against.
     */
    @JvmStatic
    fun letterFor(event: KeyEvent, interpretedMeta: Int): Int {
        val keyCode = event.keyCode
        // The user's map first, for any key it names: it is the one tier that reaches past the
        // letter keys (digits, punctuation) and the one that applies on Latin keyboards too.
        val userKey = userMap()?.key(keyCode, event.scanCode)
        val isLetterKey = keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        val isExtraKey = !isLetterKey && keyCode == KeyEvent.KEYCODE_4
                && Character.getType(event.unicodeChar) == Character.CURRENCY_SYMBOL.toInt()
        if (userKey == null && !isLetterKey && !isExtraKey) return 0
        if (!KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event)) return skipped("not a physical keyboard event")
        val layout = if (userKey != null) null else current() ?: return skipped("no script layout for the current keyboard")
        val switcher = KeyboardSwitcher.getInstance()
        if (switcher != null && switcher.isPhysicalSymbolMappingActive) return skipped("symbol page is mapping the keys")
        val modifiers = ModifierState.builder().event(event).interpretedMeta(interpretedMeta).build()
        if (modifiers.isAltActiveForCharacter || modifiers.isCtrlActive || modifiers.isSymActive) return skipped("Alt, Ctrl or Sym is active")
        if (event.metaState and (KeyEvent.META_META_ON or KeyEvent.META_FUNCTION_ON) != 0) return skipped("Meta or Fn is held")
        val shifted = modifiers.isShiftActive || modifiers.isShiftLockedForLayout
                || (interpretedMeta and ModifierState.SHIFT_ANY_MASK) != 0
        if (userKey != null) {
            val letter = userKey.letter(shifted)
            if (InputPathDebug.on()) {
                Logger.info(TAG, "user map: keyCode=$keyCode shifted=$shifted -> U+${Integer.toHexString(letter).uppercase()}")
            }
            return letter
        }
        val cell = (if (isLetterKey) layout?.cell(keyCode) else layout?.extraKey?.takeIf { Character.isLetter(it.letter) })
            ?: return skipped("no letter on this key")
        val letter = letterOf(cell, shifted) { label -> switcher?.getPhysicalShiftLetter(label) }
        if (InputPathDebug.on()) {
            Logger.info(TAG, "keyCode=$keyCode shifted=$shifted -> U+${Integer.toHexString(letter).uppercase()}")
        }
        return letter
    }

    /**
     * What holding a key down types under the current layout when "hold for capital" is on: the
     * letter Shift gives on that key (Й, or ض on the ص key). 0 when no script layout applies to
     * the event, or Shift gives nothing different (most Arabic keys carry one letter).
     *
     * The hold handlers used to read the system key map here, so holding ص replaced it with a
     * Latin "Q" (beta report, 2026-10).
     */
    @JvmStatic
    fun heldLetterFor(event: KeyEvent): Int {
        val base = letterFor(event, 0)
        if (base == 0) return 0
        val shifted = letterFor(event, KeyEvent.META_SHIFT_ON)
        return if (shifted != 0 && shifted != base) shifted else 0
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
     * The double-tap sequence for a letter: the user map's `multitap` for the key that typed it,
     * else, under a script layout, the further legends of its key when the script is cased (Shift
     * is taken by upper case there: й twice gives ё), null otherwise. Upper-case labels get
     * upper-case alternates. Null when neither applies, so callers can fall through to their own
     * tables.
     */
    @JvmStatic
    fun alternatesFor(label: String?): Array<String>? {
        if (label.isNullOrEmpty()) return null
        userMap()?.multitapFor(label)?.let { return it }
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
