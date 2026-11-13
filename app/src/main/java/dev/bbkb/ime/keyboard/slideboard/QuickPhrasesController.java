package dev.bbkb.ime.keyboard.slideboard;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.Context;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;

import java.util.ArrayList;
import java.util.List;



public class QuickPhrasesController implements QuickPhrasesView.OnQuickPhraseSelectedListener {

    private BlackBerryIME ime;

    private SettingsManager settingsManager;

    private QuickPhrasesView view;

    public QuickPhrasesController(BlackBerryIME blackBerryIME, SettingsManager settingsManager) {
        this.ime = blackBerryIME;
        this.settingsManager = settingsManager;
    }

    public void setView(QuickPhrasesView quickPhrasesView) {
        this.view = quickPhrasesView;
        this.view.setQuickPhrasesListener(this);
        this.view.setQuickPhrases(buildPhrasesList(this.settingsManager.getSettingsValues()));
    }

    /** Repopulate the attached view's phrase list, if any. */
    public void show() {
        if (hasAttachedView()) {
            this.view.setQuickPhrases(buildPhrasesList(this.settingsManager.getSettingsValues()));
        }
    }

    /** Detaches the view, not merely hides it: {@link #show()} is a no-op until the next {@link #setView}. */
    public void hide() {
        if (hasAttachedView()) {
            this.view.setQuickPhrasesListener(null);
            this.view = null;
        }
    }

    /** Whether a view is attached, not whether it is visible. */
    public boolean hasAttachedView() {
        return this.view != null;
    }

    @Override
    public void onQuickPhraseSelected(CharSequence charSequence, Context context) {
        this.ime.getInputLogic().pasteText(charSequence);
    }

    /** @param settingsValues the settings holding the five phrases */
    private List<QuickPhrasesAdapter.QuickPhrase> buildPhrasesList(SettingsValues settingsValues) {
        ArrayList arrayList = new ArrayList();
        addPhrase(arrayList, settingsValues.quickPhrase1);
        addPhrase(arrayList, settingsValues.quickPhrase2);
        addPhrase(arrayList, settingsValues.quickPhrase3);
        addPhrase(arrayList, settingsValues.quickPhrase4);
        addPhrase(arrayList, settingsValues.quickPhrase5);
        return arrayList;
    }

    /**
     * @param list the phrases so far
     * @param phrase the phrase; blank ones are skipped
     */
    private static void addPhrase(List<QuickPhrasesAdapter.QuickPhrase> list, String phrase) {
        if (isAllWhitespace(phrase)) {
            return;
        }
        list.add(new QuickPhrasesAdapter.QuickPhrase(phrase + " "));
    }

    /** @param settingsValues the settings holding the five phrases */
    public void loadPhrases(SettingsValues settingsValues) {
        QuickPhrasesView quickPhrasesView = this.view;
        if (quickPhrasesView != null) {
            quickPhrasesView.setQuickPhrases(buildPhrasesList(settingsValues));
        }
    }
}
