package dev.bbkb.ime.core.subtypeswitcher;

import android.content.Intent;
import android.os.Bundle;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;

import androidx.fragment.app.FragmentActivity;

import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageConfig;
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils;
import dev.bbkb.ime.core.locale.multilanguage.SubtypeWizard;
import dev.bbkb.ime.BuildConfig;


public class CombineLanguages extends FragmentActivity implements SubtypeWizard.OnWizardFinishedListener {

    private static final String TAG = "CombineLanguages";

    private String serializedConfig = "";

    private boolean resultDelivered = false;

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(new FrameLayout(this), new FrameLayout.LayoutParams(-1, -1));
        if (bundle == null) {
            SubtypeWizard subtypeWizard = new SubtypeWizard();
            Bundle bundleExtra = getIntent().getBundleExtra("fragment_args");
            this.serializedConfig = bundleExtra.getString("locales");
            subtypeWizard.setArguments(bundleExtra);
            getSupportFragmentManager().beginTransaction().add(android.R.id.content, subtypeWizard).commit();
        }
    }

    @Override // android.app.Activity
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (this.resultDelivered) {
            return;
        }
        setResult(0, null);
        finish();
    }

    @Override // dev.bbkb.ime.core.locale.multilanguage.SubtypeWizard.OnWizardFinishedListener
    public void onWizardFinished(String str) {
        MultiLanguageConfig config = MultiLanguageUtils.parseConfig(str);
        if (config == null || RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme().contains(config.toSubtype())) {
            setResult(0, null);
            finish();
        } else {
            if (!this.serializedConfig.equals(str)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "User chose a different combination");
            }
            this.serializedConfig = str;
            registerSubtypeAndFinish(config);
        }
    }

    private void registerSubtypeAndFinish(MultiLanguageConfig config) {
        InputMethodSubtype subtype = config.toSubtype();
        InputMethodSubtype[] additionalSubtypes = RichInputMethodManager.getInstance().getAdditionalSubtypes(this);
        int length = additionalSubtypes.length;
        boolean z = false;
        int i = 0;
        while (true) {
            if (i >= length) {
                break;
            }
            if (additionalSubtypes[i].equals(subtype)) {
                z = true;
                break;
            }
            i++;
        }
        if (!z) {
            MultiLanguageUtils.appendConfigToPrefs(PrefsManager.INSTANCE.getPrefs(this), config);
            RichInputMethodManager.getInstance().setAdditionalInputMethodSubtypes(RichInputMethodManager.getInstance().getAdditionalSubtypes(this));
        }
        Intent intent = new Intent();
        intent.putExtra("fragment_res", this.serializedConfig);
        setResult(-1, intent);
        MultiLanguageUtils.createEnableSubtypeDialog(this).show();
        this.resultDelivered = true;
    }
}
