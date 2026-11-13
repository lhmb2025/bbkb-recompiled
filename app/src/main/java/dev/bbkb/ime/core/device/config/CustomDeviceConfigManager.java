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

/** Device configurations: the default, the preloaded ones in res/xml, and user-imported custom ones. */
public class CustomDeviceConfigManager {

    private static final String TAG = "CustomDeviceConfigManager";
    private static final String PREF_ACTIVE_CONFIG_ID = "active_device_config_id";
    /**
     * True when the stored id was picked by hand, meaning "apply this to my phone". An id stored
     * without the flag applies only where its {@code <match>} rules hold, so an old stray pick on
     * the wrong phone stays inert.
     */
    private static final String PREF_ACTIVE_CONFIG_CHOSEN = "active_device_config_chosen";
    private static final String CUSTOM_CONFIG_DIR = "device_configs";

    public static final String ID_DEFAULT = "default";
    private static final String PREFIX_PRELOADED = "preloaded:";
    private static final String PREFIX_CUSTOM = "custom:";

    private static CustomDeviceConfigManager sInstance;
    private final Context mContext;
    private final SharedPreferences mPrefs;

    // setActiveConfigId() is the only writer of PREF_ACTIVE_CONFIG_ID and clears these, so a
    // non-null mActiveConfig is authoritative.
    private DeviceInputConfig mActiveConfig;

    // Memoised findMatchingPreloadedConfig(): an R.xml reflection sweep plus one parse per config.
    private String mAutoSelectedId;
    private boolean mAutoSelectSearched;

    // Memoised getPreloadedConfigs(): expensive, and fixed for the process (R.xml and the XMLs are in the APK).
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

