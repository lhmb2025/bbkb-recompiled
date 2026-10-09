package dev.bbkb.ime.keyboard.inputboard.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which speech recogniser dictation uses: the "Speech recognizer" setting
 * ({@code voice_input_recognizer}) and the list of installed recognition services it chooses from.
 *
 * <p>The setting holds {@link #SYSTEM_DEFAULT} (empty: whatever the phone's secure
 * {@code voice_recognition_service} setting names), {@link #ON_DEVICE} (the platform's on-device
 * recogniser, API 31+), or a flattened component name of one installed {@code RecognitionService}.
 *
 * <p>Plain values in, plain values out, with no Android classes, so every decision here is tested on
 * the JVM. {@link InstalledVoiceRecognizers} is what reads the device and builds the recogniser.
 */
public final class VoiceRecognizerChoice {

    /** "" in the setting: the phone's own choice, as dictation always worked before this setting. */
    public static final String SYSTEM_DEFAULT = "";

    /** The platform's on-device recogniser ({@code createOnDeviceSpeechRecognizer}). */
    public static final String ON_DEVICE = "ondevice";

    /** API 31, where {@code createOnDeviceSpeechRecognizer} and its availability check arrived. */
    public static final int ON_DEVICE_MIN_SDK = 31;

    /**
     * The voice picker's remembered language list. The system default keeps the key it always had,
     * so an existing cache is still found; every other choice adds its own suffix, so switching
     * recognisers never shows the last one's languages. The settings backup excludes the whole
     * prefix.
     */
    public static final String LANGUAGE_CACHE_KEY = "voice_input_language_cache";

    private VoiceRecognizerChoice() {
    }

    /** One installed recognition service, as the package query reported it. */
    public static final class Service {
        public final String packageName;
        public final String className;
        /** The owning app's label, or null when it could not be read. */
        public final String appLabel;

        public Service(String packageName, String className, String appLabel) {
            this.packageName = packageName;
            this.className = className;
            this.appLabel = appLabel;
        }
    }

    /** One entry of the "Speech recognizer" list. */
    public static final class Provider {
        /** Flattened component name, {@code package/fully.qualified.Class}: the setting's value. */
        public final String component;
        public final String packageName;
        /** What the list shows: the app's label, made unique among the providers. */
        public final String label;
        /** This is the service the phone's secure setting names. */
        public final boolean isSystemDefault;

        public Provider(String component, String packageName, String label, boolean isSystemDefault) {
            this.component = component;
            this.packageName = packageName;
            this.label = label;
            this.isSystemDefault = isSystemDefault;
        }
    }

    /** How the recogniser is to be built. */
    public enum Kind {
        /** {@code createSpeechRecognizer(context)}. */
        SYSTEM_DEFAULT,
        /** {@code createOnDeviceSpeechRecognizer(context)}. */
        ON_DEVICE,
        /** {@code createSpeechRecognizer(context, component)}. */
        COMPONENT
    }

    /** The setting resolved against what is installed. */
    public static final class Selection {
        public static final Selection DEFAULT = new Selection(Kind.SYSTEM_DEFAULT, null, null, null);

        public static final Selection DEVICE = new Selection(Kind.ON_DEVICE, null, null, null);

        public final Kind kind;
        /** The flattened component, for {@link Kind#COMPONENT} only. */
        public final String component;
        public final String packageName;
        /** The chosen app's label, for {@link Kind#COMPONENT} only. */
        public final String label;

        private Selection(Kind kind, String component, String packageName, String label) {
            this.kind = kind;
            this.component = component;
            this.packageName = packageName;
            this.label = label;
        }

        static Selection of(Provider provider) {
            return new Selection(Kind.COMPONENT, provider.component, provider.packageName, provider.label);
        }

        /** The setting value this selection stands for: "", {@link #ON_DEVICE}, or the component. */
        public String id() {
            switch (this.kind) {
                case ON_DEVICE:
                    return ON_DEVICE;
                case COMPONENT:
                    return this.component;
                default:
                    return SYSTEM_DEFAULT;
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Selection && ((Selection) other).id().equals(id());
        }

        @Override
        public int hashCode() {
            return id().hashCode();
        }

        @Override
        public String toString() {
            return this.kind == Kind.COMPONENT ? this.component : this.kind.name();
        }
    }

    /**
     * {@code package/Class} with a ".Class" shorthand expanded, or null for anything that is not a
     * component name. The secure setting and our own setting can each be written either way.
     */
    static String normaliseComponent(String flattened) {
        if (flattened == null) {
            return null;
        }
        final String value = flattened.trim();
        final int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            return null;
        }
        final String pkg = value.substring(0, slash);
        String cls = value.substring(slash + 1);
        if (cls.startsWith(".")) {
            cls = pkg + cls;
        }
        return pkg + "/" + cls;
    }

