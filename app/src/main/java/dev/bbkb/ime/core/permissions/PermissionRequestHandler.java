package dev.bbkb.ime.core.permissions;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Message;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.core.shared.InAppEventBus;
import dev.bbkb.ime.BuildConfig;



public class PermissionRequestHandler implements InAppEventBus.EventListener, PermissionCoordinator.GrantResultCallback {

    public static final String ACTION_RATIONALE_RESULT = "com.blackberry.rational.result.receiver";
    public static final String EXTRA_PERMISSION = "permission";
    public static final String EXTRA_RESULT_CODE = "result_code";

    private final Context mContext;

    private Callback mCallback;

    private final AskHandler mAskHandler = new AskHandler(this);

    private String mPermission;

    
    public interface Callback {
        void onPermissionResult(String str, Result cVar);
    }

    
    public enum Result {
        PERMISSION_GRANTED,
        PERMISSION_DENIED,
        PERMISSION_BLOCKED,
        OTHER
    }

    public PermissionRequestHandler(Context context, Callback bVar) {
        this.mContext = context;
        this.mCallback = bVar;
    }

    public synchronized void requestPermission(String str, String str2) {
        this.mPermission = str;
        if (!PermissionUtils.checkAllPermissionsGranted(this.mContext, str)) {
            InAppEventBus.getInstance().subscribe(ACTION_RATIONALE_RESULT, this);
            PermissionUtils.showRationaleDialog(this.mContext, str2, str, ACTION_RATIONALE_RESULT);
        }
    }

    @Override
    public synchronized void onGrantResult(boolean z, boolean z2) {
        Result cVar;
        try {
            if (z) {
                cVar = Result.PERMISSION_GRANTED;
            } else if (!z2) {
                cVar = Result.PERMISSION_BLOCKED;
                markBlocked(this.mPermission);
            } else {
                cVar = Result.PERMISSION_DENIED;
            }
            this.mCallback.onPermissionResult(this.mPermission, cVar);
        } catch (Throwable th) {
            throw th;
        }
    }

    @Override
    public synchronized void onEvent(String action, Bundle extras) {
        if (ACTION_RATIONALE_RESULT.equals(action) && extras != null) {
            int resultCode = extras.getInt(EXTRA_RESULT_CODE, 3);
            RationaleDialog.Result result = RationaleDialog.Result.values()[resultCode];
            String stringExtra = extras.getString(EXTRA_PERMISSION);
            switch (result) {
                case ASK:
                    this.mAskHandler.postAsk(stringExtra);
                    break;
                case DENY:
                    this.mCallback.onPermissionResult(this.mPermission, Result.PERMISSION_DENIED);
                    break;
                case BLOCKED:
                    this.mCallback.onPermissionResult(this.mPermission, Result.PERMISSION_BLOCKED);
                    break;
                case OTHER:
                    this.mCallback.onPermissionResult(this.mPermission, Result.OTHER);
                    break;
            }
            unsubscribe();
        }
    }

    private void unsubscribe() {
        InAppEventBus.getInstance().unsubscribe(ACTION_RATIONALE_RESULT, this);
    }

    private void markBlocked(String str) {
        // apply(), not commit(): this runs on the main thread from onGrantResult and nothing
        // reads the result of the write.
        PrefsManager.INSTANCE.getPrefs(this.mContext).edit().putBoolean(str, true).apply();
    }

    
    public static final class AskHandler extends WeakOwnerHandler<PermissionRequestHandler> {
        public AskHandler(PermissionRequestHandler c0719a) {
            super(c0719a);
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            PermissionRequestHandler c0719aV = getOwner();
            if (c0719aV != null && message.what == 0) {
                if (BuildConfig.DEBUG) Log.i("PRH", "Asking for permission");
                PermissionCoordinator.getInstance(c0719aV.mContext).requestPermissions(c0719aV, null, (String) message.obj);
            }
        }

        public void postAsk(String str) {
            sendMessage(obtainMessage(0, str));
        }
    }
}
