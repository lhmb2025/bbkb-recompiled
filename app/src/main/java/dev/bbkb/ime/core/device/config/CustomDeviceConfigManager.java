package dev.bbkb.ime.core.device.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.shared.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages device configurations, including the default system config,
 * preloaded configurations from resources, and user-imported custom configurations.
 */
public class CustomDeviceConfigManager {

    private static final String TAG = "CustomDeviceConfigManager";
    private static final String PREF_ACTIVE_CONFIG_ID = "active_device_config_id";
    /**
     * True once the user has picked the stored configuration on the Device configuration screen
     * with a build that treats a pick as "apply this to my phone". An id stored by an earlier
     * build has no such flag and keeps what it meant then: it applies only where its
     * {@code <match>} rules hold. Without this, a profile someone once tapped on a phone it was
     * not made for, where it did nothing, would switch itself on with the update.
     */
    private static final String PREF_ACTIVE_CONFIG_CHOSEN = "active_device_config_chosen";
    private static final String CUSTOM_CONFIG_DIR = "device_configs";
    
    // Config ID prefixes
    public static final String ID_DEFAULT = "default";
    private static final String PREFIX_PRELOADED = "preloaded:";
    private static final String PREFIX_CUSTOM = "custom:";

    private static CustomDeviceConfigManager sInstance;
    private final Context mContext;
    private final SharedPreferences mPrefs;
    
    // Cached active config. setActiveConfigId() is the only writer of PREF_ACTIVE_CONFIG_ID
    // and clears these, so a non-null mActiveConfig is authoritative.
    private DeviceInputConfig mActiveConfig;

    // Memoised findMatchingPreloadedConfig() result. That search reflects over every field of
    // R.xml and pull-parses one XML per preloaded config, and getActiveConfigId() used to run
    // it on every call whenever the stored id was "default" -- which is the shipped default.
    private String mAutoSelectedId;
    private boolean mAutoSelectSearched;

    // Memoised getPreloadedConfigs() result. The scan reflects over every field of R.xml (431
    // entries in this app) and then pull-parses one XML per device_config_* match just to read
    // its name attribute. Nothing about it can change while the process lives: R.xml's fields
    // are compile-time constants and the XMLs are in the APK. It used to be re-run on every
    // getAvailableConfigs() (the device-config screen) and on every auto-select search.
    private List<ConfigInfo> mPreloadedConfigs;

    public enum ConfigType {
        DEFAULT,
        PRELOADED,
        CUSTOM
    }

    public static class ConfigInfo {
        public final String id;
        public final String name;
        public final ConfigType type;
        public final String path; // File path for custom, resource name for preloaded
        public final int resourceId; // For preloaded