    /**
     * The list the setting offers, sorted by label. Two services from one app would show the same
     * label, so those get their class's short name after it.
     *
     * @param defaultService the secure {@code voice_recognition_service} value, or null
     */
    public static List<Provider> buildProviders(List<Service> services, String defaultService) {
        if (services == null || services.isEmpty()) {
            return Collections.emptyList();
        }
        final String defaultComponent = normaliseComponent(defaultService);
        final List<Service> valid = new ArrayList<>();
        final List<String> seen = new ArrayList<>();
        final Map<String, Integer> labelCounts = new HashMap<>();
        for (Service service : services) {
            if (service == null || isBlank(service.packageName) || isBlank(service.className)) {
                continue;
            }
            final String component = normaliseComponent(service.packageName + "/" + service.className);
            if (component == null || seen.contains(component)) {
                continue;
            }
            seen.add(component);
            valid.add(service);
            final String label = baseLabel(service);
            labelCounts.put(label, labelCounts.containsKey(label) ? labelCounts.get(label) + 1 : 1);
        }
        final List<Provider> providers = new ArrayList<>();
        for (Service service : valid) {
            final String component = normaliseComponent(service.packageName + "/" + service.className);
            String label = baseLabel(service);
            if (labelCounts.get(label) > 1) {
                label = label + " (" + shortClassName(service.className) + ")";
            }
            providers.add(new Provider(component, service.packageName, label, component.equals(defaultComponent)));
        }
        Collections.sort(providers, (a, b) -> {
            final int byLabel = a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT));
            return byLabel != 0 ? byLabel : a.component.compareTo(b.component);
        });
        return providers;
    }

    /** The provider the phone's secure setting names, if it is installed. */
    public static Provider systemDefault(List<Provider> providers) {
        if (providers != null) {
            for (Provider provider : providers) {
                if (provider.isSystemDefault) {
                    return provider;
                }
            }
        }
        return null;
    }

    /**
     * What dictation is built with. A choice that is no longer possible (the app was uninstalled,
     * or this phone has no on-device recogniser) falls back to the system default, which says
     * "No selected voice recognition service" itself when there is nothing left to bind.
     *
     * @param onDeviceAvailable {@code SpeechRecognizer.isOnDeviceRecognitionAvailable}; only read
     *        for {@link #ON_DEVICE} on API 31 and up
     * @param installed the recognition services installed now
     */
    public static Selection select(String setting, int sdkInt, boolean onDeviceAvailable, List<Provider> installed) {
        if (isBlank(setting)) {
            return Selection.DEFAULT;
        }
        if (ON_DEVICE.equals(setting.trim())) {
            return sdkInt >= ON_DEVICE_MIN_SDK && onDeviceAvailable ? Selection.DEVICE : Selection.DEFAULT;
        }
        final String component = normaliseComponent(setting);
        if (component != null && installed != null) {
            for (Provider provider : installed) {
                if (component.equals(provider.component)) {
                    return Selection.of(provider);
                }
            }
        }
        return Selection.DEFAULT;
    }

    /** The voice picker's language-cache key for recogniser {@code selectionId} (see {@link Selection#id()}). */
    public static String languageCacheKey(String selectionId) {
        return isBlank(selectionId) ? LANGUAGE_CACHE_KEY : LANGUAGE_CACHE_KEY + "_" + selectionId.trim();
    }

    /** What to do when the recogniser answers {@code ERROR_INSUFFICIENT_PERMISSIONS}. */
    public enum PermissionErrorAction {
        /** Ask for this keyboard's own microphone permission, as before there was a choice. */
        REQUEST_OWN_PERMISSION,
        /** The chosen app has no microphone permission: "<app> needs microphone permission". */
        APP_NEEDS_PERMISSION,
        /**
         * The chosen app has the microphone and still refused: it will not serve this keyboard
         * ("<app> did not allow dictation"). Claude's service did this on the KEY2 (2026-10-08).
         */
        APP_REFUSED
    }

    /**
     * Who the permission error is about. The error does not say: a service sends it when it lacks
     * the microphone itself, and also when it refuses the app that called it. So a chosen app is
     * blamed for its microphone only when its own grant says it lacks one; when it has one, or that
     * cannot be checked, it refused us. Nothing is put on a chosen app while this keyboard lacks the
     * permission, because the service checks its caller too, and asking for ours comes first.
     *
     * @param appPermissionGranted the chosen app's own microphone grant, or null when it could not be
     *        checked
     */
    public static PermissionErrorAction permissionErrorAction(Selection selection, boolean ownPermissionGranted,
            Boolean appPermissionGranted) {
        if (selection == null || selection.kind != Kind.COMPONENT || !ownPermissionGranted) {
            return PermissionErrorAction.REQUEST_OWN_PERMISSION;
        }
        return Boolean.FALSE.equals(appPermissionGranted)
                ? PermissionErrorAction.APP_NEEDS_PERMISSION
                : PermissionErrorAction.APP_REFUSED;
    }

    /** The name a message about the chosen app uses: its label, or its package without one. */
    public static String appName(Selection selection) {
        if (selection == null) {
            return null;
        }
        return isBlank(selection.label) ? selection.packageName : selection.label;
    }

    private static String baseLabel(Service service) {
        return isBlank(service.appLabel) ? service.packageName : service.appLabel.trim();
    }

    private static String shortClassName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot >= 0 && dot < className.length() - 1 ? className.substring(dot + 1) : className;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
