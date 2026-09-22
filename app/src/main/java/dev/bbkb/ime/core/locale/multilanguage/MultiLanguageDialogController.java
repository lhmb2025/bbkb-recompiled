package dev.bbkb.ime.core.locale.multilanguage;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.Locale;



public class MultiLanguageDialogController implements AdapterView.OnItemSelectedListener {

    private final int mode;

    private final Context context;

    private Spinner primarySpinner;

    private Spinner supportingSpinner;

    private ArrayAdapter<LocaleItem> supportingAdapter;

    private final MultiLanguageRepository repository;

    private MultiLanguageConfig config;

    @Override // android.widget.AdapterView.OnItemSelectedListener
    public void onNothingSelected(AdapterView<?> adapterView) {
    }

    MultiLanguageDialogController(View view, Bundle bundle, Context context, MultiLanguageRepository c0707b) {
        this.context = context;
        this.mode = bundle.getInt("mode");
        this.config = MultiLanguageUtils.parseConfig(bundle.getString("locales"));
        this.repository = c0707b;
        this.primarySpinner = (Spinner) view.findViewById(R.id.primary_locale_spinner);
        this.supportingSpinner = (Spinner) view.findViewById(R.id.supporting_locales_spinner);
    }

    MultiLanguageDialogController(View view, MultiLanguageDialogController c0706a, Context context, MultiLanguageRepository c0707b) {
        this.context = context;
        this.mode = c0706a.mode;
        this.config = c0706a.config;
        this.repository = c0707b;
        this.primarySpinner = (Spinner) view.findViewById(R.id.primary_locale_spinner);
        this.supportingSpinner = (Spinner) view.findViewById(R.id.supporting_locales_spinner);
    }

    public void setupSpinners() {
        this.primarySpinner.setOnItemSelectedListener(this);
        ArrayAdapter arrayAdapter = new ArrayAdapter(this.context, R.layout.multi_language_spinner_item);
        arrayAdapter.setDropDownViewResource(R.layout.multi_language_spinner_dropdown_item);
        arrayAdapter.addAll(this.repository.getAvailableLocales());
        this.supportingAdapter = new ArrayAdapter<>(this.context, R.layout.multi_language_spinner_item);
        this.supportingAdapter.setDropDownViewResource(R.layout.multi_language_spinner_dropdown_item);
        this.supportingAdapter.addAll(this.repository.getAvailableLocales());
        this.primarySpinner.setAdapter((SpinnerAdapter) arrayAdapter);
        this.supportingSpinner.setAdapter((SpinnerAdapter) this.supportingAdapter);
        MultiLanguageConfig c0710e = this.config;
        if (c0710e != null) {
            selectItem(this.primarySpinner, c0710e.getPrimaryLocale());
            selectItem(this.supportingSpinner, this.config.getSupportingLocales().get(0));
        } else {
            selectItem(this.primarySpinner, new LocaleItem(Locale.getDefault().toString()));
        }
        if (isDeleteMode()) {
            this.primarySpinner.setEnabled(false);
            this.supportingSpinner.setEnabled(false);
        }
    }

    private void selectItem(Spinner spinner, LocaleItem c0711f) {
        int count = spinner.getAdapter().getCount();
        for (int i = 0; i < count; i++) {
            if (((LocaleItem) spinner.getItemAtPosition(i)).compareTo(c0711f) == 0) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    boolean isDeleteMode() {
        return this.mode == 0;
    }

    int saveConfig() {
        LocaleItem c0711f = (LocaleItem) this.primarySpinner.getSelectedItem();
        ArrayList arrayList = new ArrayList();
        if (this.supportingSpinner.getSelectedItem() != null) {
            arrayList.add((LocaleItem) this.supportingSpinner.getSelectedItem());
        }
        if (arrayList.size() == 0) {
            return 11;
        }
        this.config = new MultiLanguageConfig(c0711f, arrayList, this.repository.getLayoutSetFor(c0711f));
        return this.repository.addConfig(this.config) ? 10 : 12;
    }

    boolean hasSupportingSelection() {
        return this.supportingSpinner.getSelectedItem() != null;
    }

    boolean deleteConfig() {
        if (this.mode == 0) {
            return this.repository.removeConfig(this.config);
        }
        return false;
    }

    @Override // android.widget.AdapterView.OnItemSelectedListener
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long j) {
        if (isDeleteMode()) {
            return;
        }
        LocaleItem c0711f = (LocaleItem) this.primarySpinner.getSelectedItem();
        ArrayList arrayList = new ArrayList(this.repository.getAvailableLocales());
        arrayList.remove(c0711f);
        this.supportingAdapter.clear();
        this.supportingAdapter.addAll(arrayList);
    }

    MultiLanguageConfig getConfig() {
        return this.config;
    }
}
