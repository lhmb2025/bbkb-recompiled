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

    public static Dictionary createDictionary(Context context, Locale locale, boolean z) {
        if (BuildConfig.DEBUG) Log.i(TAG, "=== Dictionary Factory ===");
        if (BuildConfig.DEBUG) Log.i(TAG, "Requested locale: " + locale);
        if (BuildConfig.DEBUG) Log.i(TAG, "Secondary SDK: " + z);
        
        NuanceSDKDictionaryBridge dictionary = null;
        if (locale != null) {
            try {
                if (BuildConfig.DEBUG) Log.i(TAG, "Getting " + (z ? "secondary" : "primary") + " NuanceSDK instance...");
                NuanceSDK engine = z ? NuanceSDKManager.getSecondary() : NuanceSDKManager.getInstance();
                // Audit §6 defect 9: NuanceSDKManager swallows the engine's load failure
                // (IllegalStateException, UnsatisfiedLinkError, ...) and returns null, so a failed load
                // arrives here as a null engine, not as the exception below. A bridge over null NPEs as
                // soon as a supported language pack is found; use the fallback instead.
                if (engine != null) {
                    dictionary = new NuanceSDKDictionaryBridge(engine, context, locale, !z);
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

    /**
     * Disposes the secondary (spell checker) engine that {@code createDictionary(..., true)}
     * builds on, freeing every language it holds (P-9: each is a private 5 to 6 MB native copy,
     * and the engine used to live as long as the process). The next secondary dictionary creates
     * a fresh engine. The caller must have closed every dictionary built on it and must keep
     * other threads off it: the spell checker service does both under its dictionary permits.
     */
    public static void releaseSecondaryEngine() {
        if (NuanceSDKManager.peekSecondary() != null) {
            NuanceSDKManager.disposeSecondary();
        }
    }
}
