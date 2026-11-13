package dev.bbkb.ime.core.settings;

import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import dev.bbkb.ime.core.shared.Logger;




public final class IntentUtils {

    private static final String TAG = "IntentUtils";

    private IntentUtils() {
    }

    public static Intent getInputLanguageSelectionIntent(String inputMethodId, int flagsForSubtypeSettings) {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        if (!TextUtils.isEmpty(inputMethodId)) {
            intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, inputMethodId);
        }
        if (flagsForSubtypeSettings > 0) {
            intent.setFlags(flagsForSubtypeSettings);
        }
        return intent;
    }
}
