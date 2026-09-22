package dev.bbkb.ime.core.device.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.parser.AltMappingsParser;
import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceInfo;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.InputPathDebug;
import dev.bbkb.ime.core.shared.StartupTiming;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Main API for device profile queries.
 * Combines immutable capabilities with mutable runtime state.
 * This is the new canonical DeviceProfile in the device package.
 */
public final class DeviceProfile {

    private static volatile DeviceProfile current;

    /**
     * Application context captured by {@link #initialize(Context)}. Device queries that need a
     * Context but are reached from a callback that has none (KeyEventDeviceClassifier, on the
     * per-key path) read it from here instead of passing null and silently getting no mapping.
     * Null until the IME has initialized the profile.
     */
    private static volatile Context sAppContext;

    /** Debug preference key to force VKB mode for testing */
    public static final String PREF_DEBUG_FORCE_VKB_MODE = "debug_force_vkb_mode";

    /**
     * Open while a {@link #startInitialize} load is in flight; null otherwise. Every static entry
     * point that reads {@link #current} waits on it first, so moving the load off the main thread
     * cannot let a consumer observe the {@link #current()} auto-detect fallback (a VKB-shaped
     * profile) in place of the real one.
     */
    private static volatile CountDownLatch sInitLatch;

    /**
     * The thread running that load. The loader itself reaches {@link #initializeForDevice} and
     * everything below it, so the wait has to be a no-op there or the load would join itself.
     */
    private static volatile Thread sInitThread;

    /**
     * Upper bound on the wait. The load is a device scan plus one config XML parse (~250 ms on a
     * KEY2); five seconds means something is badly wrong, and proceeding with the auto-detect
     * fallback beats wedging the IME's main thread forever.
     */
    private static final long INIT_WAIT_TIMEOUT_MS = 5000L;

    private static final String TAG = "DeviceProfile";

    private final DeviceCapabilities capabilities;
    private final DeviceRuntimeState runtimeState;
    private DeviceInputMapping deviceMapping;

    // Display configuration (mutable — updated on config change)
    private int displayOrientation = Configuration.ORIENTATION_PORTRAIT;
    private boolean isHardwareKeyboardHidden = false;

    private DeviceProfile(DeviceCapabilities caps, DeviceRuntimeState state) {
        this.capabilities = caps;
        this.runtimeState = state;
    }

    // ==================== Initialization ====================

    /**
     * Initialize with context and configuration (backward-compatible signature).
     */
    public static DeviceProfile initialize(Context context, Configuration configuration) {
        awaitInitialized();
        initialize(context);
        if (configuration != null) {
            current.updateConfiguration(configuration);
        }
        return current;
    }

    /**
     * Create a DeviceProfile (backward-compatible). Returns current().
     */
    public static DeviceProfile create(Context context, Configuration configuration) {
        awaitInitialized();
        if (current == null) {
            initialize(context, configuration);
        } else if (configuration != null) {
            current.updateConfiguration(configuration);
        }
        return current;
    }

    /**
     * Create from SettingsValues (backward-compatible).
     */
    public static DeviceProfile fromSettings(Context context, SettingsValues settings) {
        awaitInitialized();
        if (current == null) {
            initialize(context);
        }
        if (settings != null) {
            current.displayOrientation = settings.displayOrientation;
            // The hidden flag comes from the live Configuration under the same rule as
            // updateConfiguration(). It used to be derived from settings.hasHardwareKeyboard
            // (keyboard == NOKEYS || hidden == YES), so this writer and initialize() disagreed
            // on NOKEYS-without-YES and the swipe gate followed whichever ran last.
            if (context != null) {
                current.isHardwareKeyboardHidden =
                        isHardKeyboardHidden(context.getResources().getConfiguration());
            }
        }
        return current;
    }

