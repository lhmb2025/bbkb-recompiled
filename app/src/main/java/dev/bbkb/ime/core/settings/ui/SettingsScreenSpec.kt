package dev.bbkb.ime.core.settings.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.BoolRes
import androidx.annotation.DrawableRes
import androidx.annotation.FractionRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.device.profile.DeviceProfile

/**
 * A settings screen as data: a title and rows; [SettingsScreenHost] draws the frame.
 *
 * State: each preference is read once on first composition (hidden rows included) and written
 * through on change, never re-read. Search anchors stay as `settingsSearchAnchor("…")` modifiers
 * at the row's declaration site because `SettingsSearchIndexTest` scans `screens/` for them; a
 * row's [SettingsRow.visible] gate must agree with its index `DeviceRequirement` (render test).
 */
class ScreenSpec(
    @StringRes val title: Int,
    val rows: List<SettingsRow>,
)

/**
 * A single preference: key, reader and writer. Keys and defaults are persisted user state;
 * changing a default silently changes existing users' configuration.
 */
class PrefStore<T>(
    val key: String,
    private val reader: (SharedPreferences, Context) -> T,
    private val writer: (SharedPreferences.Editor, T) -> Unit,
) {
    fun read(prefs: SharedPreferences, context: Context): T = reader(prefs, context)

    /** One `edit()`/`apply()` per change. */
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

/** An int stored with `putInt` but edited as a string; the read tolerates a legacy `String` entry. */
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
 * A float fraction stored as `0f..1f` but edited as whole percent. A legacy int entry is discarded
 * (as `SettingsManager` does); the default goes through `ResourceConfigManager` so device overrides apply.
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

/** A row's icon. Vector and drawable take different `PreferenceItem` paths. */
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

/** Facts a row's `visible` / `enabled` gate can read; [bool] / [str] / [int] read the live state map. */
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

sealed interface SettingsRow {
    /** Whether this row exists at all. */
    val visible: (SettingsEnv) -> Boolean
}

/** A row that owns a preference value. */
sealed interface ValueRow<T> : SettingsRow {
    val store: PrefStore<T>
}

class Category(
    @StringRes val title: Int,
    override val visible: (SettingsEnv) -> Boolean = { true },
) : SettingsRow

/** Navigation row with a chevron. */
class Nav(
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    /** A summary built at render time (a live status); wins over [summary] when given. */
    val summaryText: ((Context) -> String)? = null,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
    val onClick: (Context) -> Unit,
) : SettingsRow

/** Informational or action row, no chevron. */
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

class Toggle(
    override val store: PrefStore<Boolean>,
    @StringRes val title: Int,
    val summary: RowSummary = RowSummary.None,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
    /** Fired after the value is committed. */
    val onWrite: ((Context, Boolean) -> Unit)? = null,
) : ValueRow<Boolean>

/**
 * Switch row that honours `DeviceProfile`'s settings-override table: forced value wins, read-only
 * greys out, hidden is not drawn. Opt-in per row; applying it everywhere would change behaviour.
 */
class ManagedToggle(
    override val store: PrefStore<Boolean>,
    @StringRes val title: Int,
    val summary: RowSummary = RowSummary.None,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    override val visible: (SettingsEnv) -> Boolean = { true },
    val onWrite: ((Context, Boolean) -> Unit)? = null,
) : ValueRow<Boolean>

/** One option in a [Choice] dialog: the persisted value and the label shown for it. */
class ChoiceOption(val value: String, val label: String)

/** Radio-dialog row. [options] feeds both the dialog entries and the summary so they cannot drift. */
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

class Slide(
    override val store: PrefStore<Int>,
    @StringRes val title: Int,
    @StringRes val summary: Int? = null,
    val range: IntRange,
    val step: Int? = null,
    val unit: String = "",
    /** The row's second line is the current value; [summary] moves into the dialog. */
    val valueOnRow: Boolean = false,
    val icon: RowIcon? = null,
    val iconSpaceReserved: Boolean = false,
    val enabled: (SettingsEnv) -> Boolean = { true },
    val modifier: Modifier = Modifier,
    override val visible: (SettingsEnv) -> Boolean = { true },
) : ValueRow<Int>

/** Escape hatch for a bespoke block (intro paragraph, permission card, warning dialog). */
class Custom(
    override val visible: (SettingsEnv) -> Boolean = { true },
    /** True for a row-shaped block that sits inside the surrounding group; false sits between groups. */
    val inGroup: Boolean = false,
    val content: @Composable (SettingsEnv) -> Unit,
) : SettingsRow

/**
 * Ends one group and starts the next with no label between them: a screen's master switch sits
 * in a card of its own above the rows it governs, as "Enable suggestions" does.
 */
fun groupBreak(visible: (SettingsEnv) -> Boolean = { true }): Custom = Custom(visible = visible) {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier.height(LocalSpacing.current.large)
    )
}
