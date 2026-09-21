package dev.bbkb.ime.core.permissions;

import android.app.Activity;
import android.content.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;



public class PermissionCoordinator {

    private static PermissionCoordinator sInstance;

    private int lastRequestId;

    private final Context context;

    /**
     * Entries are removed when a result arrives. A flow that never delivers one - the user swipes
     * PermissionActivity away, or it is killed on a configuration change - used to leave its
     * callback (and the Context and UI callback it holds) in this process-lifetime singleton
     * forever, so the map is bounded and evicts the oldest abandoned request.
     */
    private static final int MAX_PENDING_REQUESTS = 8;

    private final Map<Integer, GrantResultCallback> callbacksByRequestId =
            new LinkedHashMap<Integer, GrantResultCallback>(MAX_PENDING_REQUESTS, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, GrantResultCallback> eldest) {
                    return size() > MAX_PENDING_REQUESTS;
                }
            };

    
    public interface GrantResultCallback {
        void onGrantResult(boolean z, boolean z2);
    }

    public PermissionCoordinator(Context context) {
        // Use applicationContext to prevent memory leaks in static singleton
        this.context = context.getApplicationContext();
    }

    public static synchronized PermissionCoordinator getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PermissionCoordinator(context);
        }
        return sInstance;
    }

    private synchronized int nextRequestId() {
        int i;
        i = this.lastRequestId + 1;
        this.lastRequestId = i;
        return i;
    }

    public synchronized void requestPermissions(GrantResultCallback callback, Activity activity, String... strArr) {
        List<String> listM4826a = PermissionUtils.getDeniedPermissions(this.context, strArr);
        if (listM4826a.isEmpty()) {
            return;
        }
        int iM4822a = nextRequestId();
        String[] strArr2 = (String[]) listM4826a.toArray(new String[listM4826a.size()]);
        this.callbacksByRequestId.put(Integer.valueOf(iM4822a), callback);
        if (activity != null) {
            PermissionUtils.requestPermissions(activity, iM4822a, strArr2);
        } else {
            PermissionActivity.start(this.context, iM4822a, strArr2);
        }
    }

    public synchronized void onRequestPermissionsResult(int i, String[] strArr, int[] iArr, boolean z) {
        // Remove before invoking so a throwing callback cannot leave the entry behind.
        GrantResultCallback callback = this.callbacksByRequestId.remove(Integer.valueOf(i));
        if (callback != null) {
            callback.onGrantResult(PermissionUtils.allGranted(iArr), z);
        }
    }
}
