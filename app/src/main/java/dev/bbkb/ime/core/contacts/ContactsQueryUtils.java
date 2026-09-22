package dev.bbkb.ime.core.contacts;

import android.content.Context;
import android.database.Cursor;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;

import java.util.ArrayList;



public final class ContactsQueryUtils {

    // getValidNames only reads the display name. TIMES_CONTACTED/LAST_TIME_CONTACTED are
    // deprecated as of API 30 (they return bucketed constants) and IN_VISIBLE_GROUP was never
    // read; three unused columns is real IPC payload on a large address book.
    static final String[] PROJECTION = {ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY};

    static final String[] PROJECTION_ID_ONLY = {ContactsContract.Contacts._ID};

    static boolean isContactsPromptEligibleField(EditorInfo editorInfo) {
        String str;
        if (editorInfo == null || (str = editorInfo.packageName) == null) {
            return false;
        }
        int i = editorInfo.inputType;
        if (str.equals("com.blackberry.hub") || str.equals("com.google.android.apps.messaging") || str.equals("com.google.android.gm")) {
            return InputTypeUtils.isEmailVariation(i & 4080);
        }
        return false;
    }

    public static boolean isContactsPermissionGranted(Context context) {
        return PrefsManager.INSTANCE.getPrefs(context).getBoolean("pref_spellcheck_use_contacts", true);
    }

    /**
     * Rejects empty names, anything containing '@' (an address, not a name) and single-token
     * hyphenated strings such as "foo-bar", which are far more often identifiers than names.
     * A multi-word name may contain hyphens ("Anne-Marie Smith") and is kept.
     */
    static boolean isValidName(String str) {
        if (TextUtils.isEmpty(str) || str.indexOf('@') != -1) {
            return false;
        }
        final boolean isMultiWord = str.indexOf(' ') != -1;
        final boolean hasHyphen = str.indexOf('-') != -1;
        return isMultiWord || !hasHyphen;
    }

    static ArrayList<String> getValidNames(Cursor cursor) {
        ArrayList<String> arrayList = new ArrayList<>();
        if (cursor != null && cursor.moveToFirst()) {
            final int displayNameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY);
            while (!cursor.isAfterLast()) {
                String string = cursor.getString(displayNameColumn);
                if (isValidName(string)) {
                    arrayList.add(string);
                }
                cursor.moveToNext();
            }
        }
        return arrayList;
    }

    /** SQLiteException propagates; it is handled by the caller (ContactsChangeObserver). */
    static int getCountAndClose(Cursor cursor) {
        try {
            if (cursor == null) {
                return 0;
            }
            return cursor.getCount();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
