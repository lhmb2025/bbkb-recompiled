package dev.bbkb.ime.core.locale.multilanguage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.InAppEventBus;

public class SubtypeWizard extends Fragment {

    public static final String FRAGMENT_TAG = "dev.bbkb.ime.core.locale.multilanguage.SubtypeWizard";

    private View rootView;

    private MultiLanguageDialogController dialogController;

    public static final String ACTION_BACK_PRESSED = "BACK_PRESSED";

    InAppEventBus.EventListener backPressedListener = new InAppEventBus.EventListener() {
        @Override
        public void onEvent(String action, Bundle extras) {
            SubtypeWizard.this.onBackPressed();
        }
    };

    
    public interface OnWizardFinishedListener {
        void onWizardFinished(String str);
    }

    @Override // androidx.fragment.app.Fragment
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        InAppEventBus.getInstance().subscribe(ACTION_BACK_PRESSED, this.backPressedListener);
    }

    /**
     * GD-25: the options menu, migrated off the Fragment {@code setHasOptionsMenu} /
     * {@code onCreateOptionsMenu} / {@code onOptionsItemSelected} trio (deprecated in AndroidX
     * Fragment 1.5) onto {@link MenuProvider}.
     */
    private final MenuProvider menuProvider = new MenuProvider() {
        @Override
        public void onCreateMenu(Menu menu, MenuInflater menuInflater) {
            SubtypeWizard.this.buildMenu(menu);
        }

        @Override
        public boolean onMenuItemSelected(MenuItem menuItem) {
            return SubtypeWizard.this.handleMenuItem(menuItem);
        }
    };

    @Override // androidx.fragment.app.Fragment
    public View onCreateView(LayoutInflater layoutInflater, ViewGroup viewGroup, Bundle bundle) {
        this.rootView = layoutInflater.inflate(R.layout.multi_language_setup_wizard, (ViewGroup) null);
        this.dialogController = createDialogController();
        Activity activity = getActivity();
        this.dialogController.setupSpinners();
        ActionBar actionBar = null;
        if (activity instanceof AppCompatActivity) {
            actionBar = ((AppCompatActivity) activity).getSupportActionBar();
        }
        if (actionBar != null) {
            actionBar.setTitle(R.string.multi_language_input_subtype_wizard_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            Drawable drawable = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_settings_item_delete, null);
            if (drawable != null) {
                // Resources.getColor(int) and Drawable.setColorFilter(int, Mode) are both
                // deprecated across the whole supported range.
                drawable.setTint(ContextCompat.getColor(requireContext(), android.R.color.white));
                actionBar.setHomeAsUpIndicator(drawable);
            }
        }
        return this.rootView;
    }

    MultiLanguageDialogController createDialogController() {
        Activity activity = getActivity();
        MultiLanguageDialogController controller = this.dialogController;
        if (controller == null) {
            return new MultiLanguageDialogController(this.rootView, getArguments(), activity.getApplicationContext(), MultiLanguageRepository.getInstance(activity));
        }
        return new MultiLanguageDialogController(this.rootView, controller, activity.getApplicationContext(), MultiLanguageRepository.getInstance(activity));
    }

    @Override // androidx.fragment.app.Fragment
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        requireActivity().addMenuProvider(this.menuProvider, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void buildMenu(Menu menu) {
        MultiLanguageDialogController controller = this.dialogController;
        if (controller != null && controller.isDeleteMode()) {
            menu.add(0, 1, 0, R.string.user_dict_settings_delete).setIcon(R.drawable.ic_settings_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        } else {
            menu.add(0, 1, 0, R.string.save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        }
    }

    private boolean handleMenuItem(MenuItem menuItem) {
        if (menuItem.getItemId() == 1) {
            if (this.dialogController.isDeleteMode()) {
                if (!this.dialogController.deleteConfig()) {
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            } else {
                saveAndFinish();
            }
            return true;
        }
        if (menuItem.getItemId() != android.R.id.home) {
            return false;
        }
        notifyWizardFinished("");
        return true;
    }

    /**
     * Audit W1-F: this used to be gated on {@code getActivity() instanceof
     * PreferenceActivityHost}. No class in the app implemented that interface, so the guard was
     * constant-false, the sibling {@code finishWithResult(int)} was a permanent no-op, and the
     * home-menu branch above could only ever take this path.
     */
    private void notifyWizardFinished(String str) {
        ((OnWizardFinishedListener) getActivity()).onWizardFinished(str);
    }

    void showAlreadyExistsToast() throws Resources.NotFoundException {
        Toast.makeText(getActivity(), getActivity().getResources().getString(R.string.multi_language_input_already_exist_toast), Toast.LENGTH_SHORT).show();
    }

    void showInvalidSelectionToast() throws Resources.NotFoundException {
        Toast.makeText(getActivity(), getActivity().getResources().getString(R.string.multi_language_input_invalid_selection_toast), Toast.LENGTH_SHORT).show();
    }

    @Override // androidx.fragment.app.Fragment
    public void onDestroy() {
        super.onDestroy();
        InAppEventBus.getInstance().unsubscribe(ACTION_BACK_PRESSED, this.backPressedListener);
    }

    public AlertDialog createDiscardChangesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(getActivity(), R.style.platformDialogTheme));
        builder.setMessage(R.string.discard_event_changes).setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        }).setNegativeButton(R.string.discard, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) {
                SubtypeWizard.this.getActivity().finish();
            }
        }).setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
            @Override // android.content.DialogInterface.OnClickListener
            public void onClick(DialogInterface dialogInterface, int i) throws Resources.NotFoundException {
                SubtypeWizard.this.saveAndFinish();
            }
        });
        return builder.create();
    }

    public void onBackPressed() {
        if (!this.dialogController.hasSupportingSelection()) {
            if (getActivity().isFinishing()) {
                return;
            }
            getActivity().finish();
            return;
        }
        createDiscardChangesDialog().show();
    }

    public void saveAndFinish() throws Resources.NotFoundException {
        int saveResult = this.dialogController.saveConfig();
        if (saveResult == 10) {
            notifyWizardFinished(MultiLanguageUtils.serializeConfig(this.dialogController.getConfig()));
        } else if (saveResult == 12) {
            showAlreadyExistsToast();
            notifyWizardFinished("");
        } else if (saveResult == 11) {
            showInvalidSelectionToast();
            notifyWizardFinished("");
        }
    }
}
