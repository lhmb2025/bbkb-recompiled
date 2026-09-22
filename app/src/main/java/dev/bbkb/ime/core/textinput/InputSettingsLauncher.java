package dev.bbkb.ime.core.textinput;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.UserDictionary;

import dev.bbkb.ime.core.shared.Logger;



public final class InputSettingsLauncher {

    private static final String TAG = "InputSettingsLauncher";

    private InputSettingsLauncher() {
    }

    /**
     * TI-15: this used to build an explicit {@code MAIN} intent for
     * {@code com.android.settings.LanguageSettings}, an activity removed from AOSP Settings well
     * before API 23. {@code resolveActivityInfo} therefore returned null on every supported
     * device, so {@link #isInputSettingsAvailable} always answered false and
     * {@link #invokeInputSettings} always threw {@code ActivityNotFoundException} into its catch -
     * i.e. the "input settings" key (key code -26) silently did nothing.
     */
    private static Intent createInputSettingsIntent() {
        Intent intent = new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    static boolean isInputSettingsAvailable(Context context) {
        boolean z;
        try (Cursor cursorQuery = context.getContentResolver().query(
                UserDictionary.Words.CONTENT_URI, new String[]{UserDictionary.Words.LOCALE}, null, null, null)) {
            z = createInputSettingsIntent().resolveActivityInfo(context.getPackageManager(), 0) != null
                    && cursorQuery != null;
        }
        if (!z) {
            Logger.debug(TAG, "Input settings not available");
        }
        return z;
    }

    public static void invokeInputSettings(Context context) {
        try {
            context.startActivity(createInputSettingsIntent());
        } catch (Exception e) {
            Logger.errorWithException(TAG, e, "invokeInputSettings() encountered exception: ");
        }
    }
}
