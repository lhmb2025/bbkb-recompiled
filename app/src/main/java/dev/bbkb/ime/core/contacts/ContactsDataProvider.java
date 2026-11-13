package dev.bbkb.ime.core.contacts;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;



public class ContactsDataProvider {

    private final Context context;

    private final ContactsChangeObserver changeObserver;


    private Listener listener = null;

    
    interface Listener {
        /** @param contactsType the contact set that changed */
        void onContactsChanged(ContactsType contactsType);
    }

    /** WORK is never returned by getContactNames(); DynamicLearningManager still switches on it. */
    public enum ContactsType {
        PERSONAL,
        WORK,
        ALL
    }

    public ContactsDataProvider(Context context) {
        this.context = context;
        this.changeObserver = new ContactsChangeObserver(context);
    }

    /** @param contactsType which contact set to read; only PERSONAL and ALL return names */
    ArrayList<String> getContactNames(ContactsType contactsType, Uri uri) {
        ArrayList<String> arrayList = new ArrayList<>();
        synchronized (this) {
            if (contactsType == ContactsType.PERSONAL || contactsType == ContactsType.ALL) {
                arrayList.addAll(this.changeObserver.getContactNames(uri));
            }
        }
        return arrayList;
    }

    /** Set by {@link #release}: no observer is registered again. */
    private boolean released;

    /** @param listener listener notified when contacts change */
    void registerListener(Listener listener) {
        synchronized (this) {
            if (this.released) {
                return;
            }
            this.listener = listener;
            this.changeObserver.registerObserver(listener);
        }
    }

    /** @param contactsType contact set the names belong to; only PERSONAL is saved */
    void saveContactsSnapshot(ContactsType contactsType, ArrayList<String> arrayList) {
        if (arrayList.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (contactsType == ContactsType.PERSONAL) {
                this.changeObserver.saveContactsState(arrayList);
            }
        }
    }

    void unregister() {
        synchronized (this) {
            if (this.changeObserver != null) {
                this.changeObserver.unregisterObserver();
            }
        }
    }

    /**
     * Unregisters for good and refuses any registration queued behind this call; {@link #unregister}
     * alone could be followed by a sync task re-registering on a service that no longer exists.
     */
    void release() {
        synchronized (this) {
            this.released = true;
            if (this.changeObserver != null) {
                this.changeObserver.unregisterObserver();
            }
        }
    }
}
