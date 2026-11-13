package dev.bbkb.ime.core.contacts;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;



public class ContactsDataProvider {

    private final Context context;

    private final ContactsChangeObserver changeObserver;


    private Listener listener = null;

    
    interface Listener {
        /** @param bVar the contact set that changed */
        void onContactsChanged(ContactsType bVar);
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

    /** @param bVar which contact set to read; only PERSONAL and ALL return names */
    ArrayList<String> getContactNames(ContactsType bVar, Uri uri) {
        ArrayList<String> arrayList = new ArrayList<>();
        synchronized (this) {
            if (bVar == ContactsType.PERSONAL || bVar == ContactsType.ALL) {
                arrayList.addAll(this.changeObserver.getContactNames(uri));
            }
        }
        return arrayList;
    }

    /** Set by {@link #release}: no observer is registered again. */
    private boolean released;

    /** @param aVar listener notified when contacts change */
    void registerListener(Listener aVar) {
        synchronized (this) {
            if (this.released) {
                return;
            }
            this.listener = aVar;
            this.changeObserver.registerObserver(aVar);
        }
    }

    /** @param bVar contact set the names belong to; only PERSONAL is saved */
    void saveContactsSnapshot(ContactsType bVar, ArrayList<String> arrayList) {
        if (arrayList.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (bVar == ContactsType.PERSONAL) {
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
