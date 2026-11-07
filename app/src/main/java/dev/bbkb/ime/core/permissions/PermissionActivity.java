package dev.bbkb.ime.core.permissions;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.provider.Settings;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.permissions.PermissionRequestHandler.Callback;
import dev.bbkb.ime.core.permissions.PermissionRequestHandler.Result;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.settings.ui.BbkbDialogs;
import dev.bbkb.ime.core.shared.Logger;

/**
 * The keyboard is a Service and cannot ask for a runtime permission itself, so
 * {@link PermissionRequestHandler} starts this translucent activity, which explains why, asks the
 * system, reports one {@link Result} to the waiting {@link Callback} and finishes.
 *
 * <p>The decisions, unchanged from the old RationaleDialog + PermissionActivity pair
 * ("blocked" = the default-prefs boolean named after the permission; "explains" =
 * {@code shouldShowRequestPermissionRationale}):
 * <pre>
 *  permission already granted               -> nothing starts, no callback (PermissionRequestHandler)
 *  explanation or permission missing        -> PERMISSION_DENIED, no dialog
 *  blocked and the system does not explain  -> blocked dialog (Android no longer asks; not
 *                                              cancelable either)
 *    Open settings                          -> PERMISSION_BLOCKED, then the app's settings page
 *    Cancel                                 -> PERMISSION_BLOCKED
 *  otherwise                                -> rationale dialog (not cancelable, outside tap and
 *                                              Back do nothing)
 *    Deny                                   -> PERMISSION_DENIED (prefs untouched)
 *    Home / leaving the dialog              -> OTHER
 *    Ask for permission                     -> system request:
 *      granted                              -> PERMISSION_GRANTED
 *      refused, system now explains         -> PERMISSION_DENIED
 *      refused, system does not explain     -> PERMISSION_BLOCKED and blocked := true
 * </pre>
 * The contacts caller additionally keeps its own "has asked" preference: set before asking,
 * cleared again only on OTHER.
 *
 * <p>Result channel: one request at a time lives in {@link #sPending}, matched by a token in the
 * intent. Exactly one callback per request: a newer request delivers OTHER to the one it
 * replaces (its activity is cleared from the task), and an activity that finishes without an
 * answer delivers OTHER from {@link #onDestroy}. After a configuration change the activity keeps
 * waiting (the result registry redelivers the system answer); after process death the recreated
 * activity finds no matching request and finishes quietly, since nobody is left waiting.
 */
public final class PermissionActivity extends ComponentActivity {

    private static final String TAG = "PermissionActivity";
    private static final String EXTRA_TOKEN = "token";
    private static final String EXTRA_PERMISSION = "permission";
    private static final String EXTRA_EXPLANATION = "explanation_text";
    private static final String STATE_REQUESTED = "requested";

    private static final class Pending {
        final int token;
        final String permission;
        final Callback callback;

        Pending(int token, String permission, Callback callback) {
            this.token = token;
            this.permission = permission;
            this.callback = callback;
        }
    }

    private static Pending sPending;
    private static int sLastToken;

