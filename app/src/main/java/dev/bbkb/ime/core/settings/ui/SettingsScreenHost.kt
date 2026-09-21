package dev.bbkb.ime.core.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager

/**
 * The one renderer for every [ScreenSpec].
 *
 * It emits the frame the 17 hand-written screens each carried a copy of — `Scaffold`, a
 * `TopAppBar` with a back arrow, a `fillMaxSize` scrolling `Column`, a trailing `extraLarge`
 * spacer — and then one preference composable per row, from the same `ui/Preferences.kt` widgets
 * the screens called directly.
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
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val spacing = LocalSpacing.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            rows.forEach { row ->
                if (row.visible(env)) {
                    RenderRow(row, env, state)
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
            onClick = row.onClick?.let { { it(context) } },
        )

        is Nav -> NavRow(
            title = context.getString(row.title),
            summary = row.summary?.let { context.getString(it) },
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
            val key = row.store.key
            val deviceProfile = DeviceProfile.current()
            if (!deviceProfile.isSettingHidden(key)) {
                val readOnly = deviceProfile.isSettingReadOnly(key)
                val value = env.bool(key)
                SwitchRow(
                    title = context.getString(row.title),
                    summary = row.summary.render(context, value),
                    icon = row.icon,
                    iconSpaceReserved = false,
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
                icon = (row.icon as? RowIcon.Vector)?.image,
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
            icon = (row.icon as? RowIcon.Vector)?.image,
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

@Composable
private fun PreferenceItemRow(
    title: String,
    summary: String?,
    icon: RowIcon?,
    iconSpaceReserved: Boolean,
    onClick: (() -> Unit)?,
) {
    SimplePreference(
        title = title,
        summary = summary,
        icon = (icon as? RowIcon.Vector)?.image,
        iconSpaceReserved = iconSpaceReserved,
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
            icon = (icon as? RowIcon.Vector)?.image,
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
        icon = (icon as? RowIcon.Vector)?.image,
        iconSpaceReserved = iconSpaceReserved,
        checked = checked,
        enabled = enabled,
        modifier = modifier,
        onCheckedChange = onCheckedChange,
    )
}
