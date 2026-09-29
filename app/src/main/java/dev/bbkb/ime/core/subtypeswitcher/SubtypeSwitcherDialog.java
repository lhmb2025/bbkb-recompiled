package dev.bbkb.ime.core.subtypeswitcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.LayoutInflater;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.view.inputmethod.InputMethodManager;
import android.content.pm.PackageManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.ImageButton;
import android.widget.ListView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.InAppEventBus;
import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.settings.IntentUtils;
import dev.bbkb.ime.core.shared.Logger;

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
        AlertDialog alertDialog = this.dialog;
        if (alertDialog != null && alertDialog.isShowing()) {
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
        final SubtypeItem item = this.subtypeItems.get(i);
        if (item.imeId != null) {
            // Another input method: the IME process does the switch, since only it holds the
            // token Android wants for that.
            Bundle extras = new Bundle();
            extras.putInt(SubtypeSwitcherReceiver.EXTRA_RESULT, SubtypeSwitcherReceiver.RESULT_OTHER_IME);
            extras.putString(SubtypeSwitcherReceiver.EXTRA_IME_ID, item.imeId);
            InAppEventBus.getInstance().post(SubtypeSwitcherReceiver.ACTION_SUBTYPE_SWITCH_RESULT, extras);
            dismissAndFinish();
            return;
        }
        postResultAndFinish(item.subtypeIndex);
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
        final Context themed = themedContext();
        this.dialogBuilder = new AlertDialog.Builder(themed);
        View viewInflate = LayoutInflater.from(themed).inflate(R.layout.subtype_switcher_title, (ViewGroup) null);
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
        // Adding prediction languages to a keyboard now lives on the Language screen (the old
        // combine wizard is gone from the menus), so the button opens that screen, for any keyboard.
        this.dialogBuilder.setPositiveButton(R.string.combine_subtypes, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i2) {
                SubtypeSwitcherDialog.this.openLanguageScreen();
            }
        });
        this.dialog = this.dialogBuilder.create();
        this.dialog.setCanceledOnTouchOutside(true);
        this.dialog.getListView().setScrollbarFadingEnabled(false);
        this.dialog.show();
        // Cap the list at five rows (it used to be two once there were more than three items,
        // which hid the "Other keyboards" section below the fold on the KEY2's short screen).
        if (this.subtypeItems.size() > 5) {
            int iM4853c = (measureListItemHeight() * 5) + measureDecorHeight();
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
        // "Include other keyboards": the other enabled input methods, after this IME's languages.
        if (PrefsManager.INSTANCE.getPrefs(this).getBoolean("pref_include_other_imes_in_language_switch_list", false)) {
            final InputMethodManager imm = this.richImm.getInputMethodManager();
            final List<SubtypeItem> others = otherImeItems(
                    imm.getEnabledInputMethodList(), this.richImm.getInputMethodIdOfThisIme(), getPackageManager());
            this.subtypeItems.add(new SubtypeItem(getString(R.string.subtype_switcher_other_keyboards), null));
            if (others.isEmpty()) {
                this.subtypeItems.add(SubtypeItem.note(getString(R.string.subtype_switcher_no_other_keyboards)));
            } else {
                this.subtypeItems.addAll(others);
            }
        }
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
        return new SubtypeSwitcherAdapter(themedContext(), R.layout.subtype_switcher_list_view, this.subtypeItems, i);
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

    /**
     * The dialog's colours follow BBKB's theme mode (pref_keyboard_theme_mode: auto / light / dark),
     * the same rule BlackBerryTheme applies to the settings screens.
     */
    private Context themedContext() {
        if (this.themedContext == null) {
            this.themedContext = dev.bbkb.ime.core.settings.ui.BbkbDialogs.themedContext(this);
        }
        return this.themedContext;
    }

    private Context themedContext;

    /** "Combine compatible languages": the Language screen, where a keyboard's extras are set. */
    void openLanguageScreen() {
        Intent intent = new Intent(this, dev.bbkb.ime.core.settings.ComposeSettingsActivity.class);
        intent.putExtra("screen", "languages_hub");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        postResultAndFinish(-1);
    }

    /**
     * One row per other enabled input method: everything in {@code enabled} except this IME and
     * the auxiliary ones (voice typing, autofill proxies), which Android's own picker hides too.
     * Labels come from the other apps. Static, for the unit test.
     */
    static List<SubtypeItem> otherImeItems(List<InputMethodInfo> enabled, String thisImeId, PackageManager pm) {
        final List<SubtypeItem> rows = new ArrayList<>();
        if (enabled == null) {
            return rows;
        }
        for (InputMethodInfo imi : enabled) {
            if (imi == null || imi.getId().equals(thisImeId) || isAuxiliaryIme(imi)) {
                continue;
            }
            rows.add(new SubtypeItem(imi.loadLabel(pm), imi.getId()));
        }
        return rows;
    }

    /** The framework's own definition ({@code InputMethodInfo#isAuxiliaryIme} is hidden API). */
    private static boolean isAuxiliaryIme(InputMethodInfo imi) {
        final int count = imi.getSubtypeCount();
        if (count == 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (!imi.getSubtypeAt(i).isAuxiliary()) {
                return false;
            }
        }
        return true;
    }

}
