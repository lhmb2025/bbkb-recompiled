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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;



public class ContactsLearningManager implements ContactsDataProvider.Listener {

    private static final String TAG = "ContactsLearning";

    private final Context context;

    private final ContactsDataProvider dataProvider;

    private boolean contactsDictEnabled = false;

    private boolean initialSyncStarted = false;

    /**
     * The name words of the last full sync, as they went to the engine; empty while contacts are
     * not used. The spell checker keeps them with learned words left out ({@link #isContactWord}).
     */
    private volatile Set<String> contactWords = Collections.emptySet();

    /** The words the sync in progress has collected so far; null between syncs. */
    private volatile Set<String> collectingWords;
    
    /**
     * What the keyboard last added to the engine, kept across process starts: the names persist in
     * the learned model (category 0xFF01, alphadlm.bin), and re-adding them holds the engine lock for
     * one long native call. Not backed up, as the model it describes is not.
     */
    private static final String SYNC_STATE_PREFS = "contacts_learning";
    private static final String KEY_ADDED_FINGERPRINT = "added_fingerprint";
    private static final String KEY_ADDED_AT = "added_at";
    /**
     * An unchanged address book is added again after this long, for a model that lost the names
     * (an import, a reset, an unsaved model when the process died).
     */
    static final long READD_AFTER_MS = 24L * 60 * 60 * 1000;