    /**
     * The one rule for {@link #isHardwareKeyboardHidden()}: the framework says the hardware
     * keyboard is hidden. This is the original APK's swipe-gate test ({@code ad.a(Context)}), and
     * the same test {@link #isPhysicalKeyboardAvailable(Context)} uses. "No keyboard at all" is
     * deliberately not folded in: {@link #isHardwareKeyboardActive()} already ANDs in
     * {@link #hasPhysicalKeyboard()}, and the framework reports hidden=YES for NOKEYS anyway.
     */
    static boolean isHardKeyboardHidden(Configuration config) {
        return config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES;
    }

    public static void initialize(Context context) {
        initializeForDevice(context, DeviceInputResolver.ALL_DEVICES);
    }

    /**
     * Starts {@link #initialize(Context, Configuration)} on a background thread and returns at
     * once. Every reader below joins the load before it can observe {@link #current}, so this is
     * a pure overlap: the device scan, the active-config XML parse and the debug-override pref
     * read stop sitting on the IME's {@code onCreate} main thread and run alongside
     * {@code super.onCreate()}, the themed context and the colour manager instead.
     *
     * <p><b>Ordering.</b> The consumers, and when each first runs:
     * <ul>
     *   <li>{@code SettingsValues} ({@code loadAutoCorrectionMode},
     *       {@code isGestureInputEnabledForDevice}) and
     *       {@code SettingsManager.loadSettings -> fromSettings}: first at
     *       {@code BlackBerryIME.initializeLearningManagers()}, two steps after this call and
     *       still inside {@code onCreate}. Joins through {@link #current()} / {@link #fromSettings}.
     *       That is where the main thread pays whatever of the load is left, and it is the
     *       happens-before edge every later main-thread reader inherits.</li>
     *   <li>{@code HardwareKeyBridge.registerInterceptorCallbacks()}: posted at 0 ms on the IME
     *       handler, so after {@code onCreate} returns. Joins through {@link #current()}.</li>
     *   <li>{@code KeyEventProcessor} on the first physical key: after {@code onCreate}. Joins
     *       through {@link #appContext()} and {@link #current()} - and because
     *       {@link #appContext()} waits, its {@code appContext() == null} "never initialized"
     *       test cannot come back true merely because the load is still running, so it cannot
     *       fire a redundant second {@link #initializeForDevice}.</li>
     *   <li>{@code initializeBackgroundPreloading()}'s alt-mappings prewarm and PKB branch: end
     *       of {@code onCreate}, after {@code loadSettings()} has already joined.</li>
     *   <li>The keyboard build ({@code KeyboardSwitcher} / {@code KeyboardBuilder}) and the
     *       settings screens: {@code onCreateInputView} / activity launch, far later.</li>
     *   <li>{@code Xt9KdbVariant.apply} and {@code GestureEventProcessor.setEngineYWarp} are
     *       performed <em>inside</em> {@link #initializeForDevice}, so they complete before the
     *       latch opens; the KDB side additionally has its own {@code awaitReady} barrier.</li>
     * </ul>
     *
     * <p>Idempotent in the sense that matters: a second call while a load is in flight joins the
     * first one instead of starting a second.
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
            // Could not spawn: fall back to the synchronous behaviour this replaced.
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
     * As {@link #initialize(Context)}, but resolving the device mapping against one specific
     * input-device id instead of scanning every physical keyboard.
     *
     * <p>{@code DeviceSettingsManager} was the only caller of the targeted form, from
     * {@code KeyEventProcessor} on the first physical key event. It survives the merge (§5.4) so
     * that path keeps its meaning: with two physical keyboards attached, the id picks which one
     * the overrides come from, where the scan takes whichever matches first.
     */
    public static void initializeForDevice(Context context, int deviceId) {
        awaitInitialized();
        final long initToken = StartupTiming.begin();
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
        // Resolved BEFORE the capability probe, not after it as this used to be: the config's
        // optional <keypad-layout> is the top-ranked source for KeypadLayoutDetector, which runs
        // inside DeviceCapabilities.detect(). resolveInputMapping caches per device id, so the
        // move costs nothing on the repeat calls this method takes.
        DeviceInputMapping mapping = context != null
                ? DeviceInputResolver.resolveInputMapping(context, deviceId)
                : null;

        DeviceCapabilities caps = DeviceCapabilities.detect(mapping);
        DeviceRuntimeState state = DeviceRuntimeState.getInstance();
        DeviceProfile profile = new DeviceProfile(caps, state);
        profile.deviceMapping = mapping;

        // Check for debug VKB override
        if (context != null) {
            SharedPreferences prefs =
                    dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context);
            // Standing debug override — set unconditionally so a pref flipped off also clears it.
            state.setDebugForceVkbMode(prefs.getBoolean(PREF_DEBUG_FORCE_VKB_MODE, false));
        }

