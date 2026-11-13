package dev.bbkb.ime.core.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import dev.bbkb.ime.core.settings.util.DebugSettingsUtils;
import dev.bbkb.ime.BuildConfig;

/** Clears all settings on a TCL device reset broadcast. */


public class DeviceResetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PackageManager packageManager = context.getPackageManager();
        if ("tcl.intent.action.LAUNCH_DEVICE_RESET".equals(intent.getAction()) && packageManager.hasSystemFeature("tcl.software.device_reset")) {
            if (BuildConfig.DEBUG) Log.i("DeviceReset", "Device reset received");
            DebugSettingsUtils.INSTANCE.clearAllSettings(context);
        }
    }
}
