package dev.bbkb.ime.core.device.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.util.SparseBooleanArray;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.parser.AltMappingsParser;
import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.DeviceQuirk;
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceInfo;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;
import dev.bbkb.ime.core.device.touch.SyntheticTouchSources;
import dev.bbkb.ime.core.device.touch.TouchKeypadGeometry;
import dev.bbkb.ime.core.device.touch.TouchSourceSelector;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.InputPathDebug;
import dev.bbkb.ime.core.shared.StartupTiming;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device profile queries: immutable capabilities plus mutable runtime state. */
public final class DeviceProfile {

    private static volatile DeviceProfile current;

    /**
     * Application context captured by {@link #initialize(Context)}, for callers with no Context
     * (KeyEventDeviceClassifier on the per-key path). Null until initialized.
     */
    private static volatile Context sAppContext;

    public static final String PREF_DEBUG_FORCE_VKB_MODE = "debug_force_vkb_mode";

    /**
     * Open while a {@link #startInitialize} load is in flight. Every static reader of
     * {@link #current} waits on it so none sees the auto-detect fallback (a VKB-shaped profile).
     */
    private static volatile CountDownLatch sInitLatch;

    /** The loader thread; the wait is a no-op on it or the load would join itself. */
    private static volatile Thread sInitThread;

    /** The load takes ~250 ms; past this, the auto-detect fallback beats wedging the main thread. */
    private static final long INIT_WAIT_TIMEOUT_MS = 5000L;

    private static final String TAG = "DeviceProfile";

    /** Not final: {@link #onScannedTouchKeypadChanged} swaps in a copy with the new pad. */
    private volatile DeviceCapabilities capabilities;
    private final DeviceRuntimeState runtimeState;
    private DeviceInputMapping deviceMapping;

    /**
     * {@link #getTouchKeypadGeometry()}'s answer with the measured pad it was resolved against;
     * stale once {@link SyntheticTouchSources#measuredPad()} is a different object.
     */
    private volatile GeometryCache touchKeypadGeometry;

    /** {@link #getTouchSourceSelection()}'s answer, dropped with the geometry. */
    private volatile TouchSourceSelector.Selection touchSourceSelection;

    /** Per device id: is it the pad the config names? Cleared when devices change (ids are reused). */
    private final SparseBooleanArray namedPadDevices = new SparseBooleanArray(2);

    /** Registered once, the first time a profile is initialised with a Context. */
    private static boolean sTouchKeypadListenersRegistered;

    private int displayOrientation = Configuration.ORIENTATION_PORTRAIT;
    private boolean isHardwareKeyboardHidden = false;

    private DeviceProfile(DeviceCapabilities caps, DeviceRuntimeState state) {
        this.capabilities = caps;
        this.runtimeState = state;
    }

    public static DeviceProfile initialize(Context context, Configuration configuration) {
        awaitInitialized();
        initialize(context);
        if (configuration != null) {
            current.updateConfiguration(configuration);
        }
        return current;
    }

    public static DeviceProfile create(Context context, Configuration configuration) {
        awaitInitialized();
        if (current == null) {
            initialize(context, configuration);
        } else if (configuration != null) {
            current.updateConfiguration(configuration);
        }
        return current;
    }

    public static DeviceProfile fromSettings(Context context, SettingsValues settings) {
        awaitInitialized();
        if (current == null) {
            initialize(context);
        }
        if (settings != null) {
            current.displayOrientation = settings.displayOrientation;
            // Same rule as updateConfiguration(), so every writer agrees on the swipe gate.
            if (context != null) {
                current.isHardwareKeyboardHidden =
                        isHardKeyboardHidden(context.getResources().getConfiguration());
            }
        }
        return current;
    }

    /**
     * The one rule for {@link #isHardwareKeyboardHidden()} (also the swipe gate). "No keyboard" is
     * not folded in: {@link #isHardwareKeyboardActive()} ANDs {@link #hasPhysicalKeyboard()}.
     */
    static boolean isHardKeyboardHidden(Configuration config) {
        return config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES;
    }

    public static void initialize(Context context) {
        initializeForDevice(context, DeviceInputResolver.ALL_DEVICES);
    }

