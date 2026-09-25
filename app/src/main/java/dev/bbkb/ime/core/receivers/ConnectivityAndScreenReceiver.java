package dev.bbkb.ime.core.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
import dev.bbkb.ime.core.keyevent.ModifierResetReason;

/**
 * BroadcastReceiver for handling connectivity changes, ringer mode changes, and screen off events.
 * Extracted from BlackBerryIME to improve separation of concerns.
 */
public class ConnectivityAndScreenReceiver extends BroadcastReceiver {
    
    private final BlackBerryIME ime;
    private final SubtypeManager subtypeManager;
    private final PhysicalKeyboardStateTracker physicalKeyboardStateTracker;
    
    public ConnectivityAndScreenReceiver(BlackBerryIME ime, SubtypeManager subtypeManager, 
                                         PhysicalKeyboardStateTracker physicalKeyboardStateTracker) {
        this.ime = ime;
        this.subtypeManager = subtypeManager;
        this.physicalKeyboardStateTracker = physicalKeyboardStateTracker;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {
            subtypeManager.onNetworkStateChanged(intent);
            return;
        }
        if ("android.media.RINGER_MODE_CHANGED".equals(action)) {
            AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
        } else if ("android.intent.action.SCREEN_OFF".equals(action)) {
            ime.enableCursorMode(false);
            physicalKeyboardStateTracker.resetModifiers(ModifierResetReason.SCREEN_OFF);
        }
    }
}
