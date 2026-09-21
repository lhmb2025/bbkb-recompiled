package dev.bbkb.ime.core.contacts;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;



public class ContactsDataProvider {

    private final Context context;

    private final ContactsChangeObserver changeObserver;


    private Listener listener = null;

    
    interface Listener {
        void onContactsChanged(ContactsType bVar);
    }

    
    /**
     * WORK is never produced any more: getContactNames() only ever returns data for
     * PERSONAL/ALL. It survives because DynamicLearningManager.addContactsWordsInternal still
     * switches on it.
     */
    public enum ContactsType {
        PERSONAL,
        WORK,
        ALL
    }

    public ContactsDataProvider(Context context) {
        this.context = context;
        this.changeObserver = new ContactsChangeObserver(context);
    }


    ArrayList<String> getContactNames(ContactsType bVar, Uri uri) {
        ArrayList<String> arrayList = new ArrayList<>();
        synchronized (this) {
            // Only return personal contacts (work contacts removed)
            if (bVar == ContactsType.PERSONAL || bVar == ContactsType.ALL) {
                arrayList.addAll(this.changeObserver.getContactNames(uri));
            }
        }
        return arrayList;
    }

    void registerListener(Listener aVar) {
        synchronized (this) {
            this.listener = aVar;
            this.changeObserver.registerObserver(aVar);
        }
    }

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
}
