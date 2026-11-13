package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.device.config.builder.CaptureStep
import dev.bbkb.ime.core.device.config.builder.CapturedKey
import dev.bbkb.ime.core.device.config.builder.DeviceFacts
import dev.bbkb.ime.core.device.config.builder.DeviceProfileBuilder
import dev.bbkb.ime.core.device.config.builder.DeviceProfileExporter
import dev.bbkb.ime.core.device.config.builder.GuidedCapture
import dev.bbkb.ime.core.device.config.builder.HardwareKeyCaptureBus
import dev.bbkb.ime.core.device.config.builder.KeyCodeNames
import dev.bbkb.ime.core.settings.ui.EditTextPreference
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceTrailingLabel
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.R
import java.io.File

/**
 * Builds a device config from the hardware in hand: detected facts, the keys the user must press,
 * and the resulting profile. Bezel keys are captured because one scancode maps to different key
 * codes across ROMs, so no built-in table can be right. Keys arrive via [HardwareKeyCaptureBus]
 * because the accessibility service consumes Sym, Emoji and Mic before any window sees them; each
 * press is delivered by only one route.
 */
@Composable
fun DeviceProfileBuilderScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // Detection reads InputDevices and parses the shipped configs: do it once, not per recomposition.
    val facts = remember { DeviceProfileBuilder.detect(context) }
    val capture = remember { GuidedCapture() }

    // GuidedCapture is a plain mutable object; this counter tells Compose it moved. All key event
    // producers (accessibility service, IME, activity) are on the main thread.
    var captureTick by remember { mutableIntStateOf(0) }
    var sawAnyKey by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf(facts.suggestedProfileName()) }
    var savedFile by remember { mutableStateOf<File?>(null) }

    DisposableEffect(Unit) {
        val listener = HardwareKeyCaptureBus.Listener { scanCode, keyCode, deviceId, repeatCount,
                                                        down, eventTime ->
            val used = if (down) {
                capture.onKeyDown(scanCode, keyCode, deviceId, repeatCount, eventTime)
            } else {
                capture.onKeyUp(scanCode, keyCode, deviceId)
            }
            if (used) {
                sawAnyKey = true
                captureTick++
            }
            used
        }
        HardwareKeyCaptureBus.register(listener)
        onDispose { HardwareKeyCaptureBus.unregister(listener) }
    }

    // With no accessibility service and no bound editor, keys arrive at the focused window.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    SettingsScaffold(
        title = stringResource(R.string.device_profile_builder_title),
        onNavigateBack = onNavigateBack,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                native.deviceId >= 0 && HardwareKeyCaptureBus.offer(
                    native.scanCode,
                    native.keyCode,
                    native.deviceId,
                    native.repeatCount,
                    native.action == AndroidKeyEvent.ACTION_DOWN,
                    native.eventTime,
                )
            },
        // Sharing is a row further down: a profile can only be shared after it exists.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    savedFile = saveProfile(context, facts, capture, profileName)
                },
                icon = { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_check_outlined), contentDescription = null) },
                text = { Text(stringResource(R.string.device_profile_save)) },
            )
        },
    ) { paddingValues ->

        // Each short, fixed run of rows is one list item so PreferenceGroup gives it corners and inset.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            item { PreferenceCategory(title = stringResource(R.string.device_profile_category_detected)) }
            item { PreferenceGroup { DetectedRows(facts) } }

            item { PreferenceCategory(title = stringResource(R.string.device_profile_category_capture)) }
            item { PreferenceInfo(text = stringResource(R.string.device_profile_capture_intro)) }
            if (!sawAnyKey) {
                item { PreferenceInfo(text = stringResource(R.string.device_profile_capture_no_keyboard)) }
            }
            item {
                PreferenceGroup {
                    // Reading captureTick here subscribes this row and the step rows below it to capture changes.
                    val answered = captureTick.let { capture.answered() }
                    PreferenceItem(
                        title = if (capture.isComplete()) {
                            stringResource(R.string.device_profile_capture_complete)
                        } else {
                            stringResource(
                                R.string.device_profile_capture_progress,
                                answered,
                                capture.steps().size,
                            )
                        },
                        trailing = if (answered > 0) {
                            {
                                TextButton(onClick = {
                                    capture.reset()
                                    captureTick++
                                }) { Text(stringResource(R.string.device_profile_action_restart)) }
                            }
                        } else null,
                    )
                    capture.steps().forEach { step ->
                        CaptureStepRow(
                            step = step,
                            captured = captureTick.let { capture.resultFor(step) },
                            isCurrent = capture.current() == step,
                            isHeld = capture.isKeyHeld(),
                            isDuplicate = capture.duplicateSteps().contains(step),
                            onSkip = {
                                capture.skip()
                                captureTick++
                            },
                            onRedo = {
                                capture.reset()
                                captureTick++
                            },
                        )
                    }
                }
            }

            item { PreferenceCategory(title = stringResource(R.string.device_profile_category_profile)) }
            item {
                PreferenceGroup {
                    EditTextPreference(
                        title = stringResource(R.string.device_profile_name),
                        value = profileName,
                        onValueChange = { profileName = it.trim().ifEmpty { facts.suggestedProfileName() } },
                    )
                    PreferenceItem(
                        title = stringResource(R.string.device_profile_summary_type),
                        trailing = { PreferenceTrailingLabel(facts.deviceType()) },
                    )
                    val count = captureTick.let { capture.usableResults().size }
                    PreferenceItem(
                        title = stringResource(R.string.device_profile_summary_keys),
                        trailing = {
                            PreferenceTrailingLabel(
                                stringResource(R.string.device_profile_summary_keys_value, count)
                            )
                        },
                    )
                    val file = savedFile
                    val shareTitle = stringResource(R.string.device_profile_share)
                    val chooserTitle = stringResource(R.string.device_profile_share_chooser)
                    val notSavedYet = stringResource(R.string.device_profile_share_before_save)
                    val noSharer = stringResource(R.string.device_profile_share_unavailable)
                    PreferenceItem(
                        title = shareTitle,
                        summary = stringResource(R.string.device_profile_share_summary),
                        onClick = {
                            if (file == null) {
                                toast(context, notSavedYet)
                            } else {
                                val intent = DeviceProfileExporter.shareIntent(context, file, chooserTitle)
                                if (intent == null) {
                                    toast(context, noSharer)
                                } else {
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { toast(context, noSharer) }
                                }
                            }
                        },
                    )
                }
            }
            savedFile?.let { file ->
                item {
                    PreferenceInfo(
                        text = stringResource(R.string.device_profile_saved_path, file.absolutePath)
                    )
                }
            }

            // Keeps the last row clear of the extended FAB.
            item { Spacer(modifier = Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun DetectedRows(facts: DeviceFacts) {
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_model),
        summary = facts.buildModel.ifEmpty { "—" },
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_build_device),
        summary = facts.buildDevice.ifEmpty { "—" },
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_manufacturer),
        summary = facts.buildManufacturer.ifEmpty { "—" },
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_brand),
        summary = facts.buildBrand.ifEmpty { "—" },
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_rom),
        summary = facts.romDisplayId.ifEmpty { "—" },
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_android),
        summary = "${facts.androidRelease} (SDK ${facts.sdkInt})",
    )
    if (facts.keyboards.isEmpty()) {
        PreferenceItem(
            title = stringResource(R.string.device_profile_detected_keyboard),
            summary = stringResource(R.string.device_profile_detected_keyboard_none),
        )
    } else {
        facts.keyboards.forEach { keyboard ->
            PreferenceItem(
                title = stringResource(R.string.device_profile_detected_keyboard),
                summary = stringResource(
                    R.string.device_profile_keyboard_summary,
                    keyboard.name,
                    keyboard.vendorProduct(),
                    "0x" + Integer.toHexString(keyboard.sources),
                ),
            )
        }
    }
    val note = if (facts.shouldDeclareKeypadLayout()) {
        stringResource(R.string.device_profile_keypad_layout_recorded)
    } else {
        stringResource(R.string.device_profile_keypad_layout_omitted)
    }
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_keypad_layout),
        summary = stringResource(
            R.string.device_profile_keypad_layout_summary,
            facts.keypadLayout,
            facts.keypadLayoutSource.name,
        ) + " · " + note,
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_touch_keypad),
        trailing = {
            PreferenceTrailingLabel(
                stringResource(
                    if (facts.hasTouchKeypad) R.string.device_profile_value_yes
                    else R.string.device_profile_value_no
                )
            )
        },
    )
    PreferenceItem(
        title = stringResource(R.string.device_profile_detected_matched_config),
        summary = facts.matchedConfigName
            ?: stringResource(R.string.device_profile_detected_matched_none),
    )
}

