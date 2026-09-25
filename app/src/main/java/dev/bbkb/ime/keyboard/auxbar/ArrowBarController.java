package dev.bbkb.ime.keyboard.auxbar;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.core.ime.CursorMovementListener;

import java.util.Locale;



/**
 * Controller for the cursor-mode arrow bar — the transient cursor-key strip shown
 * in the aux/suggestion row (AuxBarState.ARROW_BAR), toggled by the double-tap /
 * cursor gesture, and
 * auto-dismissed after a timeout (BlackBerryIME.scheduleCursorModeDisable).
 *
 * Naming: "cursor mode" (isCursorModeEnabled / toggleCursorMode) is the
 * gesture-and-arrow-bar surface; the separate Fine Cursor Control BOARD keeps the
 * Fcc family ({@link dev.bbkb.ime.keyboard.inputboard.fcc.FccController}),
 * which opens as a full input board like the voice board.
 */
public class ArrowBarController implements SimplifiedKeyboardView.onKeyEventListener {

    private static final String TAG = "ArrowBarController";

    private final Context themedContext;
    private AuxBarManager auxBarManager;

    private final KeyboardBuilder keyboardBuilder;

    private final CursorMovementListener cursorMovementListener;


    private Handler repeatHandler = new Handler(Looper.getMainLooper());

    private final long keyRepeatStartTimeout;

    private final long keyRepeatInterval;

    /** The key currently auto-repeating, read by {@link #keyRepeatRunnable} at fire time. */
    private Key repeatingKey;

    /**
     * Auto-repeat driver. Allocated once: this used to be a fresh anonymous Runnable capturing
     * the key on every arrow-bar key-down, i.e. per press on the cursor-movement path.
     */
    private final Runnable keyRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            Key key = ArrowBarController.this.repeatingKey;
            if (key == null) {
                return;
            }
            ArrowBarController.this.handleArrowKey(key);
            ArrowBarController.this.repeatHandler.postDelayed(this, ArrowBarController.this.keyRepeatInterval);
        }
    };

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyLongPress(Key key) {
    }

    public ArrowBarController(Context context, CursorMovementListener cursorMovementListener, View view) {
        // Legacy view lookup removed - now uses AuxBarManager
        this.themedContext = new ContextThemeWrapper(context, dev.bbkb.ime.R.style.KeyboardTheme_LXX);
        Resources resources = context.getResources();
        this.keyRepeatStartTimeout = resources.getInteger(R.integer.config_key_repeat_start_timeout);
        this.keyRepeatInterval = resources.getInteger(R.integer.config_key_repeat_interval);
        this.keyboardBuilder = createArrowKeyboardBuilder();
        this.cursorMovementListener = cursorMovementListener;
    }
    
    /**
     * Set the AuxBarManager for arrow bar display.
     */
    public void setAuxBarManager(AuxBarManager manager) {
        this.auxBarManager = manager;
    }

    /**
     * Build the arrow bar keyboard using the correct 'arrows' subtype builder.
     * Called by AuxBarManager.showArrowBar() so it uses this builder, not its own.
     */
    public dev.bbkb.ime.keyboard.Keyboard buildArrowKeyboard() {
        return this.keyboardBuilder.getKeyboardForShift(39, true);
    }

    public void show() {
        Logger.debug(TAG, "ARROW_BAR_DIAG show: auxBarManager=" + (auxBarManager != null ? "non-null" : "NULL"));
        if (auxBarManager != null) {
            auxBarManager.showArrowBar();
        } else {
            Logger.debug(TAG, "ARROW_BAR_DIAG show: ERROR — auxBarManager is null, cannot show ArrowBar");
        }
    }

    public void hide() {
        Logger.debug(TAG, "Hiding ArrowBar");
        // A bar dismissed mid-repeat would otherwise keep moving the cursor.
        stopKeyRepeat();
        if (auxBarManager != null) {
            auxBarManager.hideArrowBar();
        }
    }

    private void stopKeyRepeat() {
        this.repeatHandler.removeCallbacks(this.keyRepeatRunnable);
        this.repeatingKey = null;
    }

    public boolean isShowing() {
        return auxBarManager != null && auxBarManager.getCurrentState() == dev.bbkb.ime.keyboard.auxbar.AuxBarState.ARROW_BAR;
    }

    void handleArrowKey(Key key) {
        switch (key.getCode()) {
            case -33:
                this.cursorMovementListener.moveDown(1);
                break;
            case -32:
                this.cursorMovementListener.moveUp(1);
                break;
            case -31:
                this.cursorMovementListener.moveRight(1);
                break;
            case -30:
                this.cursorMovementListener.moveLeft(1);
                break;
        }
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyDown(final Key key) {
        this.repeatHandler.removeCallbacks(this.keyRepeatRunnable);
        this.repeatingKey = key;
        if (key == null) {
            return;
        }
        this.repeatHandler.postDelayed(this.keyRepeatRunnable, this.keyRepeatStartTimeout);
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyUp(Key key, boolean z) {
        stopKeyRepeat();
        // The shared key view can deliver a null key; AuxBarManager guards its own delegation
        // the same way (handleArrowKey dereferences key.getCode()).
        if (z && key != null) {
            handleArrowKey(key);
        }
    }

    private KeyboardBuilder createArrowKeyboardBuilder() {
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(this.themedContext, null);
        aVar.setSubtype(SubtypeFactory.createSubtype(Locale.ENGLISH.toString(), "arrows"));
        Resources resources = this.themedContext.getResources();
        aVar.setKeyboardGeometry(ResourceConfigManager.getScreenWidthPixels(resources), ResourceConfigManager.getSuggestionsStripHeight(resources));
        return aVar.build();
    }
}
