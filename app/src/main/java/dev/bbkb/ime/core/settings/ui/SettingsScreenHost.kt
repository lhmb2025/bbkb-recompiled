package dev.bbkb.ime.core.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager

/**
 * The one renderer for every [ScreenSpec].
 *
 * It emits the frame the 17 hand-written screens each carried a copy of — [SettingsScaffold]
 * (the collapsing top bar on the page colour), a `fillMaxSize` scrolling `Column`, a trailing
 * `extraLarge` spacer — and then the rows in the grouped layout: the visible rows are split by
 * [groupRows] into labelled groups of cards and page-level blocks, and each row is drawn by the
 * same `ui/Preferences.kt` widgets the screens called directly.
 *
 * **State lifetime is the contract.** The value map is `remember`ed with no key and seeded once,
 * from every row that owns a preference, whether or not that row is currently visible. That is
 * precisely what the screens' unconditional `var x by remember { mutableStateOf(prefs.getX(…)) }`
 * declarations did: read once on first composition, hold, write through. Keying the map on the
 * row list would let a row re-read mid-screen and appear to reset.
 *
 * [rows] may be rebuilt on every recomposition — it is plain data plus lambdas, and nothing in it
 * captures a live value; summaries and gates are evaluated here, against the map.
 */
@Composable
fun SettingsScreenHost(
    @StringRes title: Int,
    onNavigateBack: () -> Unit,
    rows: List<SettingsRow>,
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    val state = remember {
        mutableStateMapOf<String, Any?>().apply {
            rows.forEach { row ->
                when (row) {
                    is ManagedToggle -> put(row.store.key, managedInitialValue(row, prefs, context))
                    is ValueRow<*> -> put(row.store.key, row.store.read(prefs, context))
                    else -> Unit
                }
            }
        }
    }
    val env = remember { SettingsEnv(context, prefs, state) }

    SettingsScaffold(
        title = context.getString(title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        val spacing = LocalSpacing.current

        // Visibility is decided here, before grouping, so a hidden row never opens a gap in a
        // group: the row's own gate, and for a ManagedToggle the device profile's hide as well.
        val deviceProfile = DeviceProfile.current()
        val visibleRows = rows.filter { row ->
            row.visible(env) &&
                (row !is ManagedToggle || deviceProfile?.isSettingHidden(row.store.key) != true)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            groupRows(visibleRows).forEach { block ->
                when (block) {
                    is RowBlock.Label -> PreferenceCategory(title = context.getString(block.row.title))
                    is RowBlock.Page -> block.row.content(env)
                    is RowBlock.Group -> PreferenceGroup {
                        block.rows.forEach { row -> RenderRow(row, env, state) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

@Composable
private fun RenderRow(
    row: SettingsRow,
    env: SettingsEnv,
    state: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Any?>,
) {
    val context = env.context
    when (row) {
        is Category -> PreferenceCategory(title = context.getString(row.title))

        is Custom -> row.content(env)

        is Simple -> PreferenceItemRow(
            title = context.getString(row.title),
            summary = row.summary?.let { context.getString(it) },
            icon = row.icon,
            iconSpaceReserved = row.iconSpaceReserved,
            modifier = row.modifier,
            onClick = row.onClick?.let { { it(context) } },
        )

        is Nav -> NavRow(
            title = context.getString(row.title),
            summary = row.summaryText?.invoke(context) ?: row.summary?.let { context.getString(it) },
            icon = row.icon,
            iconSpaceReserved = row.iconSpaceReserved,
            enabled = row.enabled(env),
            modifier = row.modifier,
            onClick = { row.onClick(context) },
        )

        is Toggle -> {
            val value = env.bool(row.store.key)
            SwitchRow(
                title = context.getString(row.title),
                summary = row.summary.render(context, value),
                icon = row.icon,
                iconSpaceReserved = row.iconSpaceReserved,
                checked = value,
                enabled = row.enabled(env),
                modifier = row.modifier,
                onCheckedChange = { newValue ->
                    state[row.store.key] = newValue
                    row.store.write(env.prefs, newValue)
                    row.onWrite?.invoke(context, newValue)
                },
            )
        }

        is ManagedToggle -> {
            // A hidden ManagedToggle never reaches here: the host's visibility pass drops it.
            val key = row.store.key
            val readOnly = DeviceProfile.current()?.isSettingReadOnly(key) == true
            val value = env.bool(key)
            SwitchRow(
                title = context.getString(row.title),
                summary = row.summary.render(context, value),
                icon = row.icon,
                iconSpaceReserved = row.iconSpaceReserved,
                checked = value,
                enabled = row.enabled(env) && !readOnly,
                modifier = Modifier,
                onCheckedChange = { newValue ->
                    if (!readOnly) {
                        state[key] = newValue
                        row.store.write(env.prefs, newValue)
                        row.onWrite?.invoke(context, newValue)
                    }
                },
            )
        }

        is Choice -> {
            val value = env.str(row.store.key)
            val options = row.options(context)
            ListPreference(
                title = context.getString(row.title),
                summary = row.summary?.invoke(context, value)
                    ?: (options.firstOrNull { it.value == value }
                        ?: options.getOrNull(row.fallbackIndex))?.label
                    ?: "",
                icon = row.icon.asImageVector(),
                iconSpaceReserved = row.iconSpaceReserved,
                entries = options.map { it.label },
                entryValues = options.map { it.value },
                value = value,
                enabled = row.enabled(env),
                modifier = row.modifier,
                onValueChange = { newValue ->
                    state[row.store.key] = newValue
                    row.store.write(env.prefs, newValue)
                    row.onWrite?.invoke(context, newValue)
                },
            )
        }

        is Slide -> SliderPreference(
            title = context.getString(row.title),
            summary = row.summary?.let { context.getString(it) },
            icon = row.icon.asImageVector(),
            iconSpaceReserved = row.iconSpaceReserved,
            value = env.int(row.store.key),
            valueRange = row.range,
            stepSize = row.step,
            unit = row.unit,
            enabled = row.enabled(env),
            modifier = row.modifier,
            onValueChange = { newValue ->
                state[row.store.key] = newValue
                row.store.write(env.prefs, newValue)
            },
        )
    }
}

private fun RowSummary.render(
    context: android.content.Context,
    value: Any?,
): String? = when (this) {
    is RowSummary.None -> null
    is RowSummary.Res -> context.getString(id)
    is RowSummary.OnOff -> context.getString(if (value == true) on else off)
    is RowSummary.Of -> text(context, value)
}

/**
 * The value a [ManagedToggle] starts on. Same order as `ManagedSwitchPreference`: a device
 * override with a forced value wins, otherwise the stored preference.
 */
private fun managedInitialValue(
    row: ManagedToggle,
    prefs: android.content.SharedPreferences,
    context: android.content.Context,
): Boolean {
    val override = DeviceProfile.current().getSettingOverride(row.store.key)
    val stored = row.store.read(prefs, context)
    return if (override != null && override.hasForcedValue()) {
        override.forcedBooleanValue ?: stored
    } else {
        stored
    }
}

/**
 * A row's icon as the [ImageVector] the preference composables take: a vector as it is, a drawable
 * loaded through `vectorResource`. Before this, every row type but [Nav] cast the icon to
 * [RowIcon.Vector] and silently dropped a drawable (the Keyboard height row drew no icon).
 */
@Composable
private fun RowIcon?.asImageVector(): ImageVector? = when (this) {
    null -> null
    is RowIcon.Vector -> image
    is RowIcon.Drawable -> ImageVector.vectorResource(id)
}

@Composable
private fun PreferenceItemRow(
    title: String,
    summary: String?,
    icon: RowIcon?,
    iconSpaceReserved: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    SimplePreference(
        title = title,
        summary = summary,
        icon = icon.asImageVector(),
        iconSpaceReserved = iconSpaceReserved,
        modifier = modifier,
        onClick = onClick,
    )
}

/** Routes to whichever `PreferenceScreen` overload the icon kind needs, as the screens did. */
@Composable
private fun NavRow(
    title: String,
    summary: String?,
    icon: RowIcon?,
    iconSpaceReserved: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (icon is RowIcon.Drawable) {
        PreferenceScreen(
            title = title,
            summary = summary,
            icon = painterResource(icon.id),
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
    } else {
        PreferenceScreen(
            title = title,
            summary = summary,
            icon = icon.asImageVector(),
            iconSpaceReserved = iconSpaceReserved,
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    icon: RowIcon?,
    iconSpaceReserved: Boolean,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        icon = icon.asImageVector(),
        iconSpaceReserved = iconSpaceReserved,
        checked = checked,
        enabled = enabled,
        modifier = modifier,
        onCheckedChange = onCheckedChange,
    )
}
