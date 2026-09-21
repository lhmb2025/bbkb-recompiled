package dev.bbkb.ime.core;

import android.view.KeyEvent;



public interface KeyboardLayoutCallback {
    String getKeyLabelForKeyEvent(KeyEvent keyEvent);

    String[] getMoreKeysForKey(String str);

    String[] getMoreKeysForKeyByStyle(String str);
}
