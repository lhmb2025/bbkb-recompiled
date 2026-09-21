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
        ((TextView) view.findViewById(R.id.subtype_switcher_language_name)).setText(this.items.get(i).displayName);
        ((RadioButton) view.findViewById(R.id.subtype_switcher_radio)).setChecked(i == this.selectedPosition);
        return view;
    }
}
