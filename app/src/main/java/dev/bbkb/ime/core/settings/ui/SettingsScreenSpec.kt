package dev.bbkb.ime.core.settings.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.BoolRes
import androidx.annotation.DrawableRes
import androidx.annotation.FractionRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.device.profile.DeviceProfile

/**
 * The declarative description of a settings screen: a title and a list of rows.
 *
 * Every screen under `screens/` used to be a hand-written Composable that repeated the same
 * `Scaffold` + `TopAppBar` + scrolling `Column` frame and the same
 * `var x by remember { mutableStateOf(prefs.getX(key, default)) }` state declarations. Those parts
 * carry no information about the screen, so they live in [SettingsScreenHost] now and each screen
 * declares only its rows.
 *
 * ## The two contracts this model exists to preserve
 *
 * **State lifetime.** A screen reads each preference once, when it first composes, holds the value
 * in composition state, and writes through on change. It never re-reads. [SettingsScreenHost]
 * keeps that exactly: one `remember`ed map, seeded once from every row's [PrefStore] — including
 * rows that are currently hidden, because the old screens declared their `var`s unconditionally
 * too.
 *
 * **Search anchors.** `SettingsSearchIndex` is hand-maintained against `settingsSearchAnchor("…")`
 * calls in `screens/`, and `SettingsSearchIndexTest` scans that directory for them. So a row's
 * anchor is carried as a ready-made [Modifier] written at the row's declaration site — the literal
 * stays in the screen's own file, next to the row it describes, and the search index keeps its
 * source of truth. A row's device gate ([SettingsRow.visible]) and its index entry's
 * `DeviceRequirement` still have to agree; `SettingsScreenRenderTest` asserts that both ways.
 */
class ScreenSpec(
    @StringRes val title: Int,
    val rows: List<SettingsRow>,
)

// ── where a row's value lives ────────────────────────────────────────────────

/**
 * A single preference: its key, how to read it and how to write it.
 *
 * The key and the default are the persisted user state, so they are written here verbatim as the
 * screen wrote them before. A store that re-declares a default differently from the screen it
 * replaced silently changes what existing users have configured.
 */
class PrefStore<T>(
    val key: String,
    private val reader: (SharedPreferences, Context) -> T,
    private val writer: (SharedPreferences.Editor, T) -> Unit,
) {
    fun read(prefs: SharedPreferences, context: Context): T = reader(prefs, context)

    /** One `edit()`/`apply()` per change, exactly as the screens did. */
    fun write(prefs: SharedPreferences, value: T) {
        prefs.edit().also { writer(it, value) }.apply()
    }
}

fun boolPref(key: String, default: Boolean): PrefStore<Boolean> = PrefStore(
    key,
    { prefs, _ -> prefs.getBoolean(key, default) },
    { editor, value -> editor.putBoolean(key, value) },
)

/** Boolean whose default comes from `res/values/bools.xml`, as `SettingsManager` reads it. */
fun boolPref(key: String, @BoolRes default: Int): PrefStore<Boolean> = PrefStore(
    key,
    { prefs, context -> prefs.getBoolean(key, context.resources.getBoolean(default)) },
    { editor, value -> editor.putBoolean(key, value) },
)

fun stringPref(key: String, default: String): PrefStore<String> = PrefStore(
    key,
    { prefs, _ -> prefs.getString(key, default) ?: default },
    { editor, value -> editor.putString(key, value) },
)

/** String whose default is computed from resources (e.g. an `R.integer` rendered as text). */
fun stringPref(key: String, default: (Context) -> String): PrefStore<String> = PrefStore(
    key,
    { prefs, context -> default(context).let { prefs.getString(key, it) ?: it } },
    { editor, value -> editor.putString(key, value) },
)

/**
 * An int stored with `putInt` but edited as a string (the auto-correction modes). The read
 * tolerates a legacy `String`-typed entry, which is what the screens' `try`/`catch` did.
 */
fun intAsStringPref(key: String, default: Int): PrefStore<String> = PrefStore(
    key,
    { prefs, _ ->
        try {
            prefs.getInt(key, default).toString()
        } catch (e: ClassCastException) {
            prefs.getString(key, default.toString()) ?: default.toString()
        }
    },
    { editor, value -> editor.putInt(key, value.toInt()) },
)

fun intPref(key: String, default: Int): PrefStore<Int> = PrefStore(
    key,
    { prefs, _ -> prefs.getInt(key, default) },
    { editor, value -> editor.putInt(key, value) },
)

/** Int whose default comes from `res/values/integers.xml`. */
fun intPrefRes(key: String, @IntegerRes default: Int): PrefStore<Int> = PrefStore(
    key,
    { prefs, context -> prefs.getInt(key, context.resources.getInteger(default)) },
    { editor, value -> editor.putInt(key, value) },
)

/**
 * A float fraction stored as `0f..1f` but edited as whole percent, with an `R.fraction` default.
 *
 * One copy of what `readTapRegionPercent` (AdvancedGestureParametersScreen) and the identical
 * `readScalePercent` (AnimationParametersScreen) each did privately, discarding a legacy
 * int-typed entry the way `SettingsManager` does on the engine side. The default comes from
 * `ResourceConfigManager`, not `Resources` directly, so a device config override still applies.
 */
