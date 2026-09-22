package dev.bbkb.ime.core.device.profile;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Mutable runtime state. Singleton with thread-safe accessors.
 */
public final class DeviceRuntimeState {

    private static volatile DeviceRuntimeState instance;
    private static final Object LOCK = new Object();

    private volatile boolean onScreenKeyboardShowing = false;
    private volatile boolean forceVkbMode = false;
    private final Set<String> vkbForcedPackages = Collections.synchronizedSet(new HashSet<String>());

    public static DeviceRuntimeState getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new DeviceRuntimeState();
                }
            }
        }
        return instance;
    }

    private DeviceRuntimeState() {}

    public boolean isOnScreenKeyboardShowing() { return onScreenKeyboardShowing; }
    public void setOnScreenKeyboardShowing(boolean showing) {
        this.onScreenKeyboardShowing = showing;
    }

    public boolean isForceVkbMode() { return forceVkbMode; }
    public void setForceVkbMode(boolean force) { this.forceVkbMode = force; }

    /** Standing debug override ("Force touchscreen-only mode"). Kept SEPARATE from the transient
     *  forceVkbMode: the original clears that on every hideWindow() (its force came from the
     *  show-VKB key and is per-window by design), which silently erased the debug toggle on the
     *  first field switch — the pref then only ever worked for one window after a process
     *  restart. This flag is set live by the Debug settings toggle and at DeviceProfile
     *  .initialize() from the pref, and nothing else touches it. */
    private volatile boolean debugForceVkbMode = false;
    public boolean isDebugForceVkbMode() { return debugForceVkbMode; }
    public void setDebugForceVkbMode(boolean force) { this.debugForceVkbMode = force; }

    public boolean isVkbForcedForPackage(String packageName) {
        return packageName != null && vkbForcedPackages.contains(packageName);
    }

    public void addVkbForcedPackage(String packageName) {
        if (packageName != null) vkbForcedPackages.add(packageName);
    }

    public void removeVkbForcedPackage(String packageName) {
        if (packageName != null) vkbForcedPackages.remove(packageName);
    }
}