        public ConfigInfo(String id, String name, ConfigType type, String path, int resourceId) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.path = path;
            this.resourceId = resourceId;
        }
    }

    private CustomDeviceConfigManager(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PrefsManager.defaultSharedPreferences(mContext);
    }

    public static synchronized CustomDeviceConfigManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new CustomDeviceConfigManager(context);
        }
        return sInstance;
    }

    /** Drops the singleton, so the next {@link #getInstance} reads the stored choice afresh. */
    @androidx.annotation.VisibleForTesting
    public static synchronized void resetForTest() {
        sInstance = null;
    }

    /**
     * Get the currently active device configuration.
     * @return DeviceInputConfig object, never null (falls back to default).
     */
    public synchronized DeviceInputConfig getActiveConfig() {
        // Fast path first. getActiveConfigId() used to run *before* this check, dragging the
        // whole preloaded-config scan onto DeviceInputResolver.resolveInputMapping - reached
        // from DeviceProfile.initialize and, if that has not run yet, from the first keystroke.
        if (mActiveConfig != null) {
            return mActiveConfig;
        }

        String activeId = getActiveConfigId();

        DeviceInputConfig config = loadConfigById(activeId);
        if (isUnusable(activeId, config)) {
            // The stored choice is gone (its file was deleted, a restore brought the id without
            // the file, an update renamed a shipped profile) or holds no device. It used to leave
            // the phone on the empty default with detection switched off and no row selected.
            // Now the choice is dropped, so detection runs again as on a fresh install.
            Logger.error(TAG, "Active config " + activeId + " cannot be used; back to automatic detection");
            forgetStoredChoice();
            activeId = getActiveConfigId();
            config = loadConfigById(activeId);
            if (config == null) {
                config = loadDefaultConfig();
            }
        }
        config.chosenByUser = isChosenByUser();

        mActiveConfig = config;
        return mActiveConfig;
    }

    /** A chosen configuration that did not load, or that loaded with no {@code <device>} in it. */
    private static boolean isUnusable(String id, DeviceInputConfig config) {
        if (config == null) return true;
        return !ID_DEFAULT.equals(id) && config.mappings.isEmpty();
    }

    /** Whether the stored configuration is one the user picked by hand (see the preference). */
    private boolean isChosenByUser() {
        return !ID_DEFAULT.equals(mPrefs.getString(PREF_ACTIVE_CONFIG_ID, ID_DEFAULT))
                && mPrefs.getBoolean(PREF_ACTIVE_CONFIG_CHOSEN, false);
    }

    /** Puts the stored choice back to "Detect automatically", without the reload a pick does. */
    private void forgetStoredChoice() {
        mPrefs.edit()
                .putString(PREF_ACTIVE_CONFIG_ID, ID_DEFAULT)
                .remove(PREF_ACTIVE_CONFIG_CHOSEN)
                .apply();
        mAutoSelectedId = null;
        mAutoSelectSearched = false;
    }

    /**
     * Get the ID of the currently active configuration.
     * If set to default and a preloaded config matches the connected device, auto-selects it.
     */
    public synchronized String getActiveConfigId() {
        String storedId = mPrefs.getString(PREF_ACTIVE_CONFIG_ID, ID_DEFAULT);
        
        // Auto-select matching preloaded config if currently on default
        if (ID_DEFAULT.equals(storedId)) {
            if (!mAutoSelectSearched) {
                mAutoSelectedId = findMatchingPreloadedConfig();
                mAutoSelectSearched = true;
                if (mAutoSelectedId != null) {
                    Logger.debug(TAG, "Auto-selecting preloaded config: " + mAutoSelectedId);
                }
            }
            if (mAutoSelectedId != null) {
                // Don't persist - let user explicitly choose if they want to keep it
                return mAutoSelectedId;
            }
        }
        
        return storedId;
    }
    
    /**
     * Find a preloaded config that matches a connected physical keyboard device.
     */
    private String findMatchingPreloadedConfig() {
        // Enumerate first, scan second. The device list is the cheap half and it decides the
        // question: with no external physical keyboard attached the answer is null whatever the
        // preloaded configs say, and asking anyway cost an R.xml reflection sweep plus one XML
        // pull-parse per preloaded config -- on the IME's cold-start path and on every cold
        // Settings launch, on phones that can never match.
        final List<android.view.InputDevice> keyboards = connectedExternalKeyboards();
        if (keyboards.isEmpty()) return null;

        List<ConfigInfo> preloaded = getPreloadedConfigs();
        if (preloaded.isEmpty()) return null;

        // Each preloaded config is parsed at most once. The nested loop used to re-parse every
        // preloaded config from XML once per connected keyboard device.
        final Map<String, DeviceInputConfig> parsed = new HashMap<>();

        for (android.view.InputDevice keyboard : keyboards) {
            // Check each preloaded config for a match
            for (ConfigInfo info : preloaded) {
                DeviceInputConfig config;
                if (parsed.containsKey(info.id)) {
                    config = parsed.get(info.id);
                } else {
                    config = loadConfigById(info.id);
                    parsed.put(info.id, config);
                }
                if (config != null && config.findMappingForInputDevice(keyboard) != null) {
                    return info.id;
                }
            }
        }
        return null;
    }

    /**
     * The connected non-virtual keyboard devices, in {@code InputDevice.getDeviceIds()} order --
     * the same devices, in the same order, as the nested loop this was lifted out of. Devices
     * rather than names so a config's {@code vendor-id} / {@code product-id} rules can match.
     */
    private static List<android.view.InputDevice> connectedExternalKeyboards() {
        final List<android.view.InputDevice> keyboards = new ArrayList<>(2);
        int[] deviceIds = android.view.InputDevice.getDeviceIds();
        if (deviceIds == null) return keyboards;
        for (int id : deviceIds) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null) continue;

            int sources = device.getSources();
            boolean isKeyboard = (sources & android.view.InputDevice.SOURCE_KEYBOARD) != 0;
            boolean isExternal = !device.isVirtual();

            if (isKeyboard && isExternal) {
                keyboards.add(device);
            }
        }
        return keyboards;
    }

    /**
     * Set the active configuration ID.
     */
    public void setActiveConfigId(String id) {
        // Every pick made here is by hand: the Device configuration screen and a deleted
        // configuration falling back to the default are the only callers.
        mPrefs.edit()
                .putString(PREF_ACTIVE_CONFIG_ID, id)
                .putBoolean(PREF_ACTIVE_CONFIG_CHOSEN, !ID_DEFAULT.equals(id))
                .apply();
        // Clear cache to force reload on next get
        synchronized (this) {
            mActiveConfig = null;
            mAutoSelectedId = null;
            mAutoSelectSearched = false;
        }
        // Also clear the DeviceInputResolver cache and refresh DeviceProfile
        DeviceInputResolver.resetConfig();
        // The device-classification and hardware-scan caches are computed once at first key or
        // boot and were then immutable for the process: switching config in Settings left them
        // stale, including primaryTouchKeypad, which decides isFromTouchKeypad() and therefore
        // whether CKB swipe input works at all.
        KeyEventDeviceClassifier.getInstance().clearCache();
        KeyboardDeviceScanner.getInstance().refresh();
        DeviceProfile.initialize(mContext);
    }

    /**
     * Get a list of all available configurations.
     */
    public List<ConfigInfo> getAvailableConfigs() {
        List<ConfigInfo> configs = new ArrayList<>();

        // 1. Default
        configs.add(new ConfigInfo(ID_DEFAULT, "Detect automatically", ConfigType.DEFAULT, null, 0));

        // 2. Preloaded (scan R.xml for device_config_*)
        configs.addAll(getPreloadedConfigs());

        // 3. Custom (scan internal storage)
        configs.addAll(getCustomConfigs());

        return configs;
    }

    private synchronized List<ConfigInfo> getPreloadedConfigs() {
        if (mPreloadedConfigs != null) {
            return mPreloadedConfigs;
        }
        List<ConfigInfo> list = new ArrayList<>();
        try {
            // Use reflection to find all XML resources starting with "device_config_"
            // Use the build-time R class directly (namespace != applicationId at runtime)
            Class<?> rXmlClass = dev.bbkb.ime.R.xml.class;
            Field[] fields = rXmlClass.getFields();

            for (Field field : fields) {
                String name = field.getName();
                if (name.startsWith("device_config_")) {
                    int resId = field.getInt(null);
                    // Parse just the name from the XML header
                    String displayName = getNameFromResource(resId);
                    if (displayName == null) {
                        displayName = formatNameFromResource(name);
                    }
                    
                    String id = PREFIX_PRELOADED + name;
                    list.add(new ConfigInfo(id, displayName, ConfigType.PRELOADED, name, resId));
                }
            }
        } catch (Exception e) {
            Logger.error(TAG, "Error scanning preloaded configs: " + e.getMessage());
        }

        if (list.isEmpty()) {
            // Not merely "this device has no preloaded config": R.xml fields are compile-time
            // constants, so R8 can inline and strip them and getFields() then returns nothing.
            // An empty list makes every device fall back to the default config, losing
            // per-device scancode roles, KDB variant and CKB warp - release-only behaviour
            // that never reproduces in a debug build. proguard-rules.pro keeps
            // dev.bbkb.ime.R$xml's static fields for exactly this reason.
            Logger.error(TAG, "No preloaded device configs found by the R.xml scan"
                    + " - check the R$xml keep rule in proguard-rules.pro");
        }
        
        Collections.sort(list, BY_NAME);
        // Memoised even when empty: an empty result means the R.xml fields were stripped, which
        // will not become untrue later in the process, and re-running the sweep to rediscover
        // that is the expensive case.
        mPreloadedConfigs = Collections.unmodifiableList(list);
        return mPreloadedConfigs;
    }

    /** Both listings are ordered by display name, case-insensitively. */
    private static final Comparator<ConfigInfo> BY_NAME =
            (a, b) -> a.name.compareToIgnoreCase(b.name);

    private List<ConfigInfo> getCustomConfigs() {
        List<ConfigInfo> list = new ArrayList<>();
        File dir = new File(mContext.getFilesDir(), CUSTOM_CONFIG_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            return list;
        }

        // Locale.ROOT: in a Turkish locale "CONFIG.XML".toLowerCase() yields a dotless i and
        // the .xml test fails, so an imported .XML config silently disappears.
        File[] files = dir.listFiles((dir1, name) -> name.toLowerCase(Locale.ROOT).endsWith(".xml"));
        if (files == null) return list;

        for (File file : files) {
            String filename = file.getName();
            String id = PREFIX_CUSTOM + filename;
            
            // Parse display name from file header
            String displayName = getNameFromFile(file);
            if (displayName == null) {
                displayName = filename;
            }
            
            list.add(new ConfigInfo(id, displayName, ConfigType.CUSTOM, file.getAbsolutePath(), 0));
        }
        
        Collections.sort(list, BY_NAME);
        return list;
    }

    /**
     * Import a custom configuration from a URI.
     * @return The ID of the imported config, or null if failed.
     */
    public String importCustomConfig(Uri uri) {
        InputStream is = null;
        OutputStream os = null;
        try {
            // 1. Read to check validity and get name
            is = mContext.getContentResolver().openInputStream(uri);
            String displayName = DeviceInputMappingParser.parseDisplayName(is);
            is.close();
            
            if (displayName == null) {
                // Try to use filename if name attribute is missing, but still validate?
                // For now, let's assume if parseDisplayName returns null, it might be invalid XML or missing root.
                // Re-open to fully parse/validate if needed.
                // Let's at least require it to be parseable.
                // For now, we will use a generated name if attribute is missing.
                displayName = "Imported Config " + System.currentTimeMillis();
            }

            // 2. Determine destination filename
            // We use the displayName to generate a filename, sanitized
            String safeName = displayName.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
            String filename = safeName + ".xml";
            
            File dir = new File(mContext.getFilesDir(), CUSTOM_CONFIG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            File destFile = new File(dir, filename);
            // Handle duplicates
            int counter = 1;
            while (destFile.exists()) {
                destFile = new File(dir, safeName + "_" + counter + ".xml");
                counter++;
            }

            // 3. Copy file
            is = mContext.getContentResolver().openInputStream(uri);
            os = new FileOutputStream(destFile);
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            
            return PREFIX_CUSTOM + destFile.getName();

        } catch (Exception e) {
            Logger.error(TAG, "Failed to import config: " + e.getMessage());
            return null;
        } finally {
            try {
                if (is != null) is.close();
                if (os != null) os.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * Delete a custom configuration.
     */
    public boolean deleteCustomConfig(String id) {
        if (!id.startsWith(PREFIX_CUSTOM)) return false;
        
        String filename = id.substring(PREFIX_CUSTOM.length());
        File file = new File(new File(mContext.getFilesDir(), CUSTOM_CONFIG_DIR), filename);
        
        boolean deleted = false;
        if (file.exists()) {
            deleted = file.delete();
        }
        
        if (deleted) {
            // If active config was deleted, revert to default
            if (id.equals(getActiveConfigId())) {
                setActiveConfigId(ID_DEFAULT);
            }
        }
        
        return deleted;
    }

    private DeviceInputConfig loadConfigById(String id) {
        if (ID_DEFAULT.equals(id)) {
            return loadDefaultConfig();
        } else if (id.startsWith(PREFIX_PRELOADED)) {
            String resName = id.substring(PREFIX_PRELOADED.length());
            // Use runtime packageName (applicationId) for getIdentifier()
            int resId = mContext.getResources().getIdentifier(resName, "xml", mContext.getPackageName());
            if (resId != 0) {
                // XML resources must use getXml(), not openRawResource()
                return DeviceInputMappingParser.parseConfigFromXmlResource(mContext, resId);
            }
        } else if (id.startsWith(PREFIX_CUSTOM)) {
            String filename = id.substring(PREFIX_CUSTOM.length());
            File file = new File(new File(mContext.getFilesDir(), CUSTOM_CONFIG_DIR), filename);
            if (file.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(file);
                    DeviceInputConfig config =
                            DeviceInputMappingParser.parseConfigFromStream(fis, this::loadBuiltInProfile);
                    fis.close();
                    return config;
                } catch (IOException e) {
                    Logger.error(TAG, "Error loading custom config file: " + e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * The built-in profile a custom one builds on ({@code <device extends="device_config_athena">}),
     * parsed afresh so the caller can change it; null when no built-in profile has that name. Only
     * {@code device_config_*} resources can be named: a file from outside must not be able to pull
     * in any other XML resource.
     */
    private dev.bbkb.ime.core.device.config.model.DeviceInputMapping loadBuiltInProfile(String name) {
        if (name == null) return null;
        final String resName = name.startsWith(PREFIX_PRELOADED)
                ? name.substring(PREFIX_PRELOADED.length()) : name;
        if (!resName.startsWith("device_config_")) return null;
        final int resId = mContext.getResources().getIdentifier(resName, "xml", mContext.getPackageName());
        if (resId == 0) return null;
        final DeviceInputConfig base = DeviceInputMappingParser.parseConfigFromXmlResource(mContext, resId);
        return base.mappings.isEmpty() ? null : base.mappings.get(0);
    }

    /** The display name of the built-in profile called {@code name}, or null when there is none. */
    public String builtInProfileName(String name) {
        if (name == null) return null;
        final String id = name.startsWith(PREFIX_PRELOADED) ? name : PREFIX_PRELOADED + name;
        for (ConfigInfo info : getPreloadedConfigs()) {
            if (info.id.equals(id)) return info.name;
        }
        return null;
    }

    private DeviceInputConfig loadDefaultConfig() {
        return DeviceInputMappingParser.parseConfig(mContext);
    }

    private String getNameFromResource(int resId) {
        // XML resources in res/xml/ must be opened with getXml(), not openRawResource()
        try {
            android.content.res.XmlResourceParser parser = mContext.getResources().getXml(resId);
            int eventType = parser.getEventType();
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if ("device-input-config".equals(parser.getName())) {
                        String name = parser.getAttributeValue(null, "name");
                        parser.close();
                        return name;
                    }
                }
                eventType = parser.next();
            }
            parser.close();
        } catch (Exception e) {
            Logger.error(TAG, "Error reading name from resource: " + e.getMessage());
        }
        return null;
    }

    private String getNameFromFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            String name = DeviceInputMappingParser.parseDisplayName(fis);
            fis.close();
            return name;
        } catch (IOException e) {
            return null;
        }
    }
    
    private String formatNameFromResource(String resName) {
        // device_config_titan_pocket -> Titan Pocket
        String name = resName.replace("device_config_", "").replace("_", " ");
        // Capitalize words
        StringBuilder result = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (Character.isSpaceChar(c)) {
                nextUpper = true;
            } else if (nextUpper) {
                c = Character.toUpperCase(c);
                nextUpper = false;
            }
            result.append(c);
        }
        return result.toString();
    }
}
