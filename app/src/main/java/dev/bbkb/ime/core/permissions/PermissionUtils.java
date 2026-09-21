package dev.bbkb.ime.core.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;



public final class PermissionUtils {
    public static List<String> getDeniedPermissions(Context context, String... strArr) {
        ArrayList arrayList = new ArrayList();
        for (String str : strArr) {
            if (ContextCompat.checkSelfPermission(context, str) != 0) {
                arrayList.add(str);
            }
        }
        return arrayList;
    }

    public static void requestPermissions(Activity activity, int i, String[] strArr) {
        ActivityCompat.requestPermissions(activity, strArr, i);
    }

    public static boolean allGranted(int[] iArr) {
        for (int i : iArr) {
            if (i != 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean checkAllPermissionsGranted(Context context, String... strArr) {
        for (String str : strArr) {
            if (ContextCompat.checkSelfPermission(context, str) != 0) {
                return false;
            }
        }
        return true;
    }

    public static void showRationaleDialog(Context context, String str, String str2, String str3) {
        Intent intent = new Intent();
        intent.setClass(context, RationaleDialog.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("permission", str2);
        intent.putExtra("explanation_text", str);
        intent.putExtra("result_receiver", str3);
        context.startActivity(intent);
    }

    public static boolean shouldShowRationale(Activity activity, String str) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, str);
    }

    public static void openApplicationSettings(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent();
        intent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
        intent.addCategory("android.intent.category.DEFAULT");
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }
}
