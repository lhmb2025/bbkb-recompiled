package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import dev.bbkb.ime.BuildConfig;




public final class ClipboardUtil {

    private static final String TAG = "ClipboardUtil";

    private ClipboardUtil() {
    }

    public static CharSequence getClipboardText(Context context) {
        CharSequence label;
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
            if (BuildConfig.DEBUG) Log.e(TAG, "primary clipboard is not accessible");
            return null;
        }
        ClipData primaryClip = clipboardManager.getPrimaryClip();
        if (primaryClip == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "primary clipboard is null");
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < primaryClip.getItemCount(); i++) {
            ClipData.Item itemAt = primaryClip.getItemAt(i);
            if (itemAt != null) {
                String string = itemAt.coerceToText(context).toString();
                if (string.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append((CharSequence) string);
                }
            }
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        ClipDescription description = primaryClip.getDescription();
        if (description != null && (label = description.getLabel()) != null && label.length() > 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "primary clipboard has no text data; pasting the label");
            return label;
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "primary clipboard has no text data");
        return null;
    }
}