    private static final int NUANCE_READY_CHECK_DELAY_MS = 500;
    private static final int NUANCE_READY_MAX_RETRIES = 10;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * {@link #unregister} has run. A pending sync must then stop, or it registers the contacts
     * observer on the dead service, leaking it and learning every later change twice.
     */
    private volatile boolean released;

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
            /**
             * @param permission the permission
             * @param result the user's answer
             */
            @Override
            public void onPermissionResult(String permission, PermissionRequestHandler.Result result) {
                SharedPreferences defaultSharedPreferences2 = PrefsManager.INSTANCE.getPrefs(ContactsLearningManager.this.context);
                if (result == PermissionRequestHandler.Result.PERMISSION_GRANTED) {
                    SettingsManager.enableContactsDict(defaultSharedPreferences2);
                    ContactsLearningManager.this.contactsDictEnabled = true;
                    ContactsLearningManager.this.initialSyncStarted = false;
                    ContactsLearningManager.this.startInitialSync();
                }
                SettingsManager.setHasAskedContactsDictPermission(defaultSharedPreferences2, result != PermissionRequestHandler.Result.OTHER);
            }
        }).requestPermission("android.permission.READ_CONTACTS", this.context.getString(R.string.contact_permission_rationale));
    }

    public void unregister() {
        this.released = true;
        this.mainHandler.removeCallbacksAndMessages(null);
        this.dataProvider.release();
    }

    /** @param enabled true if the contacts dictionary is on */
    public void setContactsDictEnabled(boolean enabled) {
        if (this.contactsDictEnabled != enabled) {
            this.contactsDictEnabled = enabled;
            if (this.contactsDictEnabled) {
                if (PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
                    syncContacts(ContactsDataProvider.ContactsType.ALL, this);
                    return;
                }
                return;
            }
            this.contactWords = Collections.emptySet();
            stopLearning();
        }
    }

    /** Whether {@code word} is a word of a contact's name or an account address, exact spelling. */
    public boolean isContactWord(String word) {
        return this.contactWords.contains(word);
    }

    /** @param contactsType which contacts changed */
    @Override
    public void onContactsChanged(ContactsDataProvider.ContactsType contactsType) {
        if (this.contactsDictEnabled) {
            syncContacts(contactsType, this);
        }
    }

        synchronized void startInitialSync() {
        if (this.contactsDictEnabled && !this.initialSyncStarted) {
            syncContacts(ContactsDataProvider.ContactsType.ALL, this);
            this.initialSyncStarted = true;
        }
    }

    /**
     * @param contactsType which contacts to sync
     * @param listener the listener to register for later changes
     */
    private void syncContacts(final ContactsDataProvider.ContactsType contactsType, final ContactsDataProvider.Listener listener) {
        m4557aWithRetry(contactsType, listener, 0);
    }

    /**
     * @param contactsType which contacts to sync
     * @param listener the listener to register for later changes
     */
    private void m4557aWithRetry(final ContactsDataProvider.ContactsType contactsType, final ContactsDataProvider.Listener listener, final int retryCount) {
        // Only the keyboard waits for the DLM: the spell checker's sync just collects contactWords,
        // and the keyboard's engine may not be running. Reads the published fact: this is main.
        if (this.released) {
            return;
        }
        if (this.context instanceof BlackBerryIME && !NuanceSDKManager.facts().isDLMReady()) {
            if (retryCount < NUANCE_READY_MAX_RETRIES) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        m4557aWithRetry(contactsType, listener, retryCount + 1);
                    }
                }, NUANCE_READY_CHECK_DELAY_MS);
            }
            return;
        }
        
        ThreadUtils.getBackgroundExecutor("Sync Contacts").execute(new Runnable() {
            @Override // java.lang.Runnable
            public void run() {
                if (ContactsLearningManager.this.released) {
                    return;
                }
                ContactsLearningManager.this.dataProvider.registerListener(listener);
                ContactsLearningManager.this.learnAllContacts(contactsType);
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

    /** @param contactsType which contacts to learn */
        void learnAllContacts(ContactsDataProvider.ContactsType contactsType) {
        final Set<String> words = ConcurrentHashMap.newKeySet();
        this.collectingWords = words;
        // Collected first and added together, so an address book the engine already holds can
        // be skipped as a whole.
        final List<String[]> batches = new ArrayList<>();
        final List<ContactsDataProvider.ContactsType> batchTypes = new ArrayList<>();
        try {
            collectContactsFromUri(contactsType, ContactsContract.Profile.CONTENT_URI, batches, batchTypes);
            collectContactsFromUri(contactsType, ContactsContract.Contacts.CONTENT_URI, batches, batchTypes);
        } finally {
            this.collectingWords = null;
        }
        if (this.contactsDictEnabled) {
            this.contactWords = words;
        }
        if (!(this.context instanceof BlackBerryIME) || batches.isEmpty()) {
            return;
        }
        final String fingerprint = fingerprintOf(batches, batchTypes, learningPreferenceOn());
        final SharedPreferences state = this.context.getApplicationContext()
                .getSharedPreferences(SYNC_STATE_PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (isAlreadyAdded(fingerprint, state.getString(KEY_ADDED_FINGERPRINT, null),
                state.getLong(KEY_ADDED_AT, 0L), now)) {
            Logger.info(TAG, "Contacts unchanged since they were added; not adding them again");
            return;
        }
        for (int i = 0; i < batches.size(); i++) {
            addWordsToDynamicModel(batches.get(i), batchTypes.get(i));
        }
        state.edit().putString(KEY_ADDED_FINGERPRINT, fingerprint).putLong(KEY_ADDED_AT, now).apply();
    }

    /** Whether the words described by {@code fingerprint} went to the engine recently enough. */
    static boolean isAlreadyAdded(String fingerprint, String addedFingerprint, long addedAt, long now) {
        return fingerprint.equals(addedFingerprint) && addedAt > 0L && addedAt <= now
                && now - addedAt < READD_AFTER_MS;
    }

    /**
     * A digest of what one sync would add: the words in order, batch types, and whether learning is
     * on (an add with learning off is dropped, so turning it on must not look like "already added").
     */
    static String fingerprintOf(List<String[]> batches, List<ContactsDataProvider.ContactsType> types, boolean learningOn) {
        long h = 1125899906842597L;
        for (int i = 0; i < batches.size(); i++) {
            h = 31 * h + types.get(i).ordinal();
            for (String word : batches.get(i)) {
                h = 31 * h + word.hashCode();
                h = 31 * h + word.length();
                h = 31 * h + 0x1f;
            }
            h = 31 * h + 0x1e;
        }
        return batches.size() + ":" + Long.toHexString(h) + (learningOn ? ":1" : ":0");
    }

    private static boolean learningPreferenceOn() {
        final SettingsManager settings = SettingsManager.getInstance();
        return settings == null || settings.getSettingsValues() == null
                || settings.getSettingsValues().isDynamicLearningEnabled;
    }

    /** @param contactsType which contacts to collect */
    private void collectContactsFromUri(ContactsDataProvider.ContactsType contactsType, Uri uri,
            List<String[]> batches, List<ContactsDataProvider.ContactsType> types) {
        if (!PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
            // Otherwise the query throws SecurityException, which the observer does not catch.
            Logger.info(TAG, "No permission to read contacts. Not adding to dynamic model");
            return;
        }
        ArrayList<String> arrayList = new ArrayList<>();
        // The data provider never returns WORK contacts.
        if (contactsType == ContactsDataProvider.ContactsType.PERSONAL || contactsType == ContactsDataProvider.ContactsType.ALL) {
            arrayList.addAll(this.dataProvider.getContactNames(ContactsDataProvider.ContactsType.PERSONAL, uri));
        }
        collectWords(arrayList, ContactsDataProvider.ContactsType.PERSONAL, batches, types);
        if (uri.equals(ContactsContract.Contacts.CONTENT_URI)) {
            this.dataProvider.saveContactsSnapshot(ContactsDataProvider.ContactsType.PERSONAL, arrayList);
        }
    }

    /** @param list contact names or addresses */
    private static String[] splitNames(List<String> list) {
        ArrayList<String> arrayList = new ArrayList<>();
        for (String name : list) {
            arrayList.addAll(Arrays.asList(name.split("\\s*(,|\\s+)\\s*")));
        }
        return arrayList.toArray(new String[0]);
    }

    /**
     * @param list contact names or addresses
     * @param contactsType the batch's contacts type
     */
    private void collectWords(List<String> list, ContactsDataProvider.ContactsType contactsType,
            List<String[]> batches, List<ContactsDataProvider.ContactsType> types) {
        final String[] words = splitNames(list);
        final Set<String> collecting = this.collectingWords;
        if (collecting != null) {
            collecting.addAll(Arrays.asList(words));
        }
        if (words.length > 0) {
            batches.add(words);
            types.add(contactsType);
        }
    }

    /** @param contactsType the batch's contacts type */
    private void addWordsToDynamicModel(String[] words, ContactsDataProvider.ContactsType contactsType) {
        if (words.length > 0 && this.context instanceof BlackBerryIME) {
            ((BlackBerryIME) this.context).getDynamicLearningManager().addContactsWords(words, contactsType);
        }
    }

    /**
     * Adds the words of {@code list} (names or addresses) to the engine now, unconditionally.
     *
     * @param contactsType the contacts type
     */
    public void addNamesToDynamicModel(List<String> list, ContactsDataProvider.ContactsType contactsType) {
        final String[] words = splitNames(list);
        final Set<String> collecting = this.collectingWords;
        if (collecting != null) {
            collecting.addAll(Arrays.asList(words));
        }
        addWordsToDynamicModel(words, contactsType);
    }
}
