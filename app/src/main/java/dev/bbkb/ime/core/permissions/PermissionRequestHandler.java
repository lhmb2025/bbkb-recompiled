package dev.bbkb.ime.core.permissions;

import android.content.Context;

/** Asks for one runtime permission from the keyboard; see {@link PermissionActivity}. */
public class PermissionRequestHandler {

    private final Context mContext;

    private final Callback mCallback;

    public interface Callback {
        void onPermissionResult(String permission, Result result);
    }

    public enum Result {
        PERMISSION_GRANTED,
        PERMISSION_DENIED,
        PERMISSION_BLOCKED,
        OTHER
    }

    public PermissionRequestHandler(Context context, Callback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    /** Does nothing, and never calls back, when the permission is already granted. */
    public void requestPermission(String permission, String explanation) {
        if (!PermissionUtils.checkAllPermissionsGranted(this.mContext, permission)) {
            PermissionActivity.request(this.mContext, permission, explanation, this.mCallback);
        }
    }
}
