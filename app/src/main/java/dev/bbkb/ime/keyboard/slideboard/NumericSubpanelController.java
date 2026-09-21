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
import java.util.List;
import java.util.Locale;



public class NumericSubpanelController implements SimplifiedKeyboardView.onKeyEventListener {

    private BlackBerryIME ime;

    private SubtypeManager subtypeManager;

    private SettingsManager settingsManager;

    private NumericSubpanelKeyboardView keyboardView;

    private List<String> customSymbols;

    private Locale currentLocale;

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyDown(Key key) {
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyLongPress(Key key) {
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
        numericSubpanelKeyboardView.updateKey(this.settingsManager.getSettingsValues().currencySymbol);
        numericSubpanelKeyboardView.setNumericKeys(this.settingsManager.getSettingsValues().customSlideboardSymbols);
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

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyUp(Key key, boolean z) {
        this.ime.onCodeInput(key.getCode(), -6, -6, SystemClock.uptimeMillis(), false);
    }

    public void onSubtypeChanged(InputMethodSubtype inputMethodSubtype) {
        if (this.keyboardView != null) {
            String str = this.customSymbols.size() > 8 ? this.customSymbols.get(8) : "";
            String symbol = Currency.getInstance(Locale.getDefault()).getSymbol();
            this.keyboardView.setKeyboard(buildKeyboard(new Locale(inputMethodSubtype.getLocale())));
            if (!str.equals(symbol)) {
                this.keyboardView.setNumericKeys(this.customSymbols);
            } else {
                this.keyboardView.setSymbolKeys(this.customSymbols);
            }
            this.customSymbols = this.settingsManager.getSettingsValues().customSlideboardSymbols;
        }
    }

    public void applySettings(SettingsValues settingsValues) {
        boolean z = !this.customSymbols.equals(settingsValues.customSlideboardSymbols);
        this.customSymbols = settingsValues.customSlideboardSymbols;
        NumericSubpanelKeyboardView numericSubpanelKeyboardView = this.keyboardView;
        if (numericSubpanelKeyboardView != null && z) {
            numericSubpanelKeyboardView.setNumericKeys(this.customSymbols);
        }
    }

    /**
     * Releases the view. The {@code ime} field is deliberately NOT nulled: it is the IME
     * service, not a view, and createBuilder dereferences it - so a refresh() followed by the
     * next setView() (SlideboardManager.setSlideboardComponent) used to NPE.
     */
    public void refresh() {
        NumericSubpanelKeyboardView numericSubpanelKeyboardView = this.keyboardView;
        if (numericSubpanelKeyboardView != null) {
            numericSubpanelKeyboardView.setGestureDetector(null);
            this.keyboardView = null;
        }
    }
}
