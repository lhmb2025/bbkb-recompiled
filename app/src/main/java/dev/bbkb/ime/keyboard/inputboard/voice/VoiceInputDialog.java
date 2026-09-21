package dev.bbkb.ime.keyboard.inputboard.voice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.InAppEventBus;


public class VoiceInputDialog extends Activity {

    public String mResultReceiver;

    
    public enum Result {
        CANCEL,
        OK,
        OTHER;


        private static final String RESULT_KEY = Result.class.getName();

        public void putInto(Intent intent) {
            intent.putExtra(RESULT_KEY, ordinal());
        }
    }

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Intent intent = getIntent();
        String stringExtra = intent.getStringExtra("explanation_text");
        this.mResultReceiver = intent.getStringExtra("result_receiver");
        if (TextUtils.isEmpty(stringExtra)) {
            finishWithResult(Result.OTHER);
        } else {
            showDialog(stringExtra);
        }
    }

    @Override // android.app.Activity
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        finishWithResult(Result.OTHER);
    }

    public static final String EXTRA_RESULT_CODE = "voice_dialog_result";

    private void finishWithResult(Result enumC1120a) {
        Bundle extras = new Bundle();
        extras.putInt(EXTRA_RESULT_CODE, enumC1120a.ordinal());
        InAppEventBus.getInstance().post(this.mResultReceiver, extras);
        finish();
    }

    private void showDialog(String str) {
        final AlertDialog alertDialogCreate = new AlertDialog.Builder(this, 5).setTitle(R.string.voice_input_manage_language_title).setMessage(str).setPositiveButton(R.string.go_to_settings, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                VoiceInputDialog.this.finishWithResult(Result.OK);
            }
        }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                VoiceInputDialog.this.finishWithResult(Result.CANCEL);
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
}
