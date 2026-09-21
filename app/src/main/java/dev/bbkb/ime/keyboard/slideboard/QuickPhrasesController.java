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

    /** Repopulate the attached view's phrase list. No-op when no view is attached. */
    public void show() {
        if (hasAttachedView()) {
            this.view.setQuickPhrases(buildPhrasesList(this.settingsManager.getSettingsValues()));
        }
    }

    /**
     * DETACHES the view (drops the listener and the reference); it does not merely hide it.
     * Called when the input view is going away, so {@link #show()} stays a no-op until the next
     * {@link #setView} - which SlideboardManager.setSlideboardComponent performs.
     */
    public void hide() {
        if (hasAttachedView()) {
            this.view.setQuickPhrasesListener(null);
            this.view = null;
        }
    }

    /** Whether a view is attached to this controller - NOT whether it is visible. */
    public boolean hasAttachedView() {
        return this.view != null;
    }

    @Override // dev.bbkb.ime.keyboard.slideboard.QuickPhrasesView.OnQuickPhraseSelectedListener
    public void onQuickPhraseSelected(CharSequence charSequence, Context context) {
        this.ime.getInputLogic().pasteText(charSequence);
    }

    private List<QuickPhrasesAdapter.QuickPhrase> buildPhrasesList(SettingsValues c0804d) {
        ArrayList arrayList = new ArrayList();
        addPhrase(arrayList, c0804d.quickPhrase1);
        addPhrase(arrayList, c0804d.quickPhrase2);
        addPhrase(arrayList, c0804d.quickPhrase3);
        addPhrase(arrayList, c0804d.quickPhrase4);
        addPhrase(arrayList, c0804d.quickPhrase5);
        return arrayList;
    }

    private static void addPhrase(List<QuickPhrasesAdapter.QuickPhrase> list, String str) {
        if (isAllWhitespace(str)) {
            return;
        }
        list.add(new QuickPhrasesAdapter.QuickPhrase(str + " "));
    }

    public void loadPhrases(SettingsValues c0804d) {
        QuickPhrasesView quickPhrasesView = this.view;
        if (quickPhrasesView != null) {
            quickPhrasesView.setQuickPhrases(buildPhrasesList(c0804d));
        }
    }
}
