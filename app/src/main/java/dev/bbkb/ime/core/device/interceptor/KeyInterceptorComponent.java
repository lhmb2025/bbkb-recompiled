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
 * Keeps the BBKB helper's service component ({@link KeyInterceptorService}) in step with the
 * device profile.
 *
 * <p>Android lists an app's accessibility service under Settings &gt; Accessibility only while its
 * component is enabled. On a phone whose profile says {@code <accessibility-helper>off} (BlackBerry
 * hardware, whose firmware delivers every key the keyboard handles) the component is disabled, so
 * the helper is not offered there at all; if it had been switched on, Android turns it off. Every
 * other phone gets the manifest default, which is enabled.
 *
 * <p>The manifest cannot read the profiles, so a fresh install lists the helper until BBKB first
 * runs on the phone; {@link DeviceProfile} calls {@link #apply} each time it resolves the profile
 * (keyboard start-up, the settings screen, a device-configuration switch). The change survives app
 * updates and reboots, and is written only when it differs from what the system already holds.
 */
public final class KeyInterceptorComponent {

    private static final String TAG = "KeyInterceptorComponent";

    private KeyInterceptorComponent() {
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
