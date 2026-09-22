package dev.bbkb.ime.core.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;

import java.util.List;
import java.util.Locale;

/**
 * BroadcastReceiver for handling system locale changes.
 * Extracted from BlackBerryIME to improve separation of concerns.
 */
public class LocaleChangeReceiver extends BroadcastReceiver {
    
    private final BlackBerryIME ime;
    private final SubtypeManager subtypeManager;
    private final RichInputMethodManager richInputMethodManager;
    
    public LocaleChangeReceiver(BlackBerryIME ime, SubtypeManager subtypeManager, 
                                RichInputMethodManager richInputMethodManager) {
        this.ime = ime;
        this.subtypeManager = subtypeManager;
        this.richInputMethodManager = richInputMethodManager;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        List<InputMethodSubtype> listM4860a = RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme();
        // Audit UT-7: Configuration.locale is deprecated (API 24) and disagrees with getLocales()[0]
        // on a device whose primary locale is second in the LocaleList — which would misfire the
        // subtype auto-switch this receiver performs. LocaleUtils is the project's helper.
        Locale locale = LocaleUtils.getConfigurationLocale(ime.getResources());
        if (locale.equals(ResourceLocaleUtils.getSubtypeLocale(subtypeManager.getCurrentSubtype()))) {
            return;
        }
        for (InputMethodSubtype inputMethodSubtype : listM4860a) {
            if (locale.getLanguage().equals(ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype).getLanguage())) {
                richInputMethodManager.clearSubtypeCaches();
                if (!richInputMethodManager.checkIfSubtypeBelongsToThisImeAndImplicitlyEnabled(inputMethodSubtype)) {
                    return;
                }
                subtypeManager.onSubtypeChanged(inputMethodSubtype);
                ime.updateLanguagePacksForSubtype(inputMethodSubtype);
            }
        }
    }
}
