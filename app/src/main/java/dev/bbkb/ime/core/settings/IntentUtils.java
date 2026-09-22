package dev.bbkb.ime.core.settings;

import android.content.Intent;
import android.text.TextUtils;

import dev.bbkb.ime.core.shared.Logger;

import java.util.Locale;



public final class IntentUtils {

    private static final String TAG = "IntentUtils";

    private IntentUtils() {
    }

    public static Intent getInputLanguageSelectionIntent(String str, int i) {
        Intent intent = new Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS");
        if (!TextUtils.isEmpty(str)) {
            intent.putExtra("input_method_id", str);
        }
        if (i > 0) {
            intent.setFlags(i);
        }
        return intent;
    }

    public static Intent getAddWordToDictionaryIntent(String str, Locale locale) {
        if (str == null || str.isEmpty()) {
            Logger.error(TAG, "getAddToDictionaryDialogIntent() called with empty word");
            return null;
        }
        Intent intent = new Intent("com.android.settings.USER_DICTIONARY_INSERT");
        intent.putExtra("word", str);
        if (locale == null) {
            Logger.debug(TAG, "getAddToDictionaryDialogIntent() called with null locale; adding word to all languages dictionary");
        } else {
            intent.putExtra("locale", locale.toString());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
