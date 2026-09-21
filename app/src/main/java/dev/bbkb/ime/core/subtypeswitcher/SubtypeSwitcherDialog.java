package dev.bbkb.ime.core.subtypeswitcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ImageButton;
import android.widget.ListView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.InAppEventBus;
import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.locale.multilanguage.LocaleItem;
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageConfig;
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.settings.IntentUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;


// Suppress deprecation warnings for Activity Result APIs - this dialog-style Activity
// extends base Activity and would require significant refactoring to use modern APIs.
@SuppressWarnings("deprecation")
public class SubtypeSwitcherDialog extends Activity implements DialogInterface.OnClickListener, DialogInterface.OnDismissListener {

    private static final String TAG = "SubtypeSwitcherDialog";

    private static boolean DEBUG = false;

    private RichInputMethodManager richImm;

    private List<InputMethodSubtype> enabledSubtypes;

    private List<SubtypeItem> subtypeItems;

    private AlertDialog.Builder dialogBuilder;

    private AlertDialog dialog;

    private AlertDialog dismissingDialog;

    private SubtypeManager subtypeManager;

    private boolean launchingSubActivity = false;

    private boolean canCombineLanguages = false;

    private MultiLanguageConfig multiLanguageConfig = null;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (!RichInputMethodManager.isInitialized()) {
            RichInputMethodManager.init(this);
        }
        this.richImm = RichInputMethodManager.getInstance();
        this.subtypeManager = SubtypeManager.getInstance();
        showSwitcherDialog();
    }

    @Override // android.app.Activity
    public void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        this.launchingSubActivity = false;
        if (i == 10 && i2 == -1) {
            this.multiLanguageConfig = MultiLanguageUtils.parseConfig(intent.getStringExtra("fragment_res"));
            List<InputMethodSubtype> listM4860a = RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme();
            InputMethodSubtype inputMethodSubtypeM4756d = this.multiLanguageConfig.toSubtype();
            postResultAndFinish(listM4860a.indexOf(inputMethodSubtypeM4756d));
            return;
        }
        AlertDialog alertDialog = this.dialog;
        if (alertDialog != null && alertDialog.isShowing()) {
            this.canCombineLanguages = false;
            AlertDialog alertDialog2 = this.dialog;
            this.dismissingDialog = alertDialog2;
            alertDialog2.dismiss();
        }
        showSwitcherDialog();
    }

    @Override // android.app.Activity
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (this.launchingSubActivity) {
            return;
        }
        postResultAndFinish(-2);
    }

    @Override // android.content.DialogInterface.OnDismissListener
    public void onDismiss(DialogInterface dialogInterface) {
        AlertDialog alertDialog = this.dismissingDialog;
        if ((alertDialog == null || !alertDialog.equals(dialogInterface)) && !this.launchingSubActivity) {
            postResultAndFinish(-1);
        }
        this.dismissingDialog = null;
    }

    @Override // android.content.DialogInterface.OnClickListener
    public void onClick(DialogInterface dialogInterface, int i) {
        postResultAndFinish(this.subtypeItems.get(i).subtypeIndex);
    }

    private void postResultAndFinish(int i) {
        Bundle extras = new Bundle();
        extras.putInt(SubtypeSwitcherReceiver.EXTRA_RESULT, i);
        InAppEventBus.getInstance().post(SubtypeSwitcherReceiver.ACTION_SUBTYPE_SWITCH_RESULT, extras);
        dismissAndFinish();
    }

    void dismissAndFinish() {
        if (this.dialog != null) {
            if (DEBUG) {
                Logger.verbose(TAG, "Hide Subtype switching menu");
            }
            this.dialog.dismiss();
        }
        finish();
    }

    private void showSwitcherDialog() {
        SubtypeSwitcherAdapter c0729bM4855e = createAdapter();
        int i = c0729bM4855e.selectedPosition;
        this.dialogBuilder = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
        View viewInflate = getLayoutInflater().inflate(R.layout.subtype_switcher_title, (ViewGroup) null);
        final ImageButton imageButton = (ImageButton) viewInflate.findViewById(R.id.subtype_switcher_settings_btn);
        imageButton.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                imageButton.setPressed(true);
                SubtypeSwitcherDialog.this.launchingSubActivity = true;
                SubtypeSwitcherDialog.this.startActivityForResult(IntentUtils.getInputLanguageSelectionIntent(RichInputMethodManager.getInstance().getInputMethodIdOfThisIme(), View.MeasureSpec.EXACTLY), 0);
            }
        });
        this.dialogBuilder.setCustomTitle(viewInflate);
        this.dialogBuilder.setTitle(R.string.subtype_switcher_dialog_header);
        this.dialogBuilder.setCancelable(false);
        this.dialogBuilder.setSingleChoiceItems(c0729bM4855e, i, this);
        this.dialogBuilder.setOnDismissListener(this);
        if (this.canCombineLanguages) {
            this.dialogBuilder.setPositiveButton(R.string.combine_subtypes, new DialogInterface.OnClickListener() {
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int i2) {
                    SubtypeSwitcherDialog.this.launchCombineLanguages();
                }
            });
        }
        this.dialog = this.dialogBuilder.create();
        this.dialog.setCanceledOnTouchOutside(true);
        this.dialog.getListView().setScrollbarFadingEnabled(false);
        this.dialog.show();
        if (this.subtypeItems.size() > 3) {
            int iM4853c = (measureListItemHeight() * 2) + measureDecorHeight();
            WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
            layoutParams.copyFrom(this.dialog.getWindow().getAttributes());
            layoutParams.height = iM4853c;
            this.dialog.getWindow().setAttributes(layoutParams);
        }
    }

    private int measureListItemHeight() {
        ListView listView = this.dialog.getListView();
        View view = listView.getAdapter().getView(0, null, listView);
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    private int measureDecorHeight() {
        View decorView = this.dialog.getWindow().getDecorView();
        decorView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return decorView.getMeasuredHeight();
    }

    private SubtypeSwitcherAdapter createAdapter() {
        this.enabledSubtypes = RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme();
        this.subtypeItems = buildSubtypeItems();
        this.multiLanguageConfig = buildMultiLanguageConfig(this.subtypeItems);
        InputMethodSubtype inputMethodSubtypeM4261j = this.subtypeManager.getCurrentSubtype();
        int i = 0;
        if (inputMethodSubtypeM4261j != null) {
            int iIndexOf = this.enabledSubtypes.indexOf(inputMethodSubtypeM4261j);
            int i2 = 0;
            while (i < this.subtypeItems.size()) {
                if (this.subtypeItems.get(i).subtypeIndex == iIndexOf) {
                    i2 = i;
                }
                i++;
            }
            i = i2;
        }
        return new SubtypeSwitcherAdapter(this, R.layout.subtype_switcher_list_view, this.subtypeItems, i);
    }

    private List<SubtypeItem> buildSubtypeItems() {
        ArrayList<InputMethodSubtype> arrayListM5894g = this.richImm.getSubtypeSwitchHistory();
        ArrayList arrayList = new ArrayList();
        for (int size = arrayListM5894g.size() - 1; size >= 0; size--) {
            InputMethodSubtype inputMethodSubtype = arrayListM5894g.get(size);
            if (this.enabledSubtypes.contains(inputMethodSubtype) && !inputMethodSubtype.isAuxiliary()) {
                SubtypeItem c0730cM4846a = createSubtypeItem(inputMethodSubtype);
                if (!containsSubtypeIndex(arrayList, c0730cM4846a)) {
                    arrayList.add(c0730cM4846a);
                }
            }
        }
        for (InputMethodSubtype inputMethodSubtype2 : this.enabledSubtypes) {
            if (!inputMethodSubtype2.isAuxiliary()) {
                SubtypeItem c0730cM4846a2 = createSubtypeItem(inputMethodSubtype2);
                if (!containsSubtypeIndex(arrayList, c0730cM4846a2)) {
                    arrayList.add(c0730cM4846a2);
                }
            }
        }
        return arrayList;
    }

    private SubtypeItem createSubtypeItem(InputMethodSubtype inputMethodSubtype) {
        InputMethodInfo inputMethodInfoM5890d = this.richImm.getInputMethodInfoOfThisIme();
        return new SubtypeItem(inputMethodSubtype.overridesImplicitlyEnabledSubtype() ? null : inputMethodSubtype.getDisplayName(this, inputMethodInfoM5890d.getPackageName(), inputMethodInfoM5890d.getServiceInfo().applicationInfo), this.enabledSubtypes.indexOf(inputMethodSubtype), inputMethodSubtype.getLocale(), Locale.getDefault().getLanguage());
    }

    private boolean containsSubtypeIndex(List<SubtypeItem> list, SubtypeItem c0730c) {
        Iterator<SubtypeItem> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().subtypeIndex == c0730c.subtypeIndex) {
                return true;
            }
        }
        return false;
    }

        void launchCombineLanguages() {
        this.launchingSubActivity = true;
        Intent intent = new Intent();
        intent.setClass(this, CombineLanguages.class);
        Bundle bundle = new Bundle();
        bundle.putInt("mode", 1);
        bundle.putString("locales", MultiLanguageUtils.serializeConfig(this.multiLanguageConfig));
        intent.putExtra("fragment_args", bundle);
        startActivityForResult(intent, 10);
    }

    MultiLanguageConfig buildMultiLanguageConfig(List<SubtypeItem> list) {
        InputMethodSubtype inputMethodSubtypeM5888c = this.richImm.getCurrentInputMethodSubtype((InputMethodSubtype) null);
        if (!isCombinableSubtype(inputMethodSubtypeM5888c)) {
            return null;
        }
        ArrayList arrayList = new ArrayList();
        for (int i = 1; i < list.size(); i++) {
            InputMethodSubtype inputMethodSubtype = this.enabledSubtypes.get(list.get(i).subtypeIndex);
            if (isCombinableSubtype(inputMethodSubtype)) {
                arrayList.add(new LocaleItem(inputMethodSubtype.getLocale()));
                if (arrayList.size() >= 2) {
                    break;
                }
            }
        }
        if (arrayList.isEmpty()) {
            return null;
        }
        this.canCombineLanguages = true;
        return new MultiLanguageConfig(new LocaleItem(inputMethodSubtypeM5888c.getLocale()), arrayList, ResourceLocaleUtils.getKeyboardLayoutSetName(inputMethodSubtypeM5888c));
    }

    private boolean isCombinableSubtype(InputMethodSubtype inputMethodSubtype) {
        return (!InputMethodSubtypeCompat.isAsciiCapable(inputMethodSubtype) || SubtypeFactory.isAdditionalSubtype(inputMethodSubtype) || inputMethodSubtype.getLocale().equals("zz")) ? false : true;
    }

}
