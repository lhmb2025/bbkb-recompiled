package dev.bbkb.ime.core.device.interceptor;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.shared.Logger;

/**
 * Keeps the {@link KeyInterceptorService} component in step with the device profile. Android lists
 * an accessibility service only while its component is enabled, so a profile with
 * {@code <accessibility-helper>off} disables it (and Android switches the service off); other phones
 * keep the manifest default (enabled). {@link DeviceProfile} calls {@link #apply} on every profile
 * resolve, so a fresh install lists the helper until BBKB first runs. Writes only on a change.
 */
public final class KeyInterceptorComponent {

    private static final String TAG = "KeyInterceptorComponent";

    private KeyInterceptorComponent() {
        // Static helpers only.
    }

    /** Whether the profile says this phone never needs the helper. */
    public static boolean isOffForDevice(@Nullable DeviceInputMapping mapping) {
        return mapping != null && mapping.accessibilityHelperOff;
    }

    /** {@link #isOffForDevice} for the profile resolved on this phone. */
    public static boolean isNotRequiredOnThisPhone() {
        final DeviceProfile profile = DeviceProfile.current();
        return profile != null && isOffForDevice(profile.getDeviceMapping());
    }

    /** The component state {@link #apply} writes for {@code mapping}. */
    static int wantedState(@Nullable DeviceInputMapping mapping) {
        return isOffForDevice(mapping)
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    /** Disable or restore the helper's component to match {@code mapping}. */
    public static void apply(@NonNull Context context, @Nullable DeviceInputMapping mapping) {
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final ComponentName component = new ComponentName(app, KeyInterceptorService.class);
        final int wanted = wantedState(mapping);
        try {
            final PackageManager pm = app.getPackageManager();
            if (pm.getComponentEnabledSetting(component) == wanted) return;
            // DONT_KILL_APP: this runs inside the keyboard and the settings screen themselves.
            pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP);
            Logger.info(TAG, "BBKB helper component "
                    + (wanted == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    ? "disabled: not required on this phone" : "restored to the manifest default"));
        } catch (RuntimeException e) {
            Logger.warn(TAG, "Could not change the BBKB helper component: " + e);
        }
    }
}
