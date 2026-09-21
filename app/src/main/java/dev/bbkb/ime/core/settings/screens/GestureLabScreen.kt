package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.gesture.arbiter.ActionPreview
import dev.bbkb.ime.core.gesture.arbiter.CkbKey
import dev.bbkb.ime.core.gesture.arbiter.CkbKeyGrid
import dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture
import dev.bbkb.ime.core.gesture.arbiter.GestureClassification
import dev.bbkb.ime.core.gesture.arbiter.GestureClassifier
import dev.bbkb.ime.core.gesture.arbiter.GestureConfig
import dev.bbkb.ime.core.gesture.arbiter.GestureTrace
import dev.bbkb.ime.core.gesture.arbiter.TracePoint
import dev.bbkb.ime.core.settings.PrefsManager
import kotlin.math.min

/**
 * Gesture Lab — a debug screen for the prototype unified gesture arbiter.
 *
 * Two tabs share one hoisted state so traces and config survive switching:
 *  - **Test**: the capture surface + verdict/features readout. Has no scrollable content, so a
 *    drag on the surface is never stolen by the page scroller.
 *  - **Tuning**: the [GestureConfig] sliders. Switch here to adjust, then tab back to test.
 *
 * The config sliders re-classify the last trace live, sharing the exact classifier the IME
 * integration will use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureLabScreen(onNavigateBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val prefs = PrefsManager.getPrefs(LocalContext.current)

    // Hoisted state — persists across tab switches AND to prefs, so the live IME engine reads the
    // same tuning (GestureConfig.fromPrefs). Every change writes through.
    var config by remember { mutableStateOf(GestureConfig.fromPrefs(prefs)) }
    val applyConfig: (GestureConfig) -> Unit = { config = it; it.writeTo(prefs) }
    val pathPx: SnapshotStateList<Offset> = remember { mutableStateListOf() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTrace by remember { mutableStateOf<GestureTrace?>(null) }
    var prevTapForLast by remember { mutableStateOf<GestureTrace?>(null) }
    var pendingPrevTap by remember { mutableStateOf<GestureTrace?>(null) }

    // CKB key grid: device-config geometry if present, else a runtime capture, else none.
    val context = LocalContext.current
    val resolvedGrid = remember { CkbKeyGrid.resolve(context) }
    var showGrid by remember { mutableStateOf(false) }

    val classification: GestureClassification? = remember(lastTrace, prevTapForLast, config) {
        lastTrace?.let { GestureClassifier(config).classify(it, prevTapForLast) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gesture Lab") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Test") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Tuning") })
            }

            when (selectedTab) {
                0 -> TestTab(
                    pathPx = pathPx,
                    classification = classification,
                    lastTrace = lastTrace,
                    config = config,
                    onBoxSize = { boxSize = it },
                    onTraceComplete = { trace ->
                        prevTapForLast = pendingPrevTap
                        pendingPrevTap = trace
                        lastTrace = trace
                    },
                    boxSize = boxSize,
                    grid = resolvedGrid,
                    showGrid = showGrid,
                    onShowGridChange = { showGrid = it },
                )
                else -> TuningTab(config = config, onConfigChange = applyConfig)
            }
        }
    }
}

@Composable
private fun TestTab(
    pathPx: SnapshotStateList<Offset>,
    classification: GestureClassification?,
    lastTrace: GestureTrace?,
    config: GestureConfig,
    boxSize: IntSize,
    onBoxSize: (IntSize) -> Unit,
    onTraceComplete: (GestureTrace) -> Unit,
    grid: CkbKeyGrid.Resolved?,
    showGrid: Boolean,
    onShowGridChange: (Boolean) -> Unit,
) {
    // No verticalScroll here: a drag on the surface must never be consumed by a scroller.
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Drag on the surface to draw a gesture. Hold still for tap/hold; two quick taps for double-tap. Switch to Tuning to adjust thresholds.",
            style = MaterialTheme.typography.bodySmall
        )
        // The grid overlay is only offered when a real grid exists (device config or capture).
        if (grid != null) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Key grid overlay" + if (showGrid) "  (${grid.source})" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = showGrid, onCheckedChange = onShowGridChange)
        }
        } // if (grid != null)
        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF1E1E1E))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val ref = min(boxSize.width, boxSize.height).toFloat().coerceAtLeast(1f)
                        val raw = ArrayList<TracePoint>()
                        pathPx.clear()
                        pathPx.add(down.position)
                        raw.add(TracePoint(down.position.x / ref, down.position.y / ref, down.uptimeMillis))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pathPx.add(change.position)
                            raw.add(TracePoint(change.position.x / ref, change.position.y / ref, change.uptimeMillis))
                            if (!change.pressed) break
                        }
                        onTraceComplete(GestureTrace(raw))
                    }
                }
        ) {
            onBoxSize(IntSize(size.width.toInt(), size.height.toInt()))

            if (grid != null && showGrid) {
                // Letterbox the keypad rect (CkbKeyGrid.ASPECT) into the canvas, centered.
                var gw = size.width
                var gh = gw / CkbKeyGrid.ASPECT
                if (gh > size.height) { gh = size.height; gw = gh * CkbKeyGrid.ASPECT }
                val ox = (size.width - gw) / 2f
                val oy = (size.height - gh) / 2f
                drawRect(Color(0x33FFFFFF), topLeft = Offset(ox, oy), size = androidx.compose.ui.geometry.Size(gw, gh), style = Stroke(width = 2f))
                val cellH = grid.cells.firstOrNull()?.h ?: 0.2f
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(170, 200, 200, 200)
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    textSize = cellH * gh * 0.5f
                }
                for (key in grid.cells) {
                    val left = ox + (key.cx - key.w / 2f) * gw
                    val top = oy + (key.cy - key.h / 2f) * gh
                    drawRect(Color(0x22FFFFFF), topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(key.w * gw, key.h * gh), style = Stroke(width = 1.5f))
                    drawContext.canvas.nativeCanvas.drawText(
                        key.label,
                        ox + key.cx * gw,
                        oy + key.cy * gh + paint.textSize * 0.35f,
                        paint
                    )
                }
            }

            if (pathPx.size >= 2) {
                var minX = pathPx[0].x; var maxX = pathPx[0].x
                var minY = pathPx[0].y; var maxY = pathPx[0].y
                for (p in pathPx) {
                    minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
                    minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
                }
                drawRect(
                    color = Color(0x3300BCD4),
                    topLeft = Offset(minX, minY),
                    size = androidx.compose.ui.geometry.Size(maxX - minX, maxY - minY),
                    style = Stroke(width = 2f)
                )
                for (i in 1 until pathPx.size) {
                    drawLine(Color(0xFF4FC3F7), pathPx[i - 1], pathPx[i], strokeWidth = 5f)
                }
                drawCircle(Color(0xFF66BB6A), radius = 12f, center = pathPx.first())
                drawCircle(Color(0xFFEF5350), radius = 12f, center = pathPx.last())
                drawLine(Color(0xFFFFB300), pathPx.first(), pathPx.last(), strokeWidth = 3f)
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Verdict", style = MaterialTheme.typography.labelLarge)
                Text(
                    classification?.label ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    classification?.let { ActionPreview.describe(it) } ?: "Draw a gesture above",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Trace features", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                val t = lastTrace
                val text = if (t == null || t.isDegenerate) "—" else buildString {
                    appendLine("displacement:  ${"%.3f".format(t.displacement)}    straightness: ${"%.3f".format(t.straightness)}")
                    appendLine("angle:         ${"%.1f".format(t.dominantAngleDeg)}°    duration: ${t.durationMs} ms")
                    appendLine("mean spd:      ${"%.2f".format(t.meanSpeed)} u/s")
                    append("terminal spd:  ${"%.2f".format(t.terminalSpeed(config.terminalWindowMs))} u/s")
                }
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun TuningTab(config: GestureConfig, onConfigChange: (GestureConfig) -> Unit) {
    val defaults = remember { GestureConfig() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Changes apply live and re-classify the last gesture. Tap ↺ to reset one slider. Tab back to Test to try it.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        LabRangeSlider(
            label = "Horizontal velocity (u/s): flow | swipe | flick",
            low = config.hSwipeMinVelocity,
            high = config.hFlickMinVelocity,
            defaultLow = defaults.hSwipeMinVelocity,
            defaultHigh = defaults.hFlickMinVelocity,
            min = 0f, max = 8f,
        ) { lo, hi ->
            onConfigChange(config.copy(hSwipeMinVelocity = lo, hFlickMinVelocity = hi))
        }
        LabRangeSlider(
            label = "Vertical velocity (u/s): flow | swipe | flick",
            low = config.vSwipeMinVelocity,
            high = config.vFlickMinVelocity,
            defaultLow = defaults.vSwipeMinVelocity,
            defaultHigh = defaults.vFlickMinVelocity,
            min = 0f, max = 8f,
        ) { lo, hi ->
            onConfigChange(config.copy(vSwipeMinVelocity = lo, vFlickMinVelocity = hi))
        }
        LabSlider("minSwipeDistance", config.minSwipeDistance, defaults.minSwipeDistance, 0.02f, 0.4f) {
            onConfigChange(config.copy(minSwipeDistance = it))
        }
        LabSlider("swipeStraightnessMin", config.swipeStraightnessMin, defaults.swipeStraightnessMin, 0.5f, 1f) {
            onConfigChange(config.copy(swipeStraightnessMin = it))
        }
        LabSlider("flowStraightnessMax", config.flowStraightnessMax, defaults.flowStraightnessMax, 0.5f, 1f) {
            onConfigChange(config.copy(flowStraightnessMax = it))
        }
        LabSlider("flowTerminalSpeedMax (u/s)", config.flowTerminalSpeedMax, defaults.flowTerminalSpeedMax, 0.1f, 3f) {
            onConfigChange(config.copy(flowTerminalSpeedMax = it))
        }
        LabSlider("tapSlop", config.tapSlop, defaults.tapSlop, 0.01f, 0.2f) {
            onConfigChange(config.copy(tapSlop = it))
        }
        LabSlider("holdMinDuration (ms)", config.holdMinDurationMs.toFloat(), defaults.holdMinDurationMs.toFloat(), 100f, 800f) {
            onConfigChange(config.copy(holdMinDurationMs = it.toLong()))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onConfigChange(GestureConfig()) }) { Text("Reset all") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabRangeSlider(
    label: String,
    low: Float,
    high: Float,
    defaultLow: Float,
    defaultHigh: Float,
    min: Float,
    max: Float,
    onChange: (Float, Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%.2f–%.2f".format(low, high), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                IconButton(onClick = { onChange(defaultLow, defaultHigh) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reset $label", modifier = Modifier.size(16.dp))
                }
            }
        }
        RangeSlider(
            value = low..high,
            onValueChange = { onChange(it.start, it.endInclusive) },
            valueRange = min..max,
        )
    }
}

@Composable
private fun LabSlider(label: String, value: Float, default: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%.3f".format(value), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                IconButton(onClick = { onChange(default) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reset $label", modifier = Modifier.size(16.dp))
                }
            }
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}
