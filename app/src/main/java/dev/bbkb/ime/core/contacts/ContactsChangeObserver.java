package dev.bbkb.ime.core.contacts;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.util.Log;

import dev.bbkb.ime.core.permissions.PermissionUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.ThreadUtils;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.bbkb.ime.BuildConfig;



public class ContactsChangeObserver implements Runnable {

    private static final String TAG = "ContactsChangeObserver";

    private final Context context;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private ContentObserver contentObserver;

    private ContactsDataProvider.Listener listener;

    private int savedContactCount;

    private int savedNamesHashCode;

    /**
     * How long the address book must stay quiet before it is queried. The provider notifies once
     * per row it touches, so a sync or import sends a burst of notifications.
     */
    static final long CHANGE_DEBOUNCE_MS = 3000L;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());

    private final Runnable checkAfterQuiet = new Runnable() {
        @Override
        public void run() {
            ThreadUtils.getBackgroundExecutor("Sync Contacts").execute(ContactsChangeObserver.this);
        }
    };

    public ContactsChangeObserver(Context context) {
        this.context = context;
    }

    /** @param listener listener told after the address book has stayed quiet */
    public void registerObserver(ContactsDataProvider.Listener listener) {
        if (!PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
            Logger.info(TAG, "No permission to read contacts. Not registering the observer.");
            return;
        }
        if (this.contentObserver != null) {
            Logger.debug(TAG, "Content observer already exists, unregister Observer");
            unregisterObserver();
        }
        Logger.debug(TAG, "registerObserver()");
        this.listener = listener;
        this.contentObserver = new ContentObserver(null) {
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                // Trailing edge: each notification pushes the check back.
                debounceHandler.removeCallbacks(checkAfterQuiet);
                debounceHandler.postDelayed(checkAfterQuiet, CHANGE_DEBOUNCE_MS);
            }
        };
        this.context.getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, this.contentObserver);
    }

    @Override // java.lang.Runnable
    public void run() {
        if (!PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
            Logger.info(TAG, "No permission to read contacts. Not updating the contacts.");
            unregisterObserver();
        } else {
            if (!this.running.compareAndSet(false, true)) {
                Logger.debug(TAG, "run() : Already running. Don't waste time checking again.");
                return;
            }
            if (haveContentsChanged()) {
                Logger.debug(TAG, "run() : Contacts have changed. Notifying listeners.");
                this.listener.onContactsChanged(ContactsDataProvider.ContactsType.PERSONAL);
            }
            this.running.set(false);
        }
    }

    private boolean haveContentsChanged() {
        if (!PermissionUtils.checkAllPermissionsGranted(this.context, "android.permission.READ_CONTACTS")) {
            Logger.info(TAG, "No permission to read contacts. Marking contacts as not changed.");
            return false;
        }
        long jUptimeMillis = SystemClock.uptimeMillis();
        int iM4586e = getContactCount();
        if (iM4586e > 10000) {
            return false;
        }
        if (iM4586e != getSavedContactCount()) {
            Logger.debug(TAG, "haveContentsChanged() : Count changed from " + getSavedContactCount() + " to " + iM4586e);
            return true;
        }
        if (getContactNames(ContactsContract.Contacts.CONTENT_URI).hashCode() != getSavedNamesHashCode()) {
            return true;
        }
        Logger.debug(TAG, "haveContentsChanged() : No change detected in " + (SystemClock.uptimeMillis() - jUptimeMillis) + " ms)");
        return false;
    }

    ArrayList<String> getContactNames(Uri uri) {
        ArrayList<String> arrayListM4580a;
        try {
            Cursor cursorQuery = this.context.getContentResolver().query(uri, ContactsQueryUtils.PROJECTION, null, null, null);
            if (cursorQuery != null) {
                try {
                    arrayListM4580a = ContactsQueryUtils.getValidNames(cursorQuery);
                } finally {
                    cursorQuery.close();
                }
            } else {
                arrayListM4580a = null;
            }
            return arrayListM4580a == null ? new ArrayList<>() : arrayListM4580a;
        } catch (SQLiteException | SecurityException | IllegalArgumentException e) {
            // SecurityException: READ_CONTACTS revoked between the guard and the query.
            // IllegalArgumentException: the provider does not expose one of our columns.
            if (BuildConfig.DEBUG) Log.e(TAG, "Error fetching valid names from contacts.", e);
            return new ArrayList<>();
        }
    }

    public void unregisterObserver() {
        this.debounceHandler.removeCallbacks(this.checkAfterQuiet);
        if (this.contentObserver == null) {
            return;
        }
        this.context.getContentResolver().unregisterContentObserver(this.contentObserver);
    }

    void saveContactsState(ArrayList<String> arrayList) {
        this.savedContactCount = getContactCount();
        this.savedNamesHashCode = arrayList.hashCode();
    }

    int getSavedContactCount() {
        return this.savedContactCount;
    }

    int getSavedNamesHashCode() {
        return this.savedNamesHashCode;
    }

    private int getContactCount() {
        try {
            return ContactsQueryUtils.getCountAndClose(this.context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, ContactsQueryUtils.PROJECTION_ID_ONLY, null, null, null));
        } catch (SQLiteException e) {
            Logger.errorWithException(TAG, e, "SQLiteException in the remote Contacts process.");
            return 0;
        }
    }
}