    /** The active device configuration; never null (falls back to default). */
    public synchronized DeviceInputConfig getActiveConfig() {
        // Fast path before getActiveConfigId(), which can trigger the preloaded-config scan.
        if (mActiveConfig != null) {
            return mActiveConfig;
        }

        String activeId = getActiveConfigId();

        DeviceInputConfig config = loadConfigById(activeId);
        if (isUnusable(activeId, config)) {
            // The stored choice is gone (file deleted, restored id without the file, renamed
            // profile) or holds no device: drop it so detection runs as on a fresh install.
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

    /** The active configuration id; on default, a preloaded config matching a connected keyboard. */
    public synchronized String getActiveConfigId() {
        String storedId = mPrefs.getString(PREF_ACTIVE_CONFIG_ID, ID_DEFAULT);

        if (ID_DEFAULT.equals(storedId)) {
            if (!mAutoSelectSearched) {
                mAutoSelectedId = findMatchingPreloadedConfig();
                mAutoSelectSearched = true;
                if (mAutoSelectedId != null) {
                    Logger.debug(TAG, "Auto-selecting preloaded config: " + mAutoSelectedId);
                }
            }
            if (mAutoSelectedId != null) {
                // Not persisted: only a hand pick is stored.
                return mAutoSelectedId;
            }
        }
        
        return storedId;
    }

    /** A preloaded config that matches a connected physical keyboard, or null. */
    private String findMatchingPreloadedConfig() {
        // Enumerate devices first: with no external keyboard the answer is null, and the config
        // scan is on the cold-start path.
        final List<android.view.InputDevice> keyboards = connectedExternalKeyboards();
        if (keyboards.isEmpty()) return null;

        List<ConfigInfo> preloaded = getPreloadedConfigs();
        if (preloaded.isEmpty()) return null;

        // Each preloaded config is parsed at most once.
        final Map<String, DeviceInputConfig> parsed = new HashMap<>();

        for (android.view.InputDevice keyboard : keyboards) {
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
     * The connected non-virtual keyboards in {@code getDeviceIds()} order. Devices rather than
     * names so {@code vendor-id} / {@code product-id} rules can match.
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

    public void setActiveConfigId(String id) {
        // Every pick here is by hand: the Device configuration screen, or a deleted config reverting.
        mPrefs.edit()
                .putString(PREF_ACTIVE_CONFIG_ID, id)
                .putBoolean(PREF_ACTIVE_CONFIG_CHOSEN, !ID_DEFAULT.equals(id))
                .apply();
        synchronized (this) {
            mActiveConfig = null;
            mAutoSelectedId = null;
            mAutoSelectSearched = false;
        }
        DeviceInputResolver.resetConfig();
        // These caches are otherwise fixed for the process; stale primaryTouchKeypad breaks CKB swipe input.
        KeyEventDeviceClassifier.getInstance().clearCache();
        KeyboardDeviceScanner.getInstance().refresh();
        DeviceProfile.initialize(mContext);
    }

    public List<ConfigInfo> getAvailableConfigs() {
        List<ConfigInfo> configs = new ArrayList<>();

        configs.add(new ConfigInfo(ID_DEFAULT, "Detect automatically", ConfigType.DEFAULT, null, 0));

        configs.addAll(getPreloadedConfigs());

        configs.addAll(getCustomConfigs());

        return configs;
    }

    private synchronized List<ConfigInfo> getPreloadedConfigs() {
        if (mPreloadedConfigs != null) {
            return mPreloadedConfigs;
        }
        List<ConfigInfo> list = new ArrayList<>();
        try {
            // The build-time R class: namespace != applicationId at runtime.
            Class<?> rXmlClass = dev.bbkb.ime.R.xml.class;
            Field[] fields = rXmlClass.getFields();

            for (Field field : fields) {
                String name = field.getName();
                if (name.startsWith("device_config_")) {
                    int resId = field.getInt(null);
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
            // R8 can strip R.xml's constant fields, leaving every device on the default config
            // (release builds only). proguard-rules.pro keeps R$xml's static fields for this.
            Logger.error(TAG, "No preloaded device configs found by the R.xml scan"
                    + " - check the R$xml keep rule in proguard-rules.pro");
        }
        
        Collections.sort(list, BY_NAME);
        // Memoised even when empty: stripped fields stay stripped.
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

        // Locale.ROOT: a Turkish lowercase of "XML" has a dotless i.
        File[] files = dir.listFiles((dir1, name) -> name.toLowerCase(Locale.ROOT).endsWith(".xml"));
        if (files == null) return list;

        for (File file : files) {
            String filename = file.getName();
            String id = PREFIX_CUSTOM + filename;

            String displayName = getNameFromFile(file);
            if (displayName == null) {
                displayName = filename;
            }
            
            list.add(new ConfigInfo(id, displayName, ConfigType.CUSTOM, file.getAbsolutePath(), 0));
        }
        
        Collections.sort(list, BY_NAME);
        return list;
    }

    /** @return the id of the imported config, or null on failure */
    public String importCustomConfig(Uri uri) {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = mContext.getContentResolver().openInputStream(uri);
            String displayName = DeviceInputMappingParser.parseDisplayName(is);
            is.close();
            
            if (displayName == null) {
                displayName = "Imported Config " + System.currentTimeMillis();
            }

            String safeName = displayName.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
            String filename = safeName + ".xml";
            
            File dir = new File(mContext.getFilesDir(), CUSTOM_CONFIG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            File destFile = new File(dir, filename);
            int counter = 1;
            while (destFile.exists()) {
                destFile = new File(dir, safeName + "_" + counter + ".xml");
                counter++;
            }

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
                // Nothing to do if closing fails.
            }
        }
    }

    public boolean deleteCustomConfig(String id) {
        if (!id.startsWith(PREFIX_CUSTOM)) return false;
        
        String filename = id.substring(PREFIX_CUSTOM.length());
        File file = new File(new File(mContext.getFilesDir(), CUSTOM_CONFIG_DIR), filename);
        
        boolean deleted = false;
        if (file.exists()) {
            deleted = file.delete();
        }
        
        if (deleted) {
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
            // getIdentifier() needs the runtime applicationId.
            int resId = mContext.getResources().getIdentifier(resName, "xml", mContext.getPackageName());
            if (resId != 0) {
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
     * The built-in profile a custom one extends ({@code <device extends="device_config_athena">}),
     * parsed afresh so the caller can change it, or null. Only {@code device_config_*} resources
     * can be named, so an imported file cannot pull in any other XML resource.
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
        // res/xml resources must be opened with getXml(), not openRawResource().
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