    private final ActivityResultLauncher<String> launcher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onRequestResult);

    private int token;
    private String permission;
    /** The system request is under way: leaving now no longer means OTHER. */
    private boolean requested;
    private AlertDialog dialog;

    static void request(Context context, String permission, String explanation, Callback callback) {
        Pending replaced;
        int token;
        synchronized (PermissionActivity.class) {
            replaced = sPending;
            token = ++sLastToken;
            sPending = new Pending(token, permission, callback);
        }
        if (replaced != null) {
            replaced.callback.onPermissionResult(replaced.permission, Result.OTHER);
        }
        Intent intent = new Intent(context.getApplicationContext(), PermissionActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_PERMISSION, permission)
                .putExtra(EXTRA_EXPLANATION, explanation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Logger.error(TAG, "Could not start the permission request: " + e);
            deliver(token, Result.OTHER);
        }
    }

    /** Hands {@code result} to the request {@code token} names, if it is still waiting. */
    private static void deliver(int token, Result result) {
        Pending pending;
        synchronized (PermissionActivity.class) {
            if (sPending == null || sPending.token != token) {
                return;
            }
            pending = sPending;
            sPending = null;
        }
        pending.callback.onPermissionResult(pending.permission, result);
    }

    private static synchronized boolean isWaiting(int token) {
        return sPending != null && sPending.token == token;
    }

    /** Whether the rationale dialog is shown, or the request ends as blocked without one. */
    static boolean shouldShowDialog(boolean blocked, boolean systemExplains) {
        return !blocked || systemExplains;
    }

    /** What a finished system request reports. */
    static Result resultOfRequest(boolean granted, boolean systemExplains) {
        if (granted) {
            return Result.PERMISSION_GRANTED;
        }
        return systemExplains ? Result.PERMISSION_DENIED : Result.PERMISSION_BLOCKED;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        token = intent.getIntExtra(EXTRA_TOKEN, 0);
        permission = intent.getStringExtra(EXTRA_PERMISSION);
        if (!isWaiting(token)) {
            // Recreated after process death, or replaced by a newer request.
            finishAndRemoveTask();
            return;
        }
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_REQUESTED)) {
            requested = true;
            return;
        }
        String explanation = intent.getStringExtra(EXTRA_EXPLANATION);
        if (TextUtils.isEmpty(explanation)) {
            Logger.error(TAG, "Tried to create a rationale dialog with an empty message");
            finishWith(Result.PERMISSION_DENIED);
        } else if (TextUtils.isEmpty(permission)) {
            Logger.error(TAG, "Tried to create a rationale dialog without specifying the permission");
            finishWith(Result.PERMISSION_DENIED);
        } else if (shouldShowDialog(isBlocked(), shouldShowRequestPermissionRationale(permission))) {
            showRationaleDialog(explanation);
        } else {
            showBlockedDialog(explanation);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_REQUESTED, requested);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!requested) {
            finishWith(Result.OTHER);
        }
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.dismiss();
        }
        if (isFinishing()) {
            deliver(token, Result.OTHER);
        }
        super.onDestroy();
    }

    private void onRequestResult(Boolean granted) {
        Result result = resultOfRequest(granted, !granted && shouldShowRequestPermissionRationale(permission));
        if (result == Result.PERMISSION_BLOCKED) {
            // apply(), not commit(): main thread, and nothing reads the result of the write.
            PrefsManager.INSTANCE.getPrefs(this).edit().putBoolean(permission, true).apply();
        }
        finishWith(result);
    }

    private boolean isBlocked() {
        return PrefsManager.INSTANCE.getPrefs(this).getBoolean(permission, false);
    }

    private void finishWith(Result result) {
        deliver(token, result);
        finishAndRemoveTask();
    }

    private void showRationaleDialog(String explanation) {
        final AlertDialog created = BbkbDialogs.builder(this)
                .setTitle(R.string.rationale_dialog_header)
                .setMessage(explanation)
                .setPositiveButton(R.string.ask_for_permission, (d, which) -> {
                    requested = true;
                    launcher.launch(permission);
                })
                .setNegativeButton(R.string.deny, (d, which) -> finishWith(Result.PERMISSION_DENIED))
                .setIcon(R.mipmap.ic_launcher_round)
                .setCancelable(false)
                .create();
        created.setCanceledOnTouchOutside(false);
        created.setOnShowListener(d -> {
            created.getButton(AlertDialog.BUTTON_NEGATIVE).clearFocus();
            Button button = created.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setFocusable(true);
            button.setFocusableInTouchMode(true);
            button.requestFocus();
        });
        dialog = created;
        created.show();
    }

    /**
     * Android has stopped asking (two refusals): say so instead of failing silently, and offer the
     * app's settings page, the only place left to turn the permission on. Either button reports
     * PERMISSION_BLOCKED; the settings page opens after the report, so the caller never waits on it.
     */
    private void showBlockedDialog(String explanation) {
        final AlertDialog created = BbkbDialogs.builder(this)
                .setTitle(R.string.rationale_dialog_header)
                .setMessage(explanation + "\n\n" + getString(R.string.permission_blocked_explanation))
                .setPositiveButton(R.string.settings_update_open_settings, (d, which) -> openAppSettings())
                .setNegativeButton(R.string.cancel, (d, which) -> finishWith(Result.PERMISSION_BLOCKED))
                .setIcon(R.mipmap.ic_launcher_round)
                .setCancelable(false)
                .create();
        created.setCanceledOnTouchOutside(false);
        dialog = created;
        created.show();
    }

    private void openAppSettings() {
        deliver(token, Result.PERMISSION_BLOCKED);
        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException e) {
            Logger.warn(TAG, "No app settings page to open: " + e);
        }
        finishAndRemoveTask();
    }
}
