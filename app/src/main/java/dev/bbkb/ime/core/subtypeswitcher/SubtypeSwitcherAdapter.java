package dev.bbkb.ime.core.subtypeswitcher;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.TextView;

import dev.bbkb.ime.R;

import java.util.List;



public class SubtypeSwitcherAdapter extends ArrayAdapter<SubtypeItem> {

    public int selectedPosition;

    private final LayoutInflater inflater;

    private final int layoutResId;

    private final List<SubtypeItem> items;

    SubtypeSwitcherAdapter(Context context, int i, List<SubtypeItem> list, int i2) {
        super(context, i, list);
        this.layoutResId = i;
        this.items = list;
        this.selectedPosition = i2;
        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override // android.widget.ArrayAdapter, android.widget.Adapter
    public View getView(int i, View view, ViewGroup viewGroup) {
        if (view == null) {
            view = this.inflater.inflate(this.layoutResId, (ViewGroup) null);
        }
        if (i < 0 || i >= this.items.size()) {
            return view;
        }
        final SubtypeItem item = this.items.get(i);
        final TextView name = view.findViewById(R.id.subtype_switcher_language_name);
        final RadioButton radio = view.findViewById(R.id.subtype_switcher_radio);
        name.setText(item.displayName);
        // A header is a list subheader: small, dimmed, no radio. Another keyboard's row has no
        // radio either - it is never "the selected language", it is a way out of this IME.
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, item.isHeader ? 13 : (item.isNote ? 14 : 16));
        name.setAlpha(item.isHeader || item.isNote ? 0.6f : 1f);
        radio.setVisibility(item.subtypeIndex >= 0 ? View.VISIBLE : View.INVISIBLE);
        radio.setChecked(i == this.selectedPosition);
        return view;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int i) {
        return i >= 0 && i < this.items.size() && !this.items.get(i).isHeader && !this.items.get(i).isNote;
    }
}
