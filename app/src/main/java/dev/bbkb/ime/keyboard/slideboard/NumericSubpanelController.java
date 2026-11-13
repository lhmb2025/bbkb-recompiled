package dev.bbkb.ime.keyboard.slideboard;

import android.content.res.Resources;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;

import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;



public class NumericSubpanelController implements SimplifiedKeyboardView.onKeyEventListener {

    private BlackBerryIME ime;

    private SubtypeManager subtypeManager;

    private SettingsManager settingsManager;

    private NumericSubpanelKeyboardView keyboardView;

    private List<String> customSymbols;

    private String currencySymbol;

    private Locale currentLocale;

    @Override
    public void onKeyDown(Key key) {
        // Keys commit on key up.
    }

    @Override
    public void onKeyLongPress(Key key) {
        // The pad has no long-press actions.
    }

    public NumericSubpanelController(BlackBerryIME blackBerryIME, SubtypeManager subtypeManager, SettingsManager settingsManager) {
        this.ime = blackBerryIME;
        this.subtypeManager = subtypeManager;
        this.settingsManager = settingsManager;
        this.customSymbols = this.settingsManager.getSettingsValues().customSlideboardSymbols;
        this.currentLocale = this.subtypeManager.getCurrentSubtypeLocale();
    }

    public void setView(NumericSubpanelKeyboardView numericSubpanelKeyboardView) {
        this.keyboardView = numericSubpanelKeyboardView;
        numericSubpanelKeyboardView.setKeyboard(buildKeyboard(this.subtypeManager.getCurrentSubtypeLocale()));
        numericSubpanelKeyboardView.setOnKeyEventListener(this);
        applyKeys(this.settingsManager.getSettingsValues());
    }

    /** Currency first, then the customised pad. Every path that builds the pad goes through here. */
    private void applyKeys(SettingsValues settingsValues) {
        this.customSymbols = settingsValues.customSlideboardSymbols;
        this.currencySymbol = settingsValues.currencySymbol;
        this.keyboardView.updateKey(settingsValues.currencySymbol);
        this.keyboardView.applyCustomKeys(this.customSymbols, currencyPlaceholders(settingsValues.currencySymbol));
    }

    /** What the currency slot may hold and still mean "the currency key". */
    public static Set<String> currencyPlaceholders(String currencySymbol) {
        Set<String> placeholders = new HashSet<>();
        placeholders.add("$"); // the editor's default
        placeholders.add(currencySymbol);
        try {
            placeholders.add(Currency.getInstance(Locale.getDefault()).getSymbol());
        } catch (IllegalArgumentException e) {
            // A default locale with no country (or an unknown one) has no currency.
        }
        return placeholders;
    }

    private Keyboard buildKeyboard(Locale locale) {
        return createBuilder(locale).getKeyboardForShift(41, false);
    }

    private KeyboardBuilder createBuilder(Locale locale) {
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(this.ime, null);
        aVar.setSubtype(SubtypeFactory.createSubtype(locale.toString(), "slide_numeric_sub_panel"));
        this.currentLocale = locale;
        Resources resources = this.ime.getResources();
        aVar.setKeyboardGeometry(ResourceConfigManager.getScreenWidthPixels(resources) / 2, ResourceConfigManager.getKeyboardHeightWithPadding(resources));
        return aVar.build();
    }

    /** @param z unused */
    @Override
    public void onKeyUp(Key key, boolean z) {
        this.ime.onCodeInput(key.getCode(), -6, -6, SystemClock.uptimeMillis(), false);
    }

    public void onSubtypeChanged(InputMethodSubtype inputMethodSubtype) {
        if (this.keyboardView != null) {
            this.keyboardView.setKeyboard(buildKeyboard(new Locale(inputMethodSubtype.getLocale())));
            applyKeys(this.settingsManager.getSettingsValues());
        }
    }

    public void applySettings(SettingsValues settingsValues) {
        if (this.keyboardView == null) {
            return;
        }
        boolean changed = !this.customSymbols.equals(settingsValues.customSlideboardSymbols)
                || !java.util.Objects.equals(this.currencySymbol, settingsValues.currencySymbol);
        if (changed) {
            // Replaced keys can't be un-replaced (a cleared customisation, say), so start over.
            this.keyboardView.setKeyboard(buildKeyboard(this.currentLocale));
            applyKeys(settingsValues);
        }
    }

    /** Releases the view. Keep {@code ime}: the next setView() needs it in createBuilder. */
    public void refresh() {
        NumericSubpanelKeyboardView numericSubpanelKeyboardView = this.keyboardView;
        if (numericSubpanelKeyboardView != null) {
            numericSubpanelKeyboardView.setGestureDetector(null);
            this.keyboardView = null;
        }
    }
}
