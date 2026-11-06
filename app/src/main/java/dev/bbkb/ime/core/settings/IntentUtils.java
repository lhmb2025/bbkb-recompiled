package dev.bbkb.ime.core.settings;

import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import dev.bbkb.ime.core.shared.Logger;




public final class IntentUtils {

    private static final String TAG = "IntentUtils";

    private IntentUtils() {
    }

    public static Intent getInputLanguageSelectionIntent(String str, int i) {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        if (!TextUtils.isEmpty(str)) {
            intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, str);
        }
        if (i > 0) {
            intent.setFlags(i);
        }
        return intent;
    }
}
