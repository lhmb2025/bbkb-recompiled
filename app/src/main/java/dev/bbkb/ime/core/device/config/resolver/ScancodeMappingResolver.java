package dev.bbkb.ime.core.device.config.resolver;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;

import java.util.List;
import dev.bbkb.ime.BuildConfig;

/**
 * Resolves raw scancode/keycode pairs to {@link ScancodeMapping} entries
 * loaded from device XML configuration.
 *
 * <p>This is the central authority for determining how a physical key should be
 * routed through the pipeline. It replaces the hardcoded {@code identifyKeyType()}
 * switch statement in {@code KeyInterceptorService} with a data-driven lookup.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Cached result for (scanCode, keyCode) pair</li>
 *   <li>Linear scan of device's {@code <scancode-mappings>} list (first match wins)</li>
 *   <li>null if no mapping found (caller falls back to legacy behavior)</li>
 * </ol>
 *
 * <p>Thread safety: all public methods are safe to call from any thread. {@code mMappings} and
 * {@code mInitialized} are volatile, and the cache is published copy-on-write, so the per-key
 * {@link #resolve} path stays lock-free while {@link #initialize} / {@link #reset} can run
 * concurrently. (The javadoc previously made this claim while {@code resolve} mutated a plain
 * SparseArray outside any lock.)
 */
public class ScancodeMappingResolver {

    private static final String TAG = "ScancodeMappingResolver";
    /**
     * Per-resolve tracing. Off by default: this fires on EVERY keystroke, so a debug build with
     * it on floods logcat and makes the log useless for anything else. Flip it to true while
     * investigating a scancode mapping, not as a standing setting (§5.11). The UNIFIED_MISMATCH
     * shadow-run logging is separate and stays on — that is the signal the hardcoded-table
     * retirement is gated on.
     */
    private static final boolean DEBUG = false;

    /** Singleton instance. */
    private static volatile ScancodeMappingResolver sInstance;

    /** The scancode mappings from the active device config. May be empty, never null after init. */
    private volatile List<ScancodeMapping> mMappings;

    /**
     * Cache: compound key (scanCode << 16 | keyCode & 0xFFFF) → ScancodeMapping.
     * Uses SparseArray to avoid boxing. A cached null is represented by {@link #CACHE_MISS}.
     */
    private volatile SparseArray<ScancodeMapping> mCache = new SparseArray<>();

    /** Sentinel object representing a cached "no mapping found" result. */
    private static final ScancodeMapping CACHE_MISS = new ScancodeMapping();

    private volatile boolean mInitialized = false;

    private ScancodeMappingResolver() {
        // Singleton
    }

    /**
     * Get the singleton instance.
     */
    public static ScancodeMappingResolver getInstance() {
        if (sInstance == null) {
            synchronized (ScancodeMappingResolver.class) {
                if (sInstance == null) {
                    sInstance = new ScancodeMappingResolver();
                }
            }
        }
        return sInstance;
    }

    /**
     * Initialize the resolver with the scancode mappings from the resolved device mapping.
     * Called once when the device mapping is resolved (typically during first key event).
     *
     * @param mapping The resolved DeviceInputMapping for the current device, or null
     */
    public synchronized void initialize(DeviceInputMapping mapping) {
        if (mapping != null && mapping.scancodeMappings != null && !mapping.scancodeMappings.isEmpty()) {
            mMappings = mapping.scancodeMappings;
            if (DEBUG) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Initialized with " + mMappings.size() + " scancode mappings from device: " + mapping.deviceName);
                for (ScancodeMapping m : mMappings) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "  " + m.toString());
                }
            }
        } else {
            mMappings = null;
            if (DEBUG) {
                if (BuildConfig.DEBUG) {
                Log.d(TAG, "Initialized with no scancode mappings" +
                        (mapping != null ? " (device: " + mapping.deviceName + ")" : " (no device mapping)"));
                }
            }
        }
        mCache = new SparseArray<>();
        mInitialized = true;
    }

    /**
     * Resolve a scancode/keycode pair to a {@link ScancodeMapping}.
     *
     * @param scanCode Raw hardware scancode from KeyEvent
     * @param keyCode  Android keycode from KeyEvent
     * @return The matching ScancodeMapping, or null if no mapping is configured
     */
    public ScancodeMapping resolve(int scanCode, int keyCode) {
        final List<ScancodeMapping> mappings = mMappings;
        if (!mInitialized || mappings == null) {
            return null;
        }

        // Check cache
        int cacheKey = makeCacheKey(scanCode, keyCode);
        ScancodeMapping cached = mCache.get(cacheKey);
        if (cached != null) {
            return cached == CACHE_MISS ? null : cached;
        }

        // Linear scan — list is small (typically <10 entries)
        for (ScancodeMapping mapping : mappings) {
            if (mapping.matches(scanCode, keyCode)) {
                cache(cacheKey, mapping);
                if (DEBUG) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "resolve(sc=" + scanCode + ", kc=" + keyCode + ") → " + mapping);
                }
                return mapping;
            }
        }

        // Cache the miss
        cache(cacheKey, CACHE_MISS);
        if (DEBUG) {
            if (BuildConfig.DEBUG) Log.d(TAG, "resolve(sc=" + scanCode + ", kc=" + keyCode + ") → no mapping");
        }
        return null;
    }

    /**
     * Check if the resolver has been initialized (even if no mappings are present).
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    /**
     * Reset the resolver (for testing or config reload).
     */
    public synchronized void reset() {
        mMappings = null;
        mCache = new SparseArray<>();
        mInitialized = false;
        if (DEBUG) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Reset");
        }
    }

    /**
     * Publish a cache entry copy-on-write. Writes happen at most once per distinct
     * (scanCode, keyCode) pair on a device -- a handful of entries -- so copying is cheap, and
     * it keeps {@link #resolve}'s read path lock-free.
     */
    private synchronized void cache(int cacheKey, ScancodeMapping mapping) {
        final SparseArray<ScancodeMapping> next = mCache.clone();
        next.put(cacheKey, mapping);
        mCache = next;
    }

    /**
     * Create a compound cache key from scanCode and keyCode.
     * Uses upper 16 bits for scanCode and lower 16 bits for keyCode.
     */
    private static int makeCacheKey(int scanCode, int keyCode) {
        return (scanCode << 16) | (keyCode & 0xFFFF);
    }
}
