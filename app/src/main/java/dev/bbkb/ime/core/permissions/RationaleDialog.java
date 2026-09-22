package dev.bbkb.ime.core.permissions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.text.TextUtils;
import android.widget.Button;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.InAppEventBus;
import dev.bbkb.ime.core.shared.Logger;


public class RationaleDialog extends Activity {

    public String permission;

    public String resultReceiverAction;

    
    public enum Result {
        ASK,
        DENY,
        BLOCKED,
        OTHER;


        private static final String INTENT_EXTRA_KEY = Result.class.getName();

        public void writeToIntent(Intent intent) {
            intent.putExtra(INTENT_EXTRA_KEY, ordinal());
        }

        public static Result readFromIntent(Intent intent) {
            if (!intent.hasExtra(INTENT_EXTRA_KEY)) {
                throw new IllegalStateException();
            }
            return values()[intent.getIntExtra(INTENT_EXTRA_KEY, -1)];
        }
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Intent intent = getIntent();
        String stringExtra = intent.getStringExtra("explanation_text");
        this.permission = intent.getStringExtra("permission");
        this.resultReceiverAction = intent.getStringExtra("result_receiver");
        if (TextUtils.isEmpty(stringExtra)) {
            Logger.error("RationaleDialog", "Tried to create a rationale dialog with an empty message");
            finishWithResult(Result.DENY);
            return;
        }
        if (TextUtils.isEmpty(this.permission)) {
            Logger.error("RationaleDialog", "Tried to create a rationale dialog without specifying the permission");
            finishWithResult(Result.DENY);
        } else if (TextUtils.isEmpty(this.resultReceiverAction)) {
            Logger.error("RationaleDialog", "Tried to create a rationale dialog with an invalid result receiver");
            finishWithResult(Result.DENY);
        } else if (canShowRationale()) {
            showRationaleDialog(stringExtra);
        } else {
            finishWithResult(Result.BLOCKED);
        }
    }

    @Override // android.app.Activity
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        finishWithResult(Result.OTHER);
    }

        void finishWithResult(Result result) {
        Bundle extras = new Bundle();
        extras.putInt(PermissionRequestHandler.EXTRA_RESULT_CODE, result.ordinal());
        extras.putString(PermissionRequestHandler.EXTRA_PERMISSION, this.permission);
        InAppEventBus.getInstance().post(this.resultReceiverAction, extras);
        finish();
    }

    private void showRationaleDialog(String str) {
        final AlertDialog alertDialogCreate = new AlertDialog.Builder(this, R.style.platformDialogTheme).setTitle(R.string.rationale_dialog_header).setMessage(str).setPositiveButton(R.string.ask_for_permission, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                RationaleDialog.this.finishWithResult(Result.ASK);
            }
        }).setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                RationaleDialog.this.finishWithResult(Result.DENY);
            }
        }).setIcon(R.mipmap.ic_launcher_round).setCancelable(false).create();
        alertDialogCreate.setCanceledOnTouchOutside(false);
        alertDialogCreate.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override // android.content.DialogInterface.OnShowListener
            public void onShow(DialogInterface dialogInterface) {
                alertDialogCreate.getButton(-2).clearFocus();
                Button button = alertDialogCreate.getButton(-1);
                button.setFocusable(true);
                button.setFocusableInTouchMode(true);
                button.requestFocus();
            }
        });
        alertDialogCreate.show();
    }

    private boolean canShowRationale() {
        return !PrefsManager.INSTANCE.getPrefs(getApplicationContext()).getBoolean(this.permission, false) || PermissionUtils.shouldShowRationale(this, this.permission);
    }
}
