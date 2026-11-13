package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.util.Log;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Locale;
import dev.bbkb.ime.BuildConfig;



public final class DictionaryFactory {

    private static final String TAG = "DictionaryFactory";

    private DictionaryFactory() {
    }

    /** @param useSecondaryEngine true for the spell checker's (secondary) engine */
    public static Dictionary createDictionary(Context context, Locale locale, boolean useSecondaryEngine) {
        if (BuildConfig.DEBUG) Log.i(TAG, "=== Dictionary Factory ===");
        if (BuildConfig.DEBUG) Log.i(TAG, "Requested locale: " + locale);
        if (BuildConfig.DEBUG) Log.i(TAG, "Secondary SDK: " + useSecondaryEngine);
        
        NuanceSDKDictionaryBridge dictionary = null;
        if (locale != null) {
            try {
                if (BuildConfig.DEBUG) Log.i(TAG, "Getting " + (useSecondaryEngine ? "secondary" : "primary") + " NuanceSDK instance...");
                NuanceSDK engine = useSecondaryEngine ? NuanceSDKManager.getSecondary() : NuanceSDKManager.getInstance();
                // A failed load arrives as a null engine (NuanceSDKManager swallows the error); a
                // bridge over null would NPE, so use the fallback.
                if (engine != null) {
                    dictionary = new NuanceSDKDictionaryBridge(engine, context, locale, !useSecondaryEngine);
                    if (BuildConfig.DEBUG) Log.i(TAG, "NuanceSDK dictionary created successfully: " + dictionary);
                } else if (BuildConfig.DEBUG) {
                    Log.e(TAG, "NuanceSDK engine unavailable; using FallbackDictionary");
                }
            } catch (IllegalStateException unused) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Swiftkey license expired.");
            }
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "No locale defined for dictionary");
        }
        
        Dictionary result = dictionary == null ? new FallbackDictionary(context, locale) : dictionary;
        if (BuildConfig.DEBUG) Log.i(TAG, "Final dictionary type: " + result.getClass().getSimpleName());
        return result;
    }

    public static Dictionary createDictionary(Context context, Locale locale) {
        return createDictionary(context, locale, false);
    }
}