    /**
     * Starts {@link #initialize(Context, Configuration)} on a background thread and returns at
     * once. Every static reader joins the load first; the first join is
     * {@code SettingsManager.loadSettings -> fromSettings} inside {@code onCreate}, which is the
     * happens-before edge later main-thread readers inherit. {@link #appContext()} also waits, so
     * {@code KeyEventProcessor} cannot see null mid-load and start a second init.
     * {@code Xt9KdbVariant.apply} and {@code setEngineYWarp} run inside the load. A second call
     * while a load is in flight is a no-op.
     */
    public static void startInitialize(final Context context, final Configuration configuration) {
        if (sInitLatch != null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        sInitLatch = latch;
        final Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                sInitThread = Thread.currentThread();
                final long token = StartupTiming.begin();
                try {
                    initialize(context, configuration);
                } catch (Throwable t) {
                    Logger.error(TAG, "background DeviceProfile init failed: " + t);
                } finally {
                    StartupTiming.end("deviceProfile.backgroundInitialize", token);
                    sInitThread = null;
                    sInitLatch = null;
                    latch.countDown();
                }
            }
        }, "DeviceProfileInit");
        loader.setDaemon(true);
        try {
            loader.start();
        } catch (Throwable t) {
            // Could not spawn: initialize synchronously.
            sInitLatch = null;
            latch.countDown();
            initialize(context, configuration);
        }
    }

    /**
     * Blocks until a {@link #startInitialize} load has published its profile. A no-op when no
     * load is in flight, and on the loader thread itself.
     */
    private static void awaitInitialized() {
        final CountDownLatch latch = sInitLatch;
        if (latch == null || Thread.currentThread() == sInitThread) {
            return;
        }
        final long token = StartupTiming.begin();
        try {
            if (!latch.await(INIT_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Logger.warn(TAG, "timed out waiting for the background DeviceProfile init");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        StartupTiming.endIfOver("deviceProfile.awaitInitialized", token, 1L);
    }

    /** Test seam: drops any in-flight-load bookkeeping so one test cannot strand the next. */
    @VisibleForTesting
    public static void clearPendingInitForTest() {
        final CountDownLatch latch = sInitLatch;
        sInitLatch = null;
        sInitThread = null;
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * As {@link #initialize(Context)}, but resolving the mapping against one input-device id; with
     * two physical keyboards attached the id picks which one the overrides come from.
     */
    public static void initializeForDevice(Context context, int deviceId) {
        awaitInitialized();
        final long initToken = StartupTiming.begin();
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
        // Resolved before the capability probe: the config's <keypad-layout> is the top-ranked
        // source for KeypadLayoutDetector inside DeviceCapabilities.detect(). Cached per device id.
        DeviceInputMapping mapping = context != null
                ? DeviceInputResolver.resolveInputMapping(context, deviceId)
                : null;
        // This is the phone's profile whichever keyboard asked: an unmatched device (side keys,
        // an external keyboard) leaves the phone's in place.
        if (mapping == null && context != null && deviceId != DeviceInputResolver.ALL_DEVICES) {
            mapping = DeviceInputResolver.resolveInputMapping(context, DeviceInputResolver.ALL_DEVICES);
        }

        // The user's Keypad layout choice, when the config offers one, outranks every detection source.
        String userKeypadLayout = null;
        if (context != null && mapping != null && !mapping.keypadLayoutOptions.isEmpty()) {
            String chosen = dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                    .getString(PREF_KEYPAD_LAYOUT, "");
            if (chosen != null && mapping.keypadLayoutOptions.contains(chosen)) userKeypadLayout = chosen;
        }
        DeviceCapabilities caps = DeviceCapabilities.detect(mapping, userKeypadLayout);
        DeviceRuntimeState state = DeviceRuntimeState.getInstance();
        DeviceProfile profile = new DeviceProfile(caps, state);
        profile.deviceMapping = mapping;

        if (context != null) {
            SharedPreferences prefs =
                    dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context);
            // Release builds do not show the Debug screen, so they drop the pref on every start
            // or a user who set it earlier would be stuck in on-screen mode.
            if (!dev.bbkb.ime.BuildConfig.DEBUG && prefs.contains(PREF_DEBUG_FORCE_VKB_MODE)) {
                prefs.edit().remove(PREF_DEBUG_FORCE_VKB_MODE).apply();
            }
            // Set unconditionally so a pref flipped off also clears it.
            state.setDebugForceVkbMode(prefs.getBoolean(PREF_DEBUG_FORCE_VKB_MODE, false));
        }

        // Applied here so start-up and config switches both run it, before the next layout sync.
        if (context != null) {
            // Device KDB variant: route layout loads through assets/kdb/<variant>/ (root fallback).
            com.blackberry.nuanceshim.Xt9KdbVariant.apply(context,
                    profile.deviceMapping != null ? profile.deviceMapping.kdbVariant : null);
            // Piecewise sensor->engine Y warp for the CKB gesture feed.
            dev.bbkb.ime.keyboard.internal.GestureEventProcessor.setEngineYWarp(
                    profile.deviceMapping != null ? profile.deviceMapping.ckbYWarp : null);
            // The stroke analyser's frame (144 / 610 on the KEY2's pad).
            dev.bbkb.ime.keyboard.internal.GestureEventProcessor.setKeypadGeometry(
                    profile.getTouchKeypadGeometry());
            // The BBKB helper is switched off (hidden from Accessibility) where the profile says
            // it is never needed.
            dev.bbkb.ime.core.device.interceptor.KeyInterceptorComponent.apply(context,
                    profile.deviceMapping);
            // A pad that appears or vanishes later (Titan 2 Scroll assistant toggle) is picked up
            // without a restart. Touch keypad only: no key config reset.
            KeyboardDeviceScanner.getInstance().registerDeviceListener(context);
            registerTouchKeypadListeners();
        }

        Logger.info("CKB_DEBUG", "DeviceProfile.initialize:"
                + " deviceId=" + (deviceId == DeviceInputResolver.ALL_DEVICES ? "all" : deviceId)
                + ", caps=" + caps
                + ", mapping=" + (profile.deviceMapping == null ? "none" : (profile.deviceMapping.deviceName != null ? profile.deviceMapping.deviceName : "<unnamed>"))
                + ", forceTouchKeypad=" + (profile.deviceMapping != null && profile.deviceMapping.forceTouchKeypad)
                + ", hasTouchKeypad=" + profile.hasTouchKeypad()
                + ", touchKeypadDeviceId=" + profile.getTouchKeypadDeviceId()
                + ", touchKeypadGeometry=" + profile.getTouchKeypadGeometry()
                + ", touchSource=" + profile.getTouchSourceSelection()
                + ", primaryKeyboard=" + caps.getPrimaryKeyboard());

        current = profile;
        StartupTiming.end("deviceProfile.initializeForDevice", initToken);
    }

    private static synchronized void registerTouchKeypadListeners() {
        if (sTouchKeypadListenersRegistered) return;
        sTouchKeypadListenersRegistered = true;
        KeyboardDeviceScanner.getInstance().addTouchKeypadListener(DeviceProfile::onScannedTouchKeypadChanged);
        SyntheticTouchSources.addListener(DeviceProfile::onSyntheticTouchSourcesChanged);
    }

    /** A synthetic source (Shizuku) started or stopped; the stroke analyser's frame copy is re-pushed. */
    private static void onSyntheticTouchSourcesChanged() {
        final DeviceProfile profile = current;
        if (profile == null) return;
        dev.bbkb.ime.keyboard.internal.GestureEventProcessor.setKeypadGeometry(
                profile.getTouchKeypadGeometry());
        Logger.info("CKB_DEBUG", "DeviceProfile: synthetic touch sources changed"
                + ", hasTouchKeypad=" + profile.hasTouchKeypad()
                + ", geometry=" + profile.getTouchKeypadGeometry());
    }

    /** A live re-scan found a different touch keypad; only the pad changes in the profile. */
    @VisibleForTesting
    static void onScannedTouchKeypadChanged(@Nullable TouchKeypadInfo touchKeypad) {
        final DeviceProfile profile = current;
        if (profile == null) return;
        profile.capabilities = profile.capabilities.withTouchKeypad(touchKeypad);
        profile.invalidateTouchKeypadCaches();
        dev.bbkb.ime.keyboard.internal.GestureEventProcessor.setKeypadGeometry(
                profile.getTouchKeypadGeometry());
        Logger.info("CKB_DEBUG", "DeviceProfile: touch keypad now " + touchKeypad
                + ", hasTouchKeypad=" + profile.hasTouchKeypad()
                + ", geometry=" + profile.getTouchKeypadGeometry());
    }

    private void invalidateTouchKeypadCaches() {
        touchKeypadGeometry = null;
        touchSourceSelection = null;
        synchronized (namedPadDevices) {
            namedPadDevices.clear();
        }
    }

    /**
     * Rebuilds the profile after {@code KeypadLayoutDetector.observeKeyEvent} proved a different
     * keypad layout. Unlike a config switch it does not reset the resolver or classifier cache:
     * this runs inside the hardware key path, where dropping them mid-keystroke is a hazard.
     */
    public static void reinitializeForKeypadLayoutChange(Context context, int deviceId) {
        Logger.info(TAG, "keypad layout changed by observation; rebuilding device profile");
        initializeForDevice(context, deviceId);
    }

    /** The application context captured at {@link #initialize(Context)}; null before then. */
    @Nullable
    public static Context appContext() {
        awaitInitialized();
        return sAppContext;
    }

    public static DeviceProfile current() {
        awaitInitialized();
        if (current == null) {
            // Lazy-safe fallback: auto-detect if not explicitly initialized.
            DeviceCapabilities caps = DeviceCapabilities.detect();
            DeviceRuntimeState state = DeviceRuntimeState.getInstance();
            current = new DeviceProfile(caps, state);
        }
        return current;
    }

    /**
     * Test seam: installs a profile over the given capabilities, bypassing the scan (on a JVM
     * detection always reports VKB). Shares the process-wide {@link DeviceRuntimeState}, so a
     * test that sets a force-VKB lever must clear it. {@link #initialize(Context)} discards it.
     */
    @VisibleForTesting
    public static void installForTest(DeviceCapabilities capabilities) {
        clearPendingInitForTest();
        current = new DeviceProfile(capabilities, DeviceRuntimeState.getInstance());
    }

    public void updateConfiguration(Configuration config) {
        if (config != null) {
            this.displayOrientation = config.orientation;
            this.isHardwareKeyboardHidden = isHardKeyboardHidden(config);
        }
    }

    public enum DeviceType {
        PKB, VKB, UNKNOWN
    }

    /**
     * Whether this is a keypad phone: the scan found an alphabetic keyboard, or the profile
     * declares one ({@code <device-type>} PKB or CKB), since some ROMs misreport the keypad.
     */
    public boolean isPkbDevice() {
        if (runtimeState.isForceVkbMode()) return false;
        if (isForcedPkbDevice()) return true;
        DeviceCapabilities.DetectedDeviceType type = capabilities.getDeviceType();
        return type == DeviceCapabilities.DetectedDeviceType.PKB || type == DeviceCapabilities.DetectedDeviceType.HYBRID;
    }

    public boolean isVkbDevice() {
        return !isPkbDevice();
    }

    public DeviceType getDeviceType() {
        return isPkbDevice() ? DeviceType.PKB : DeviceType.VKB;
    }

    /** The scan found a physical keyboard, or the phone's profile declares the phone has one. */
    public boolean hasPhysicalKeyboard() {
        return capabilities.hasPhysicalKeyboard() || isForcedPkbDevice();
    }

    /**
     * Whether touch-keypad events can reach the IME (forced CKB, scanned pad, native source, or a
     * synthetic source). Contrast {@link #declaresTouchKeypad()}.
     */
    public boolean hasTouchKeypad() {
        if (deviceMapping != null && deviceMapping.forceTouchKeypad) return true;
        if (capabilities.hasTouchKeypad()) return true;
        if (declaresTouchKeypad()
                && getTouchSourceSelection().choice == TouchSourceSelector.Choice.NATIVE) {
            return true;
        }
        return !SyntheticTouchSources.isEmpty();
    }

    /**
     * Whether the profile declares {@code <touch-keypad>}, reachable or not. Settings key on this;
     * behaviour keys on {@link #hasTouchKeypad()}.
     */
    public boolean declaresTouchKeypad() {
        return deviceMapping != null && deviceMapping.touchKeypad != null;
    }

    /** The profile's {@code <touch-keypad>} block, or null. */
    @Nullable
    public TouchKeypadConfig getTouchKeypadConfig() {
        return deviceMapping != null ? deviceMapping.touchKeypad : null;
    }

    /** Which keypad touch source should run here, and why; see {@link TouchSourceSelector}. */
    public TouchSourceSelector.Selection getTouchSourceSelection() {
        TouchSourceSelector.Selection s = touchSourceSelection;
        if (s == null) {
            s = TouchSourceSelector.select(getTouchKeypadConfig(), Build.VERSION.SDK_INT,
                    capabilities.hasTouchKeypad());
            touchSourceSelection = s;
        }
        return s;
    }

    /** The touch keypad's coordinate frame; precedence in {@link TouchKeypadGeometry}. */
    public TouchKeypadGeometry getTouchKeypadGeometry() {
        final TouchKeypadInfo measured = SyntheticTouchSources.measuredPad();
        GeometryCache cache = touchKeypadGeometry;
        if (cache == null || cache.measured != measured) {
            cache = new GeometryCache(measured, TouchKeypadGeometry.resolve(measured,
                    capabilities.getTouchKeypad(), getTouchKeypadConfig(),
                    deviceMapping != null ? deviceMapping.ckbYWarp : null,
                    deviceMapping != null && deviceMapping.forceTouchKeypad));
            touchKeypadGeometry = cache;
        }
        return cache.geometry;
    }

    /** One resolved frame and the measured pad behind it, swapped as a unit. */
    private static final class GeometryCache {
        @Nullable final TouchKeypadInfo measured;
        final TouchKeypadGeometry geometry;

        GeometryCache(@Nullable TouchKeypadInfo measured, TouchKeypadGeometry geometry) {
            this.measured = measured;
            this.geometry = geometry;
        }
    }

    /**
     * Whether keypad swipe typing has engine geometry. The root KDB is authored for the KEY2's
     * pad, so a profile declaring {@code <touch-keypad>} also needs its own {@code <kdb-variant>}.
     */
    public boolean isTouchKeypadSwipeTypingSupported() {
        return deviceMapping == null || deviceMapping.touchKeypad == null
                || deviceMapping.kdbVariant != null;
    }

    /** Whether the matched profile declares {@code <quirk name="..."/>} for {@code quirk}. */
    public boolean hasQuirk(DeviceQuirk quirk) {
        return deviceMapping != null && deviceMapping.hasQuirk(quirk);
    }

    /** E-ink screen ({@code eink-display} quirk): views should avoid cosmetic repaints. */
    public boolean isEinkDisplay() {
        return hasQuirk(DeviceQuirk.EINK_DISPLAY);
    }

    public int getTouchKeypadDeviceId() {
        if (deviceMapping != null && deviceMapping.forceTouchKeypad) {
            int hwId = capabilities.getTouchKeypadDeviceId();
            if (hwId != -1) return hwId;
            // On a forced-CKB device the capacitive overlay shares the primary keyboard's id.
            KeyboardDeviceInfo primary = capabilities.getPrimaryKeyboard();
            if (primary != null) {
                Logger.info("CKB_DEBUG", "getTouchKeypadDeviceId: forceTouchKeypad, hw=-1, using primaryKeyboard.deviceId=" + primary.getDeviceId());
                return primary.getDeviceId();
            }
        }
        return capabilities.getTouchKeypadDeviceId();
    }

    /** The pad's resolution (0 if unreported); {@code SettingsValues} scales touch thresholds by it. */
    public float getTouchKeypadResolution() {
        return getTouchKeypadGeometry().resolution();
    }

    /**
     * The pad's sensor Y extent, or 0 with only the KEY2 default: the scanned range, else
     * {@code <touch-keypad range-y>}, else (forced CKB, no pad) the last {@code <ckb-y-warp>}
     * sensor breakpoint ("0:0,450:324" -> 450).
     */
    public float getTouchKeypadYMax() {
        return getTouchKeypadGeometry().sensorYMax();
    }

    /**
     * The physical keypad layout ("qwerty", "qwertz" or "azerty"), resolved per
     * {@link #initializeForDevice} by {@code KeypadLayoutDetector}.
     */
    public String getKeypadLayout() {
        return capabilities.getKeypadLayout();
    }

    /** Physical keyboard > Keypad layout: the shape the user chose, {@code ""} for automatic. */
    public static final String PREF_KEYPAD_LAYOUT = "pref_pkb_keypad_layout";

    /** The config's {@code <keypad-layouts>}, offered by the Keypad layout choice; may be empty. */
    public java.util.List<String> getKeypadLayoutOptions() {
        return deviceMapping != null ? deviceMapping.keypadLayoutOptions : java.util.Collections.emptyList();
    }

    /** The keypad shape detection alone gives, named by the "Automatic" row. */
    public String getDetectedKeypadLayout() {
        if (capabilities.getKeypadLayoutSource() != dev.bbkb.ime.core.device.detection.KeypadLayoutDetector.Source.USER) {
            return getKeypadLayout();
        }
        return DeviceCapabilities.detect(deviceMapping).getKeypadLayout();
    }

    public String getKeypadVariant() {
        if (deviceMapping != null && deviceMapping.keypadType != null) {
            return deviceMapping.keypadType;
        }
        return capabilities.getKeypadVariant();
    }

    public String getEffectiveKeypadType() {
        return getKeypadVariant();
    }

    public boolean isBlackBerryDevice() {
        return capabilities.isBlackBerryDevice();
    }

    public boolean isEmulator() {
        return capabilities.isEmulator();
    }

    public boolean supportsSwipeTyping() {
        return !isPkbDevice();
    }

    public boolean supportsSlideboard() {
        return !isPkbDevice();
    }

    public boolean hasAlphabeticKeyboard() {
        return KeyboardDeviceScanner.getInstance().hasExternalKeyboard();
    }

    public int getDisplayOrientation() { return displayOrientation; }
    public boolean isLandscape() { return displayOrientation == Configuration.ORIENTATION_LANDSCAPE; }
    public boolean isPortrait() { return displayOrientation == Configuration.ORIENTATION_PORTRAIT; }
    public boolean isHardwareKeyboardHidden() { return isHardwareKeyboardHidden; }
    public boolean isHardwareKeyboardActive() { return hasPhysicalKeyboard() && !isHardwareKeyboardHidden; }

    public boolean shouldUseFullscreenMode(boolean fullscreenEnabledInSettings) {
        if (isPkbDevice()) return false;
        return fullscreenEnabledInSettings;
    }

    public boolean usesMetaSymHandling() {
        if (!isPkbDevice()) return false;
        return hasCustomAltMappings();
    }

    public boolean hasAltSymKey() {
        return isPkbDevice() && !isBlackBerryDevice();
    }

    /**
     * Whether a MotionEvent comes from the touch keypad: the scanned pad's id, an InputDevice the
     * profile's {@code <touch-keypad>} names, or an id registered in {@link SyntheticTouchSources}.
     */
    public boolean isFromTouchKeypad(MotionEvent motionEvent) {
        if (!hasTouchKeypad() || motionEvent == null) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "isFromTouchKeypad=false: hasTouchKeypad=" + hasTouchKeypad() + " event=" + motionEvent);
            return false;
        }
        int tkpId = getTouchKeypadDeviceId();
        int evtId = motionEvent.getDeviceId();
        boolean result = evtId == tkpId
                || isDeclaredTouchKeypadDevice(evtId)
                || SyntheticTouchSources.contains(evtId);
        if (!result && motionEvent.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "isFromTouchKeypad=false: event.deviceId=" + evtId
                    + " != touchKeypadDeviceId=" + tkpId
                    + " source=0x" + Integer.toHexString(motionEvent.getSource()));
        } else if (result && motionEvent.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "isFromTouchKeypad=true: event.deviceId=" + evtId
                    + " source=0x" + Integer.toHexString(motionEvent.getSource())
                    + " action=" + motionEvent.getActionMasked()
                    + " toolType=" + motionEvent.getToolType(0)
                    + " pointerCount=" + motionEvent.getPointerCount());
        }
        return result;
    }

    /** Whether {@code deviceId} is the pad the profile names; one field read when it names none. */
    private boolean isDeclaredTouchKeypadDevice(int deviceId) {
        final TouchKeypadConfig pad = getTouchKeypadConfig();
        if (pad == null || pad.inputDeviceName == null) return false;
        synchronized (namedPadDevices) {
            final int i = namedPadDevices.indexOfKey(deviceId);
            if (i >= 0) return namedPadDevices.valueAt(i);
        }
        final android.view.InputDevice device = KeyboardDeviceScanner.inputDevice(deviceId);
        final boolean named = device != null && pad.matchesInputDeviceName(device.getName());
        synchronized (namedPadDevices) {
            namedPadDevices.put(deviceId, named);
        }
        return named;
    }

    @Nullable
    public DeviceInputMapping getDeviceMapping() {
        return deviceMapping;
    }

    public boolean hasDeviceMapping() {
        return deviceMapping != null;
    }

    public boolean isForcedPkbDevice() {
        return deviceMapping != null && deviceMapping.forcePkbDevice;
    }

    public boolean isForcedCkbDevice() {
        return deviceMapping != null && deviceMapping.forcePkbDevice && deviceMapping.forceTouchKeypad;
    }

    public boolean hasCustomAltMappings() {
        return deviceMapping != null &&
               deviceMapping.mappingType == DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS;
    }

    @Nullable
    public AltMappingsTable getAltMappingsTable(Context context) {
        return AltMappingsParser.tableFor(context, deviceMapping);
    }

    /** The config's {@code <layout-alt-overrides>} table for {@code layout} ("azerty"...), or null. */
    @Nullable
    public AltMappingsTable getLayoutAltOverridesTable(Context context, String layout) {
        if (deviceMapping == null || layout == null) {
            return null;
        }
        String overrideFile = deviceMapping.layoutAltOverrides.get(layout);
        if (overrideFile == null || overrideFile.isEmpty()) {
            return null;
        }
        return AltMappingsParser.getCachedMappings(context, overrideFile);
    }

    public boolean hasSettingOverride(String settingKey) {
        return deviceMapping != null && deviceMapping.hasSettingOverride(settingKey);
    }

    @Nullable
    public DeviceSettingOverride getSettingOverride(String settingKey) {
        if (deviceMapping == null) return null;
        return deviceMapping.getSettingOverride(settingKey);
    }

    public boolean isSettingReadOnly(String settingKey) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        if (override == null) return false;
        return override.hasForcedValue() || override.readOnly;
    }

    /** Whether a setting is removed from the UI; {@link #isSettingReadOnly} only greys it out. */
    public boolean isSettingHidden(String settingKey) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        return override != null && override.hidden;
    }

    /**
     * The active profile's override for a setting, or null (used by {@code ProfileForcedPrefs}).
     * Unlike {@link #current()} it never waits or builds: prefs are read inside the profile's own
     * init and on every thread. A read racing the first load sees the stored value.
     */
    @Nullable
    public static DeviceSettingOverride peekSettingOverride(String settingKey) {
        final DeviceProfile profile = current;
        return profile == null ? null : profile.getSettingOverride(settingKey);
    }

    public boolean getForcedBooleanValue(String settingKey, boolean defaultValue) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        if (override != null && override.type == DeviceSettingOverride.SettingType.BOOLEAN) {
            Boolean forcedValue = override.getForcedBooleanValue();
            if (forcedValue != null) return forcedValue;
        }
        return defaultValue;
    }

    public int getForcedIntegerValue(String settingKey, int defaultValue) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        if (override != null && override.type == DeviceSettingOverride.SettingType.INTEGER) {
            Integer forcedValue = override.getForcedIntegerValue();
            if (forcedValue != null) return forcedValue;
        }
        return defaultValue;
    }

    /** The profile's {@code default-value} for a string setting, else {@code appDefault}; a stored user choice still wins. */
    public String getDefaultStringValue(String settingKey, String appDefault) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        String deviceDefault = override != null ? override.getDefaultStringValue() : null;
        return deviceDefault != null ? deviceDefault : appDefault;
    }

    public String getForcedStringValue(String settingKey, String defaultValue) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        if (override != null && (override.type == DeviceSettingOverride.SettingType.STRING ||
                                  override.type == DeviceSettingOverride.SettingType.LIST)) {
            String forcedValue = override.getForcedStringValue();
            if (forcedValue != null) return forcedValue;
        }
        return defaultValue;
    }

    @Nullable
    public String getLayoutOverride(String originalLayout) {
        if (deviceMapping == null) return null;
        return deviceMapping.getLayoutOverride(originalLayout);
    }

    void setDeviceMapping(DeviceInputMapping mapping) {
        this.deviceMapping = mapping;
        invalidateTouchKeypadCaches();
    }

    public static void setOnScreenKeyboardShowing(boolean showing) {
        DeviceRuntimeState.getInstance().setOnScreenKeyboardShowing(showing);
    }

    /** Also publishes whether a typing keyboard, not only an input board, is showing. */
    public static void setOnScreenKeyboardShowing(boolean showing, boolean typingKeyboardShowing) {
        DeviceRuntimeState.getInstance().setOnScreenKeyboardShowing(showing, typingKeyboardShowing);
    }

    public static boolean isOnScreenKeyboardVisible() {
        return DeviceRuntimeState.getInstance().isOnScreenKeyboardShowing() || current().isVkbDevice();
    }

    /** {@link #isOnScreenKeyboardVisible()} not counting input boards (clipboard, number pad, voice...). */
    public static boolean isOnScreenTypingKeyboardVisible() {
        return DeviceRuntimeState.getInstance().isOnScreenTypingKeyboardShowing() || current().isVkbDevice();
    }

    public static void setForceVkbMode(boolean force) {
        DeviceRuntimeState.getInstance().setForceVkbMode(force);
    }

    public static boolean isForceVkbMode() {
        DeviceRuntimeState st = DeviceRuntimeState.getInstance();
        return st.isForceVkbMode() || st.isDebugForceVkbMode() || st.isLanguageForceVkbMode();
    }

    /** The current keyboard's language asked for the on-screen keyboard; see {@code OnScreenKeyboardChoice}. */
    public static void setLanguageForceVkbMode(boolean force) {
        DeviceRuntimeState.getInstance().setLanguageForceVkbMode(force);
    }

    /** Debug "Force touchscreen-only mode" toggle; survives hideWindow(), unlike the transient force. */
    public static void setDebugForceVkbMode(boolean force) {
        DeviceRuntimeState.getInstance().setDebugForceVkbMode(force);
    }

    public static boolean isVkbForcedForPackage(String packageName) {
        return DeviceRuntimeState.getInstance().isVkbForcedForPackage(packageName);
    }

    public static void addVkbForcedPackage(String packageName) {
        DeviceRuntimeState.getInstance().addVkbForcedPackage(packageName);
    }

    public static void removeVkbForcedPackage(String packageName) {
        DeviceRuntimeState.getInstance().removeVkbForcedPackage(packageName);
    }

    /** The KeyCharacterMap of device 0, or null without a physical keyboard. */
    @Nullable
    public static KeyCharacterMap getKeyCharacterMap() {
        if (!current().hasPhysicalKeyboard()) return null;
        android.view.InputDevice device = android.view.InputDevice.getDevice(0);
        return device != null ? device.getKeyCharacterMap() : null;
    }

    public static boolean isPhysicalKeyboardAvailable(Context context) {
        boolean hasPkb = current().hasPhysicalKeyboard();
        if (!hasPkb) return false;
        if (context == null) return true;
        return context.getResources().getConfiguration().hardKeyboardHidden
            != Configuration.HARDKEYBOARDHIDDEN_YES;
    }

    public static boolean isPkbOnlyMode(Context context) {
        return isPhysicalKeyboardAvailable(context) && !current().hasAlphabeticKeyboard();
    }

    public static boolean isPkbWithShiftedSymbols(Context context) {
        return isPhysicalKeyboardAvailable(context) && current().isPkbDevice();
    }

    /** Resolves a layout resource id through the matched config's {@code <layout-overrides>}. */
    public static int resolveLayoutResource(android.content.res.Resources resources, int originalResourceId) {
        DeviceProfile profile = current();
        if (profile.deviceMapping == null
                || profile.deviceMapping.layoutOverrides == null
                || profile.deviceMapping.layoutOverrides.isEmpty()) {
            return originalResourceId;
        }
        try {
            String originalName = resources.getResourceEntryName(originalResourceId);
            String overrideName = profile.deviceMapping.layoutOverrides.get(originalName);
            if (overrideName != null) {
                int overrideId = resources.getIdentifier(
                        overrideName, "xml", resources.getResourcePackageName(originalResourceId));
                if (overrideId != 0) {
                    return overrideId;
                }
                Logger.warn("DeviceProfile", "Layout override resource not found: " + overrideName);
            }
        } catch (android.content.res.Resources.NotFoundException ignored) {
            // Unresolvable id: fall through to the original.
        }
        return originalResourceId;
    }

    public boolean isPkbWithoutAlphabeticKeyboard() {
        return isPkbDevice() && !hasAlphabeticKeyboard();
    }

    public boolean hasShiftedSymbolKeyboard() {
        return isPkbDevice() && hasAlphabeticKeyboard();
    }

    public static boolean isMercuryOrVeniceDevice() {
        return ("blackberry".equals(Build.BRAND) &&
                com.blackberry.nuanceshim.NuanceSDK.DEVICE_MERCURY.equals(Build.DEVICE)) ||
                com.blackberry.nuanceshim.NuanceSDK.DEVICE_VENICE.equals(Build.DEVICE);
    }

    public static boolean isPkb() { return current().isPkbDevice(); }
    public static boolean isVkb() { return current().isVkbDevice(); }

    @Override
    public String toString() {
        return "DeviceProfile{" +
                "deviceType=" + getDeviceType() +
                ", hasPhysicalKeyboard=" + hasPhysicalKeyboard() +
                ", hasTouchKeypad=" + hasTouchKeypad() +
                ", supportsSwipeTyping=" + supportsSwipeTyping() +
                ", supportsSlideboard=" + supportsSlideboard() +
                ", keypadLayout='" + getKeypadLayout() + '\'' +
                ", keypadVariant='" + getKeypadVariant() + '\'' +
                ", displayOrientation=" + displayOrientation +
                ", isHardwareKeyboardHidden=" + isHardwareKeyboardHidden +
                ", hasDeviceMapping=" + hasDeviceMapping() +
                '}';
    }
}
