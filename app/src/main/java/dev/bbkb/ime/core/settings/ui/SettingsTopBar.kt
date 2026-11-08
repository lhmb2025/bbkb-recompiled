package dev.bbkb.ime.core.settings.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.R

/**
 * The header every settings screen draws, in the shape of the Android Settings app's own
 * (SettingsLib's collapsing toolbar): a 56dp toolbar row with the back arrow, under it a title
 * block that brings the whole header to 179dp, the title in 36sp at 24dp side margins and a 32dp
 * bottom margin. As the list scrolls the block shrinks to nothing and the title crossfades into
 * the toolbar row at 20sp; the scroll behaviour snaps it open or shut.
 *
 * Drawn here rather than with Material's large flexible top app bar because the 1.4.0 release
 * keeps that one internal; the public [TopAppBarScrollBehavior] does all the scroll work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit = {},
    /** The leading icon: the back arrow, or a close cross while a screen is in selection mode. */
    navigationIcon: ImageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_arrow_back),
    navigationContentDescription: String = stringResource(R.string.settings_common_back),
    /**
     * While a screen searches, its field: it takes the toolbar row in place of the title and the
     * actions, after the navigation icon, and the title block folds away so the results start
     * right under it. Null the rest of the time.
     */
    searchField: (@Composable RowScope.() -> Unit)? = null,
    /**
     * False for a screen whose own content names it (About, which leads with the app's icon and
     * name): the bar is just the toolbar row with the back arrow, it never collapses, and [title]
     * is kept only as the pane title accessibility services announce.
     */
    showTitle: Boolean = true,
) {
    val density = LocalDensity.current
    val collapseRange = if (!showTitle) 0f else with(density) {
        (SettingsTopBarExpandedHeight - SettingsTopBarCollapsedHeight).toPx()
    }
    // Tell the behaviour how far this bar can collapse. Its nested-scroll connection then moves
    // heightOffset between -collapseRange and 0 as the content scrolls under it.
    SideEffect {
        if (scrollBehavior.state.heightOffsetLimit != -collapseRange) {
            scrollBehavior.state.heightOffsetLimit = -collapseRange
        }
    }
    val fraction = scrollBehavior.state.collapsedFraction // 0 = open, 1 = shut
    val blockOpen by animateFloatAsState(if (searchField == null) 1f else 0f, label = "searchFold")
    val blockHeight = with(density) { ((collapseRange + scrollBehavior.state.heightOffset) * blockOpen).toDp() }
    val expandedTitleAlpha = (1f - fraction * 1.5f).coerceIn(0f, 1f)
    val collapsedTitleAlpha = ((fraction - 0.5f) * 2f).coerceIn(0f, 1f)

    Surface(
        color = LocalSettingsColors.current.page,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                // With no visible title, the screen still has a name for TalkBack to announce.
                .then(if (showTitle) Modifier else Modifier.semantics { paneTitle = title })
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SettingsTopBarCollapsedHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onNavigateBack) {
                    Icon(navigationIcon, navigationContentDescription)
                }
                if (searchField != null) {
                    searchField()
                } else if (!showTitle) {
                    Spacer(modifier = Modifier.weight(1f))
                    actions()
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 20.dp, end = 16.dp)
                            .graphicsLayer { alpha = collapsedTitleAlpha }
                            // The same words as the block title below; one heading in the tree,
                            // not two, for accessibility and for tests that find a screen by title.
                            .clearAndSetSemantics {},
                    )
                    actions()
                }
            }
            if (showTitle) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(blockHeight)
                    .clipToBounds()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                        .graphicsLayer { alpha = expandedTitleAlpha }
                        .semantics { heading() },
                )
            }
        }
    }
}

val SettingsTopBarCollapsedHeight = 56.dp
val SettingsTopBarExpandedHeight = 179.dp

/**
 * Whether a screen opens with the title block collapsed, as the Android 15+ expressive Settings
 * app does, or expanded. One switch for every screen.
 */
const val SETTINGS_TOP_BAR_STARTS_COLLAPSED = false

/** The scroll behaviour [SettingsTopBar] collapses with: exit until collapsed, snapping. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSettingsTopBarScrollBehavior(): TopAppBarScrollBehavior {
    val collapse = with(LocalDensity.current) {
        (SettingsTopBarExpandedHeight - SettingsTopBarCollapsedHeight).toPx()
    }
    val state = rememberTopAppBarState(
        initialHeightOffsetLimit = -collapse,
        initialHeightOffset = if (SETTINGS_TOP_BAR_STARTS_COLLAPSED) -collapse else 0f,
    )
    return TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state)
}

/**
 * The frame every settings screen shares: a scaffold on the page colour with [SettingsTopBar]
 * wired to the content's scrolling. Hand-written screens that need more of the scaffold (a
 * snackbar host, a floating action button) pass it through; everything else is the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    navigationIcon: ImageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_arrow_back),
    navigationContentDescription: String = stringResource(R.string.settings_common_back),
    /** See [SettingsTopBar]'s parameter of the same name. */
    searchField: (@Composable RowScope.() -> Unit)? = null,
    /** See [SettingsTopBar]'s parameter of the same name. */
    showTitle: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = rememberSettingsTopBarScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LocalSettingsColors.current.page,
        topBar = { SettingsTopBar(title, onNavigateBack, scrollBehavior, actions, navigationIcon, navigationContentDescription, searchField, showTitle) },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}
