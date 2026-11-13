package dev.bbkb.ime.core.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import dev.bbkb.ime.core.engine.learning.DynamicLearningManager;
import dev.bbkb.ime.core.shared.Logger;

/**
 * Handles "flush learned data to disk" requests. Despite the name it only flushes the personal
 * dictionary: the engine's learning model is written by native code with no Java-side save entry
 * point (see {@link DynamicLearningManager#savePersonalDictionary()}).
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
