package dev.bbkb.ime.core.contacts;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.util.Patterns;

import androidx.core.content.ContextCompat;

import dev.bbkb.ime.core.settings.PrefsManager;

import java.util.ArrayList;
import java.util.List;
import dev.bbkb.ime.BuildConfig;



public final class AccountUtils {
    private static final String TAG = "AccountUtils";

    private AccountUtils() {
        // Private constructor - utility class
    }

    @SuppressLint("MissingPermission") // Permission checked at runtime with ContextCompat
    private static Account[] getAccounts(Context context) {
        // Check for GET_ACCOUNTS permission at runtime
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) 
                != PackageManager.PERMISSION_GRANTED) {
            return new Account[0];
        }
        
        try {
            return AccountManager.get(context).getAccounts();
        } catch (SecurityException e) {
            // Fallback if permission check passes but SecurityException still thrown
            if (BuildConfig.DEBUG) Log.w(TAG, "SecurityException getting accounts despite permission check", e);
            return new Account[0];
        }
    }

    public static List<String> getDeviceAccountsEmailAddresses(Context context) {
        ArrayList arrayList = new ArrayList();
        
        // Check if email learning is enabled in preferences
        android.content.SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs(context);
        if (!prefs.getBoolean("pref_key_use_accounts_dict", true)) {
            return arrayList;
        }
        
        // Will return empty array if permission not granted
        Account[] accounts = getAccounts(context);
        if (accounts.length == 0) {
            return arrayList;
        }
        
        for (Account account : accounts) {
            String str = account.name;
            if (Patterns.EMAIL_ADDRESS.matcher(str).matches()) {
                arrayList.add(str);
                arrayList.add(str.split("@")[0]);
            }
        }
        
        return arrayList;
    }
}