/**
 * One capture step. Only the current step is tinted and carries "Skip": skipping a step the
 * capture is not on has no meaning to the state machine.
 */
@Composable
private fun CaptureStepRow(
    step: CaptureStep,
    captured: CapturedKey?,
    isCurrent: Boolean,
    isHeld: Boolean,
    isDuplicate: Boolean,
    onSkip: () -> Unit,
    onRedo: () -> Unit,
) {
    val summary = when {
        captured != null && captured.skipped -> stringResource(R.string.device_profile_capture_skipped)
        captured != null -> {
            val base = stringResource(
                R.string.device_profile_capture_recorded,
                captured.scanCode,
                captured.keyCode,
                // Not KeyEvent.keyCodeToString: off a device that native call degrades to a bare number,
                // disagreeing with the config this capture writes.
                KeyCodeNames.of(captured.keyCode) ?: "—",
            )
            val withRepeat = if (captured.hasRepeatCadence()) {
                base + " · " + stringResource(
                    R.string.device_profile_capture_repeat,
                    captured.repeatIntervalMs,
                )
            } else {
                base
            }
            if (isDuplicate) {
                withRepeat + " · " + stringResource(R.string.device_profile_capture_duplicate)
            } else {
                withRepeat
            }
        }
        isCurrent && isHeld -> stringResource(R.string.device_profile_capture_holding)
        isCurrent -> stringResource(R.string.device_profile_capture_waiting)
        else -> stringResource(R.string.device_profile_capture_pending)
    }

    PreferenceItem(
        title = stepLabel(step),
        summary = summary,
        containerColor = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else null,
        trailing = when {
            isCurrent -> {
                { TextButton(onClick = onSkip) { Text(stringResource(R.string.device_profile_action_skip)) } }
            }
            captured != null -> {
                { TextButton(onClick = onRedo) { Text(stringResource(R.string.device_profile_action_redo)) } }
            }
            else -> null
        },
    )
}

