package dev.bbkb.ime.core.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R

/**
 * A long list's search, as the dictionary screens do it: a magnifier in the top bar that turns the
 * bar into a field, and Back (the arrow or the system's) that closes the field before it leaves
 * the screen.
 *
 * [active] is whether the field is open; [query] is what is typed in it. [filtering] is what a
 * list asks before it narrows itself: an open field with nothing typed still shows everything.
 */
@Stable
class ListSearchState {
    var active by mutableStateOf(false)
    var query by mutableStateOf("")

    val filtering: Boolean get() = active && query.isNotBlank()

    fun close() {
        active = false
        query = ""
    }

    /** Whether [text] is listed under the current query: always, unless one is typed. */
    fun matches(vararg text: String?): Boolean {
        if (!filtering) return true
        val wanted = query.trim()
        return text.any { it != null && it.contains(wanted, ignoreCase = true) }
    }
}

@Composable
fun rememberListSearchState(): ListSearchState = remember { ListSearchState() }

/** The magnifier a searchable list puts in its top bar. */
@Composable
fun ListSearchAction(search: ListSearchState) {
    IconButton(onClick = { search.active = true }) {
        Icon(
            ImageVector.vectorResource(R.drawable.ic_settings_m_search),
            stringResource(R.string.settings_common_search),
        )
    }
}

/**
 * [SettingsScaffold] for a screen whose list can be searched: it adds the magnifier, swaps the
 * title for the field while [search] is open, and makes both Backs close the search first.
 */
@Composable
fun SearchableSettingsScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    search: ListSearchState,
    searchHint: String,
    modifier: Modifier = Modifier,
    /** The screen's other top bar actions, after the magnifier. */
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(enabled = search.active) { search.close() }
    SettingsScaffold(
        title = title,
        onNavigateBack = { if (search.active) search.close() else onNavigateBack() },
        modifier = modifier,
        actions = {
            ListSearchAction(search)
            actions()
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        navigationContentDescription = stringResource(
            if (search.active) R.string.settings_common_close_search else R.string.settings_common_back
        ),
        searchField = if (search.active) {
            { TopBarSearchField(query = search.query, onQueryChange = { search.query = it }, hint = searchHint) }
        } else null,
        content = content,
    )
}

/**
 * The search field the top bar holds while searching: a pill like the one on the settings home
 * page, focused as it opens, with a cross to empty it once there is something to empty.
 */
@Composable
fun RowScope.TopBarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .weight(1f)
            .padding(end = 12.dp)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .background(colors.surfaceContainerHighest, CircleShape)
                    .padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_settings_m_close),
                            stringResource(R.string.settings_common_clear_search),
                        )
                    }
                }
            }
        },
    )
}
