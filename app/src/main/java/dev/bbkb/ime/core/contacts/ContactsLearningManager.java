package dev.bbkb.ime.core.contacts;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.provider.ContactsContract;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.permissions.PermissionRequestHandler;
import dev.bbkb.ime.core.permissions.PermissionUtils;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.ThreadUtils;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;



public class ContactsLearningManager implements ContactsDataProvider.Listener {

    private static final String TAG = "ContactsLearning";

    private final Context context;

    private final ContactsDataProvider dataProvider;

    private boolean contactsDictEnabled = false;

    private boolean initialSyncStarted = false;
    
    private static final int NUANCE_READY_CHECK_DELAY_MS = 500;
    private static final int NUANCE_READY_MAX_RETRIES = 10;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ContactsLearningManager(Context context) {
        this.context = context;
        this.dataProvider = new ContactsDataProvider(context);
    }

    public void requestContactsPermissionIfNeeded(EditorInfo editorInfo) {
        if (PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
            startInitialSync();
            return;
        }
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);
        if (SettingsManager.hasAskedForContactsPermission(defaultSharedPreferences) || !ContactsQueryUtils.isContactsPromptEligibleField(editorInfo)) {
            return;
        }
        SettingsManager.setHasAskedContactsDictPermission(defaultSharedPreferences, true);
        new PermissionRequestHandler(this.context, new PermissionRequestHandler.Callback() {
            @Override
            public void onPermissionResult(String str, PermissionRequestHandler.Result cVar) {
                SharedPreferences defaultSharedPreferences2 = PrefsManager.INSTANCE.getPrefs(ContactsLearningManager.this.context);
                if (cVar == PermissionRequestHandler.Result.PERMISSION_GRANTED) {
                    SettingsManager.enableContactsDict(defaultSharedPreferences2);
                    ContactsLearningManager.this.contactsDictEnabled = true;
                    ContactsLearningManager.this.initialSyncStarted = false;
                    ContactsLearningManager.this.startInitialSync();
                }
                SettingsManager.setHasAskedContactsDictPermission(defaultSharedPreferences2, cVar != PermissionRequestHandler.Result.OTHER);
            }
        }).requestPermission("android.permission.READ_CONTACTS", this.context.getString(R.string.contact_permission_rationale));
    }

    public void unregister() {
        this.dataProvider.unregister();
    }

    public void setContactsDictEnabled(boolean z) {
        if (this.contactsDictEnabled != z) {
            this.contactsDictEnabled = z;
            if (this.contactsDictEnabled) {
                if (PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
                    syncContacts(ContactsDataProvider.ContactsType.ALL, this);
                    return;
                }
                return;
            }
            stopLearning();
        }
    }

    @Override
    public void onContactsChanged(ContactsDataProvider.ContactsType bVar) {
        if (this.contactsDictEnabled) {
            syncContacts(bVar, this);
        }
    }

        synchronized void startInitialSync() {
        if (this.contactsDictEnabled && !this.initialSyncStarted) {
            syncContacts(ContactsDataProvider.ContactsType.ALL, this);
            this.initialSyncStarted = true;
        }
    }

    private void syncContacts(final ContactsDataProvider.ContactsType bVar, final ContactsDataProvider.Listener aVar) {
        m4557aWithRetry(bVar, aVar, 0);
    }
    
    private void m4557aWithRetry(final ContactsDataProvider.ContactsType bVar, final ContactsDataProvider.Listener aVar, final int retryCount) {
        // Check if NuanceSDK DLM is ready before syncing contacts
        if (!NuanceSDKManager.isDLMReady()) {
            if (retryCount < NUANCE_READY_MAX_RETRIES) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        m4557aWithRetry(bVar, aVar, retryCount + 1);
                    }
                }, NUANCE_READY_CHECK_DELAY_MS);
            }
            return;
        }
        
        ThreadUtils.getBackgroundExecutor("Sync Contacts").execute(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                ContactsLearningManager.this.dataProvider.registerListener(aVar);
                ContactsLearningManager.this.learnAllContacts(bVar);
            }
        });
    }

    private void stopLearning() {
        ThreadUtils.getBackgroundExecutor("Sync Contacts").execute(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                Logger.info(ContactsLearningManager.TAG, "Stopped contacts learning");
                ContactsLearningManager.this.dataProvider.unregister();
            }
        });
    }

        void learnAllContacts(ContactsDataProvider.ContactsType bVar) {
        learnAccountEmails();
        learnContactsFromUri(bVar, ContactsContract.Profile.CONTENT_URI);
        learnContactsFromUri(bVar, ContactsContract.Contacts.CONTENT_URI);
    }

    private void learnAccountEmails() {
        List<String> listM4552a = AccountUtils.getDeviceAccountsEmailAddresses(this.context);
        if (listM4552a == null || listM4552a.isEmpty()) {
            return;
        }
        addNamesToDynamicModel(listM4552a, ContactsDataProvider.ContactsType.ALL);
    }

    private void learnContactsFromUri(ContactsDataProvider.ContactsType bVar, Uri uri) {
        if (!PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
            // Without this return the query below throws SecurityException on the
            // "Sync Contacts" executor - and SecurityException is not the SQLiteException the
            // observer catches.
            Logger.info(TAG, "No permission to read contacts. Not adding to dynamic model");
            return;
        }
        ArrayList<String> arrayList = new ArrayList<>();
        // WORK contacts are never returned by the data provider, so the WORK arms only ever
        // collected an empty list.
        if (bVar == ContactsDataProvider.ContactsType.PERSONAL || bVar == ContactsDataProvider.ContactsType.ALL) {
            arrayList.addAll(this.dataProvider.getContactNames(ContactsDataProvider.ContactsType.PERSONAL, uri));
        }
        addNamesToDynamicModel(arrayList, ContactsDataProvider.ContactsType.PERSONAL);
        if (uri.equals(ContactsContract.Contacts.CONTENT_URI)) {
            this.dataProvider.saveContactsSnapshot(ContactsDataProvider.ContactsType.PERSONAL, arrayList);
        }
    }

    public void addNamesToDynamicModel(List<String> list, ContactsDataProvider.ContactsType bVar) {
        ArrayList arrayList = new ArrayList();
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            arrayList.addAll(Arrays.asList(it.next().split("\\s*(,|\\s+)\\s*")));
        }
        if (arrayList.size() > 0) {
            Context context = this.context;
            if (context instanceof BlackBerryIME) {
                ((BlackBerryIME) context).getDynamicLearningManager().addContactsWords((String[]) arrayList.toArray(new String[0]), bVar);
            }
        }
    }
}