fun percentPref(key: String, @FractionRes default: Int): PrefStore<Int> = PrefStore(
    key,
    { prefs, context ->
        val stored = try {
            prefs.getFloat(key, -1f)
        } catch (e: ClassCastException) {
            prefs.edit().remove(key).apply()
            -1f
        }
        val fraction =
            if (stored != -1f) stored
            else ResourceConfigManager.getFractionValue(context.resources, default)
        Math.round(fraction * 100f)
    },
    { editor, value -> editor.putFloat(key, value / 100f) },
)

// ── what a row draws ─────────────────────────────────────────────────────────

/** A row's icon. Vector and drawable take different `PreferenceItem` paths, as they did before. */
sealed interface RowIcon {
    class Vector(val image: ImageVector) : RowIcon
    class Drawable(@DrawableRes val id: Int) : RowIcon
}

fun ImageVector.asRowIcon(): RowIcon = RowIcon.Vector(this)

fun drawableIcon(@DrawableRes id: Int): RowIcon = RowIcon.Drawable(id)

/** How a row's second line is produced. */
sealed interface RowSummary {
    /** No second line. */
    object None : RowSummary

    class Res(@StringRes val id: Int) : RowSummary

    /** Two resources, picked by the row's own boolean value. */
    class OnOff(@StringRes val on: Int, @StringRes val off: Int) : RowSummary

    /** Anything else, given the row's current value. */
    class Of(val text: (Context, Any?) -> String?) : RowSummary
}

/**
 * The device and cross-row facts a row's `visible` / `enabled` gate can read.
 *
 * [bool] / [str] / [int] read the host's live state map, which is what a screen's local `var`
 * gave a dependent row before.
 */
class SettingsEnv internal constructor(
    val context: Context,
    val prefs: SharedPreferences,
    private val state: SnapshotStateMap<String, Any?>,
) {
    val hasPhysicalKeyboard: Boolean by lazy {
        DeviceProfile.current()?.hasPhysicalKeyboard() ?: false
    }

    fun bool(key: String): Boolean = state[key] as? Boolean ?: false

    fun str(key: String): String = state[key] as? String ?: ""

    fun int(key: String): Int = state[key] as? Int ?: 0
}

// ── the rows ─────────────────────────────────────────────────────────────────

sealed interface SettingsRow {
    /** Whether this row exists at all. Mirrors the `if (…)` the screen wrapped the row in. */
    val visible: (SettingsEnv) -> Boolean
}

/** A row that owns a preference value. */
sealed interface ValueRow<T> : SettingsRow {
    val store: PrefStore<T>
}

/** Section header. */
class Category(
    @StringRes val title: Int,
    override val visible: (SettingsEnv) -> Boolean = { true },
) : SettingsRow

/** Navigation row — title, summary, chevron. */
class Nav(
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
    val onClick: (Context) -> Unit,
) : SettingsRow

/** Informational / action row — no chevron. */
class Simple(
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    /** Carries [settingsSearchAnchor] for the action rows search can land on. */
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
    val onClick: ((Context) -> Unit)? = null,
) : SettingsRow

/** Switch row. */
class Toggle(
    override val store: PrefStore<Boolean>,
    @StringRes val title: Int,
    val summary: RowSummary = RowSummary.None,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
    /** Fired after the value is committed — the side effects a few toggles carry. */
    val onWrite: ((Context, Boolean) -> Unit)? = null,
) : ValueRow<Boolean>

/**
 * Switch row that honours `DeviceProfile`'s per-device settings-override table: a forced value
 * wins over the stored one, a read-only setting greys out, a hidden one is not drawn.
 *
 * Opt-in per row, exactly as `ManagedSwitchPreference` was: applying the override table to every
 * row would be a behaviour change on devices whose `device_config_*.xml` declares overrides.
 */
class ManagedToggle(
    override val store: PrefStore<Boolean>,
    @StringRes val title: Int,
    val summary: RowSummary = RowSummary.None,
    val icon: RowIcon? = null,
    val enabled: (SettingsEnv) -> Boolean = { true },
    override val visible: (SettingsEnv) -> Boolean = { true },
    val onWrite: ((Context, Boolean) -> Unit)? = null,
) : ValueRow<Boolean>

/** One option in a [Choice] dialog: the persisted value and the label shown for it. */
class ChoiceOption(val value: String, val label: String)

/**
 * Radio-dialog row. [options] is the single source for both the dialog entries and the row's
 * summary, so the two cannot drift the way the old parallel `entries`/`entryValues` lists plus a
 * `when (value)` summary could.
 */
class Choice(
    override val store: PrefStore<String>,
    @StringRes val title: Int,
    val options: (Context) -> List<ChoiceOption>,
    /** Shown when the stored value matches no option. Index into [options]. */
    val fallbackIndex: Int = 0,
    /** Overrides the option-label summary for the few rows that format their own. */
    val summary: ((Context, String) -> String)? = null,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
    val onWrite: ((Context, String) -> Unit)? = null,
) : ValueRow<String>

/** Slider-dialog row. */
class Slide(
    override val store: PrefStore<Int>,
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    val range: IntRange,
    val step: Int? = null,
    val unit: String = "",
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
) : ValueRow<Int>

/**
 * The escape hatch: a block the row types cannot express, kept as the Composable it always was.
 *
 * Used for the handful of genuinely bespoke pieces — an intro paragraph, a permission card, a
 * locale warning dialog. A spec table that contorts itself to absorb one odd block is worse than
 * a spec table plus one hand-written block.
 */
class Custom(
    override val visible: (SettingsEnv) -> Boolean = { true },
    val content: @Composable (SettingsEnv) -> Unit,
) : SettingsRow
