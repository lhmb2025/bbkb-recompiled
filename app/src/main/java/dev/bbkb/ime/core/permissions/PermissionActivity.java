package dev.bbkb.ime.core.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;


public final class PermissionActivity extends Activity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private int requestCode = -1;

    public static void start(Context context, int i, String... strArr) {
        Intent intent = new Intent(context.getApplicationContext(), (Class<?>) PermissionActivity.class);
        intent.putExtra("requested_permissions", strArr);
        intent.putExtra("request_code", i);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.requestCode = bundle != null ? bundle.getInt("request_code", -1) : -1;
    }

    @Override // android.app.Activity
    protected void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        bundle.putInt("request_code", this.requestCode);
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        if (this.requestCode == -1) {
            Bundle extras = getIntent().getExtras();
            String[] stringArray = extras.getStringArray("requested_permissions");
            this.requestCode = extras.getInt("request_code");
            PermissionUtils.requestPermissions(this, this.requestCode, stringArray);
        }
    }

    // Note: onRequestPermissionsResult is deprecated in favor of Activity Result APIs, but this
    // dedicated permission Activity requires base Activity class for specific lifecycle handling.
    @SuppressWarnings("deprecation")
    @Override // android.app.Activity
    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        boolean zShouldShowRequestPermissionRationale;
        if (Build.VERSION.SDK_INT > 22) {
            zShouldShowRequestPermissionRationale = false;
            for (int i2 = 0; i2 < strArr.length && !zShouldShowRequestPermissionRationale; i2++) {
                zShouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale(strArr[i2]);
            }
        } else {
            zShouldShowRequestPermissionRationale = false;
        }
        this.requestCode = -1;
        PermissionCoordinator.getInstance(this).onRequestPermissionsResult(i, strArr, iArr, zShouldShowRequestPermissionRationale);
        finish();
    }
}