        // Apply what the resolved device mapping drives outside the profile object itself.
        if (context != null) {
            // Device KDB variant: route the engine's layout loads through assets/kdb/<variant>/
            // (owned KDB module; per-layout override with root fallback). Runs here so it covers
            // both IME startup and config switches, before the next keyboard layout sync.
            com.blackberry.nuanceshim.Xt9KdbVariant.apply(context,
                    profile.deviceMapping != null ? profile.deviceMapping.kdbVariant : null);
            // Retail swipe config (§8.7.12): the piecewise sensor->engine Y warp for the CKB
            // gesture feed rides the same lifecycle as the variant (startup + config switches).
            dev.bbkb.ime.keyboard.internal.GestureEventProcessor.setEngineYWarp(
                    profile.deviceMapping != null ? profile.deviceMapping.ckbYWarp : null);
        }

        Logger.info("CKB_DEBUG", "DeviceProfile.initialize:"
                + " deviceId=" + (deviceId == DeviceInputResolver.ALL_DEVICES ? "all" : deviceId)
                + ", caps=" + caps
                + ", mapping=" + (profile.deviceMapping == null ? "none" : (profile.deviceMapping.deviceName != null ? profile.deviceMapping.deviceName : "<unnamed>"))
                + ", forceTouchKeypad=" + (profile.deviceMapping != null && profile.deviceMapping.forceTouchKeypad)
                + ", hasTouchKeypad=" + profile.hasTouchKeypad()
                + ", touchKeypadDeviceId=" + profile.getTouchKeypadDeviceId()
                + ", primaryKeyboard=" + caps.getPrimaryKeyboard());

