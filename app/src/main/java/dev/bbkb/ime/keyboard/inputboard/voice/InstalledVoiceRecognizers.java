package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.shared.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The device side of {@link VoiceRecognizerChoice}: what is installed, what the "Speech recognizer"
 * setting resolves to now, and the recogniser built from it. Dictation, the voice-input gate and the
 * voice language picker all come through here, so they always agree on which service they mean.
 */
public final class InstalledVoiceRecognizers {

    private static final String TAG = "VoiceRecognition";

    private InstalledVoiceRecognizers() {
    }

    /** Every installed recognition service, labelled by its app, the phone's default marked. */
    public static List<VoiceRecognizerChoice.Provider> query(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        final PackageManager pm = context.getPackageManager();
        final List<VoiceRecognizerChoice.Service> services = new ArrayList<>();
        try {
            final List<ResolveInfo> found = pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            if (found != null) {
                for (ResolveInfo info : found) {
                    final ServiceInfo service = info.serviceInfo;
                    if (service != null) {
                        services.add(new VoiceRecognizerChoice.Service(service.packageName, service.name,
                                appLabel(pm, service.packageName)));
                    }
                }
            }
        } catch (RuntimeException e) {
            Logger.debug(TAG, "Could not list recognition services: " + e);
        }
        return VoiceRecognizerChoice.buildProviders(services, systemDefaultService(context));
    }

    /** Whether the "On-device recognizer" choice can work here. */
    public static boolean isOnDeviceAvailable(Context context) {
        if (context == null || Build.VERSION.SDK_INT < VoiceRecognizerChoice.ON_DEVICE_MIN_SDK) {
            return false;
        }
        try {
            return SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The setting as stored. */
    public static String setting(Context context) {
        return SettingsManager.getVoiceInputRecognizer(PrefsManager.INSTANCE.getPrefs(context));
    }

    /**
     * The setting resolved against this device now. The package query only runs for a chosen app,
     * so the common case (the system default) costs nothing.
     */
    public static VoiceRecognizerChoice.Selection resolve(Context context) {
        final String setting = setting(context);
        if (setting == null || setting.trim().isEmpty()) {
            return VoiceRecognizerChoice.Selection.DEFAULT;
        }
        final boolean onDevice = VoiceRecognizerChoice.ON_DEVICE.equals(setting.trim());
        final VoiceRecognizerChoice.Selection selection = VoiceRecognizerChoice.select(setting, Build.VERSION.SDK_INT,
                onDevice && isOnDeviceAvailable(context),
                onDevice ? Collections.<VoiceRecognizerChoice.Provider>emptyList() : query(context));
        if (selection.kind == VoiceRecognizerChoice.Kind.SYSTEM_DEFAULT) {
            Logger.debug(TAG, "Speech recognizer " + setting + " is not available; using the system default");
        }
        return selection;
    }

    /** A new recogniser for {@code selection}. */
    public static SpeechRecognizer create(Context context, VoiceRecognizerChoice.Selection selection) {
        if (selection != null && selection.kind == VoiceRecognizerChoice.Kind.COMPONENT) {
            final ComponentName component = ComponentName.unflattenFromString(selection.component);
            if (component != null) {
                return SpeechRecognizer.createSpeechRecognizer(context, component);
            }
        }
        if (selection != null && selection.kind == VoiceRecognizerChoice.Kind.ON_DEVICE
                && Build.VERSION.SDK_INT >= VoiceRecognizerChoice.ON_DEVICE_MIN_SDK) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        }
        return SpeechRecognizer.createSpeechRecognizer(context);
    }

    /** The secure {@code voice_recognition_service} value, or null when it cannot be read. */
    static String systemDefaultService(Context context) {
        try {
            return Settings.Secure.getString(context.getContentResolver(),
                    VoiceRecognitionAvailability.SECURE_VOICE_RECOGNITION_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String appLabel(PackageManager pm, String packageName) {
        try {
            final CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
            return label == null ? null : label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
