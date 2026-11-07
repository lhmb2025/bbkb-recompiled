package dev.bbkb.ime.core.permissions;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

public final class PermissionUtils {

    private PermissionUtils() {
    }

    public static boolean checkAllPermissionsGranted(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