@Composable
private fun stepLabel(step: CaptureStep): String = stringResource(
    when (step) {
        CaptureStep.SYM -> R.string.device_profile_step_sym
        CaptureStep.ALT -> R.string.device_profile_step_alt
        CaptureStep.SHIFT -> R.string.device_profile_step_shift
        CaptureStep.ENTER -> R.string.device_profile_step_enter
        CaptureStep.BACKSPACE -> R.string.device_profile_step_backspace
        CaptureStep.SPACE -> R.string.device_profile_step_space
        CaptureStep.SPEED -> R.string.device_profile_step_speed
        CaptureStep.EMOJI -> R.string.device_profile_step_emoji
        CaptureStep.MIC -> R.string.device_profile_step_mic
    }
)

/**
 * Serialises, validates, writes and activates. Returns the file, or null when the generated XML
 * does not survive a round trip through the config parser.
 */
private fun saveProfile(
    context: Context,
    facts: DeviceFacts,
    capture: GuidedCapture,
    profileName: String,
): File? {
    val draft = DeviceProfileBuilder.draft(facts, capture, profileName)
    val result = DeviceProfileExporter.exportAndActivate(context, draft)
    return if (result.ok()) {
        toast(context, context.getString(R.string.device_profile_saved, draft.name))
        result.file
    } else {
        toast(
            context,
            context.getString(
                R.string.device_profile_save_failed,
                result.failure ?: context.getString(R.string.settings_common_unknown_error),
            ),
        )
        null
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
