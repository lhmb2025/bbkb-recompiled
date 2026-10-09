package dev.bbkb.ime.core.settings.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.touch.KeypadTouchSources
import dev.bbkb.ime.core.device.touch.TouchSourceSelector.Choice
import dev.bbkb.ime.core.device.touch.TouchSourceStatus
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.Reason
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.State
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine

/**
 * One reading of [KeypadTouchSources], in the terms the Touch surface helper and its two link rows
 * (Device compatibility, Physical keyboard) show. Nothing here decides anything about the sources;
 * it only picks the words for what they report.
 */
data class TouchSurfaceState(
    /** The profile describes a touch surface over its keys: the rows exist at all. */
    val declared: Boolean,
    val choice: Choice,
    /** The native route can apply on this Android version: the Built-in section shows. */
    val showBuiltIn: Boolean,
    /** The selector gives the pad to the Shizuku reader: the Shizuku section shows. */
    val showShizuku: Boolean,
    /** The chosen source's status (the selection's reason when none is chosen). */
    val active: TouchSourceStatus,
    /** The Shizuku reader's status (NOT_APPLICABLE when it is not the choice). */
    val shizuku: TouchSourceStatus,
) {

    /**
     * The one-line status the helper's header and both link rows show. A phone whose profile
     * declares no touch surface reads "not required": there is nothing for the helper to do there,
     * which is different from a declared pad stuck in a state no other line names ("unavailable").
     */
    @get:StringRes
    val statusLine: Int
        get() = if (!declared) R.string.settings_status_not_required else when (choice) {
            Choice.NATIVE -> when {
                active.reason == Reason.PAD_NOT_ENUMERATED -> R.string.touch_surface_status_native_off
                active.state == State.ACTIVE || active.state == State.IDLE ->
                    R.string.touch_surface_status_native_working
                else -> R.string.touch_surface_status_unavailable
            }
            Choice.SHIZUKU -> when (active.state) {
                State.ACTIVE -> R.string.touch_surface_status_shizuku_working
                State.IDLE -> R.string.touch_surface_status_shizuku_ready
                else -> when (active.reason) {
                    Reason.SHIZUKU_NOT_INSTALLED -> R.string.touch_surface_status_shizuku_not_installed
                    Reason.SHIZUKU_NOT_RUNNING -> R.string.touch_surface_status_shizuku_not_running
                    Reason.SHIZUKU_NOT_GRANTED, Reason.SHIZUKU_DENIED ->
                        R.string.touch_surface_status_shizuku_not_granted
                    Reason.SHIZUKU_OUTDATED -> R.string.touch_surface_status_shizuku_outdated
                    Reason.SHIZUKU_UNSUPPORTED -> R.string.touch_surface_status_android_unsupported
                    Reason.PAD_NOT_FOUND -> R.string.touch_surface_status_pad_not_found
                    Reason.READER_FAILED -> R.string.touch_surface_status_reader_failed
                    else -> R.string.touch_surface_status_unavailable
                }
            }
            Choice.NONE -> when (active.reason) {
                Reason.PAD_NOT_ENUMERATED -> R.string.touch_surface_status_native_off
                Reason.OS_DOES_NOT_DELIVER_PAD -> R.string.touch_surface_status_android_unsupported
                else -> R.string.touch_surface_status_unavailable
            }
        }

    /** Whether the line reads as working (the header's tick) rather than as something to do. */
    val isWorking: Boolean
        get() = statusLine == R.string.touch_surface_status_native_working ||
            statusLine == R.string.touch_surface_status_shizuku_working ||
            statusLine == R.string.touch_surface_status_shizuku_ready

    /** Shizuku's own state, for the Shizuku section's status row. */
    @get:StringRes
    val shizukuStateLine: Int
        get() = when (shizuku.reason) {
            Reason.SHIZUKU_NOT_INSTALLED -> R.string.touch_surface_shizuku_state_not_installed
            Reason.SHIZUKU_NOT_RUNNING -> R.string.touch_surface_shizuku_state_not_running
            Reason.SHIZUKU_NOT_GRANTED -> R.string.touch_surface_shizuku_state_not_granted
            Reason.SHIZUKU_DENIED -> R.string.touch_surface_shizuku_state_denied
            Reason.SHIZUKU_OUTDATED -> R.string.touch_surface_shizuku_state_outdated
            Reason.SHIZUKU_UNSUPPORTED -> R.string.touch_surface_status_android_unsupported
            // Running with access: streaming, ready, or past Shizuku and stuck on the pad itself.
            else -> R.string.touch_surface_shizuku_state_granted
        }

    /** Grant access only means something while Shizuku runs and has not been asked yet. */
    val canRequestShizukuAccess: Boolean
        get() = shizuku.reason == Reason.SHIZUKU_NOT_GRANTED

    val shizukuAccessDenied: Boolean
        get() = shizuku.reason == Reason.SHIZUKU_DENIED

    companion object {
        /** Read the sources now. */
        fun read(): TouchSurfaceState {
            val selection = KeypadTouchSources.selection()
            return TouchSurfaceState(
                declared = KeypadTouchSources.declaresTouchKeypad(),
                choice = selection.choice,
                showBuiltIn = selection.nativeRouteApplies,
                showShizuku = selection.choice == Choice.SHIZUKU,
                active = KeypadTouchSources.activeStatus(),
                shizuku = KeypadTouchSources.shizukuStatus(),
            )
        }
    }
}

/**
 * The [TouchSurfaceState] for a screen, read again on every resume (after [KeypadTouchSources.refresh]
 * has re-scanned for the pad and re-read Shizuku: coming back from the Scroll assistant settings,
 * or from the Shizuku app, sends no event) and on every Shizuku status change while the screen is
 * up (the answer to Grant access arrives that way). On a device whose profile declares no pad this
 * only reads a cached selection: Shizuku is never touched.
 */
@Composable
fun rememberTouchSurfaceState(): TouchSurfaceState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(TouchSurfaceState.read()) }

    DisposableEffect(lifecycleOwner) {
        val listener = ShizukuTouchEngine.StatusListener { state = TouchSurfaceState.read() }
        val listening = state.showShizuku
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                KeypadTouchSources.refresh(context)
                state = TouchSurfaceState.read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (listening) KeypadTouchSources.addShizukuListener(context, listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (listening) KeypadTouchSources.removeShizukuListener(listener)
        }
    }
    return state
}
