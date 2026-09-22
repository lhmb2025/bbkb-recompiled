package dev.bbkb.ime.core.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import dev.bbkb.ime.core.engine.learning.DynamicLearningManager;
import dev.bbkb.ime.core.shared.Logger;

/**
 * BroadcastReceiver for handling "flush learned data to disk" requests.
 * Extracted from BlackBerryIME to improve separation of concerns.
 *
 * <p>Despite the name, the only store this can flush is the personal dictionary. The engine's
 * dynamic learning model is written by native code and has no Java-side save entry point, so
 * there has never been a "learning model" for this receiver to write — see
 * {@link DynamicLearningManager#savePersonalDictionary()} for the full history.
 */
public class LearningModelSaveReceiver extends BroadcastReceiver {
    
    private static final String TAG = "LearningModelSaveReceiver";
    private final DynamicLearningManager dynamicLearningManager;
    
    public LearningModelSaveReceiver(DynamicLearningManager dynamicLearningManager) {
        this.dynamicLearningManager = dynamicLearningManager;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.info(TAG, "Writing personal dictionary to disk");
        dynamicLearningManager.savePersonalDictionary();
    }
}
