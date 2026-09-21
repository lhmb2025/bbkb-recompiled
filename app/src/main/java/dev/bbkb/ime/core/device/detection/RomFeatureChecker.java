package dev.bbkb.ime.core.device.detection;

import android.os.Build;

import com.blackberry.nuanceshim.NuanceSDK;

import dev.bbkb.ime.core.shared.SystemProps;



public final class RomFeatureChecker {

    private static Boolean sGoogleServicesDisabled;

    public static boolean isGoogleServicesDisabled() {
        if (sGoogleServicesDisabled == null) {
            sGoogleServicesDisabled =
                    "disabled".equals(SystemProps.get("ro.product.google.services"))
                            ? Boolean.TRUE : Boolean.FALSE;
        }
        return sGoogleServicesDisabled.booleanValue();
    }

    public static boolean isBlackBerryMercury() {
        return "blackberry".equals(Build.BRAND) && NuanceSDK.DEVICE_MERCURY.equals(Build.DEVICE);
    }
}
