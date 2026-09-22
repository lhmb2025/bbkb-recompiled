package dev.bbkb.ime.core.subtypeswitcher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.shared.InAppEventBus;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.keyevent.InputSource;

import java.util.List;



public class SubtypeSwitcherReceiver implements InAppEventBus.EventListener {

    public static final String ACTION_SUBTYPE_SWITCH_RESULT = "com.blackberry.quick.subtype.switch.result.receiver";
    public static final String EXTRA_RESULT = "subtype.switcher.dialog.result";

    private InputMethodService ime = null;

    private final UIUpdateHandler uiUpdateHandler;


    private long dialogShownTime;

    /** Snapshot taken when the dialog was shown; the result index refers to this list. */
    private List<InputMethodSubtype> shownSubtypes;

    public SubtypeSwitcherReceiver(Context context, UIUpdateHandler handlerC0650c) {
        this.uiUpdateHandler = handlerC0650c;
    }

    public boolean show(Context context, InputMethodService ims, InputSource enumC0690f) {
        this.ime = ims;
        InAppEventBus.getInstance().subscribe(ACTION_SUBTYPE_SWITCH_RESULT, this);
        Intent intent = new Intent();
        intent.setClass(context, SubtypeSwitcherDialog.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        this.dialogShownTime = SystemClock.uptimeMillis();
        this.shownSubtypes = RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme();
        return true;
    }

    @Override
    public void onEvent(String action, Bundle extras) {
        if (ACTION_SUBTYPE_SWITCH_RESULT.equals(action) && extras != null) {
            int intExtra = extras.getInt(EXTRA_RESULT, 0);
            if (this.ime == null || intExtra < 0) {
                if (intExtra == -2) {
                    this.uiUpdateHandler.postCancelQuickSwitch();
                }
            } else {
                // The index refers to the list the dialog was populated from, not to a list
                // re-fetched now: the enabled set can change while the dialog is open, which
                // switched the user to the wrong language or threw IndexOutOfBounds.
                final List<InputMethodSubtype> subtypes = this.shownSubtypes;
                if (subtypes != null && intExtra < subtypes.size()) {
                    RichInputMethodManager.getInstance().setInputMethodAndSubtype(this.ime, subtypes.get(intExtra));
                }
            }
        }
        InAppEventBus.getInstance().unsubscribe(ACTION_SUBTYPE_SWITCH_RESULT, this);
    }

}