        current = profile;
        StartupTiming.end("deviceProfile.initializeForDevice", initToken);
    }

    /**
     * Rebuilds the profile after {@code KeypadLayoutDetector.observeKeyEvent} proved a different
     * physical keypad layout than the one currently in force.
     *
     * <p>Same shape as the rebuild {@code CustomDeviceConfigManager.setActiveConfigId} does when
     * an imported config changes the answer — {@link #initializeForDevice}, which re-runs
     * {@link DeviceCapabilities#detect(DeviceInputMapping)} and so picks the detector's upgraded
     * answer up — minus that path's {@code DeviceInputResolver.resetConfig()} /
     * {@code KeyEventDeviceClassifier.clearCache()} / scanner refresh. Those three exist there
     * because the *config file* changed; nothing about the device did. This runs from inside the
     * hardware key path, where dropping the scancode-role resolver and the per-event device
     * classification mid-keystroke would be a real hazard for no gain.
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
     * Installs a profile over the given capabilities, bypassing the hardware scan. The injection
     * seam for {@link #current()}, which is otherwise a static singleton with no way in: on a JVM
     * {@link DeviceCapabilities#detect()} always reports the VKB shape, so anything that branches
     * on {@link #isPkbDevice()} (most of the settings screens, among others) can only ever be
     * exercised in one of its two shapes.
     *
     * <p>Shares the process-wide {@link DeviceRuntimeState} with a detected profile, so the
     * force-VKB levers keep working across an installed shape exactly as they do on a device — and
     * a test that sets one must clear it again. {@link #initialize(Context)} discards whatever was
     * installed and goes back to detection.
     *
     * <p>Production never calls this.
     */
    @VisibleForTesting
    public static void installForTest(DeviceCapabilities capabilities) {
        clearPendingInitForTest();
        current = new DeviceProfile(capabilities, DeviceRuntimeState.getInstance());
    }

    /** Update display configuration (called on config change) */
    public void updateConfiguration(Configuration config) {
        if (config != null) {
            this.displayOrientation = config.orientation;
            this.isHardwareKeyboardHidden = isHardKeyboardHidden(config);
        }
    }

    // ==================== Device Type Enum ====================

    public enum DeviceType {
        PKB, VKB, UNKNOWN
    }

    // ==================== Capability Queries ====================

    public boolean isPkbDevice() {
        if (runtimeState.isForceVkbMode()) return false;
        DeviceCapabilities.DetectedDeviceType type = capabilities.getDeviceType();
        return type == DeviceCapabilities.DetectedDeviceType.PKB || type == DeviceCapabilities.DetectedDeviceType.HYBRID;
    }

    public boolean isVkbDevice() {
        return !isPkbDevice();
    }

    public DeviceType getDeviceType() {
        return isPkbDevice() ? DeviceType.PKB : DeviceType.VKB;
    }

    public boolean hasPhysicalKeyboard() {
        return capabilities.hasPhysicalKeyboard();
    }

    public boolean hasTouchKeypad() {
        if (deviceMapping != null && deviceMapping.forceTouchKeypad) return true;
        return capabilities.hasTouchKeypad();
    }

    public int getTouchKeypadDeviceId() {
        if (deviceMapping != null && deviceMapping.forceTouchKeypad) {
            int hwId = capabilities.getTouchKeypadDeviceId();
            if (hwId != -1) return hwId;
            // Hardware scan found no touch keypad; on a forced-CKB device the capacitive
            // overlay shares the same device ID as the primary keyboard.
            KeyboardDeviceInfo primary = capabilities.getPrimaryKeyboard();
            if (primary != null) {
                Logger.info("CKB_DEBUG", "getTouchKeypadDeviceId: forceTouchKeypad, hw=-1, using primaryKeyboard.deviceId=" + primary.getDeviceId());
                return primary.getDeviceId();
            }
        }
        return capabilities.getTouchKeypadDeviceId();
    }

    public float getTouchKeypadResolution() {
        return capabilities.getTouchKeypadResolution();
    }

    public float getTouchKeypadYMax() {
        float hw = capabilities.getTouchKeypadYMax();
        // Forced-CKB profile with no hardware pad (the W2 emulator posing as a KEY2): the
        // sensor y-range is the last sensor breakpoint of the config's own <ckb-y-warp>
        // ("0:0,450:324" -> 450). Real CKB hardware never reaches this fallback — its
        // scanner supplies the value.
        if (hw <= 0 && deviceMapping != null && deviceMapping.forceTouchKeypad
                && deviceMapping.ckbYWarp != null) {
            try {
                String[] pts = deviceMapping.ckbYWarp.trim().split(",");
                String last = pts[pts.length - 1];
                float v = Float.parseFloat(last.split(":")[0].trim());
                if (v > 0) return v;
            } catch (Throwable ignored) { }
        }
        return hw;
    }

    /**
     * The physical keypad layout — "qwerty", "qwertz" or "azerty". Resolved once per
     * {@link #initializeForDevice} by {@code KeypadLayoutDetector} from the active device
     * config, the keypad's InputDevice name, the firmware system properties, the loaded
     * KeyCharacterMap and any scancode observation, in that order; there is no user setting.
     */
    public String getKeypadLayout() {
        return capabilities.getKeypadLayout();
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

    // ==================== SDK Version ====================

    public static boolean isMarshmallowOrLater() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    // ==================== Display ====================

    public int getDisplayOrientation() { return displayOrientation; }
    public boolean isLandscape() { return displayOrientation == Configuration.ORIENTATION_LANDSCAPE; }
    public boolean isPortrait() { return displayOrientation == Configuration.ORIENTATION_PORTRAIT; }
    public boolean isHardwareKeyboardHidden() { return isHardwareKeyboardHidden; }
    public boolean isHardwareKeyboardActive() { return hasPhysicalKeyboard() && !isHardwareKeyboardHidden; }

    // ==================== Fullscreen ====================

    public boolean shouldUseFullscreenMode(boolean fullscreenEnabledInSettings) {
        if (isPkbDevice()) return false;
        return fullscreenEnabledInSettings;
    }

    // ==================== Meta/Sym Handling ====================

    public boolean usesMetaSymHandling() {
        if (!isPkbDevice()) return false;
        return hasCustomAltMappings();
    }

    public boolean hasAltSymKey() {
        return isPkbDevice() && !isBlackBerryDevice();
    }

    // ==================== Touch Keypad ====================

    public boolean isFromTouchKeypad(MotionEvent motionEvent) {
        if (!hasTouchKeypad() || motionEvent == null) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "isFromTouchKeypad=false: hasTouchKeypad=" + hasTouchKeypad() + " event=" + motionEvent);
            return false;
        }
        int tkpId = getTouchKeypadDeviceId();
        int evtId = motionEvent.getDeviceId();
        boolean result = evtId == tkpId;
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

    // ==================== Device Mapping / Override Methods ====================

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
        if (!hasCustomAltMappings() || deviceMapping.altMappingsFile == null) {
            return null;
        }
        return AltMappingsParser.getCachedMappings(context, deviceMapping.altMappingsFile);
    }

    /**
     * Get the layout-specific alt override mappings table for the given layout.
     * Loaded from the device config's <layout-alt-overrides> section.
     *
     * @param context Application context
     * @param layout Active keypad layout (e.g. "azerty", "qwertz")
     * @return AltMappingsTable for the layout override, or null if none configured
     */
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

    /**
     * Whether a setting should be hidden from the settings UI entirely, rather than shown
     * disabled. Distinct from {@link #isSettingReadOnly}: read-only greys the control out, hidden
     * removes it.
     *
     * <p>Came from {@code DeviceSettingsManager}, the second facade over this same
     * {@code DeviceInputMapping}, when the two were merged (§5.4).
     */
    public boolean isSettingHidden(String settingKey) {
        DeviceSettingOverride override = getSettingOverride(settingKey);
        return override != null && override.hidden;
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
    }

    // ==================== Runtime State ====================

    public static void setOnScreenKeyboardShowing(boolean showing) {
        DeviceRuntimeState.getInstance().setOnScreenKeyboardShowing(showing);
    }

    public static boolean isOnScreenKeyboardVisible() {
        return DeviceRuntimeState.getInstance().isOnScreenKeyboardShowing() || current().isVkbDevice();
    }

    public static void setForceVkbMode(boolean force) {
        DeviceRuntimeState.getInstance().setForceVkbMode(force);
    }

    public static boolean isForceVkbMode() {
        DeviceRuntimeState st = DeviceRuntimeState.getInstance();
        return st.isForceVkbMode() || st.isDebugForceVkbMode();
    }

    /** Live setter for the Debug settings "Force touchscreen-only mode" toggle (no IME restart
     *  needed; survives hideWindow(), which clears only the transient force). */
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

    // ==================== Key Character Map ====================

    /**
     * Get the KeyCharacterMap for the primary physical keyboard (device 0).
     * Returns null if no physical keyboard is present.
     */
    @Nullable
    public static KeyCharacterMap getKeyCharacterMap() {
        if (!current().hasPhysicalKeyboard()) return null;
        android.view.InputDevice device = android.view.InputDevice.getDevice(0);
        return device != null ? device.getKeyCharacterMap() : null;
    }

    // ==================== Context-Aware Checks ====================

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

    // ==================== Layout Resolution ====================

    /**
     * Resolve a layout resource ID, applying the matched device's
     * {@code <layout-overrides>} from the device config. Consolidated from the legacy
     * DeviceLayoutOverrideResolver (which re-parsed the config with its own parser and its own
     * device detection); the DeviceInputResolver-matched {@link #deviceMapping} is the single
     * authority now.
     */
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
            // Unresolvable id — fall through to the original.
        }
        return originalResourceId;
    }

    // ==================== Legacy Compat ====================

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

    // Convenience static accessors
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
