# R8 rules: only what R8 cannot see. Manifest components are kept by AGP's generated rules and
# libraries ship consumer rules. No blanket package keeps: they defeat shrinking and merging.

# Only what proguard-android-optimize.txt does not already keep.
-keepattributes SourceFile, LineNumberTable

# Repackaging makes Class.getPackage() return null, crashing static initializers that call
# SomeClass.class.getPackage().getName().
-keeppackagenames com.blackberry.**

# libnative-lib.so resolves NuanceSDK, KeyInfo and WordInfo by name and reads their fields and
# constructors; app/src/main/cpp defines Java_com_blackberry_nuanceshim_* entry points.
-keep class com.blackberry.nuanceshim.** { *; }
-keepclassmembers class com.blackberry.nuanceshim.** { *; }

# Native upcall.
-keepclassmembers class com.blackberry.nuanceshim.NuanceSDK {
    void updateAutoCommittedString(java.lang.String);
}

# Other classes with native methods keep their names through AGP's default native-methods rule.

# Broader than AGP's own view rules: keeps every public View subclass whole, used or not.
# Dropping it shrinks the dex but changes how R8 optimises the app, so it needs a full release
# pass on device first.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
    public *** get*();
}

# Broader than AGP's default in the same way (pins only library classes beyond it).
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

# CustomDeviceConfigManager enumerates R.xml.device_config_* by reflection.
-keepclassmembers class dev.bbkb.ime.R$xml {
    public static <fields>;
}

-keep public class * extends java.lang.Exception

# The platform reflects on the IME service for @hide hooks; keep it whole.
-keep class * extends android.inputmethodservice.InputMethodService { *; }

# Shizuku starts EvdevUserService in its own process by class name and instantiates it
# reflectively ((Context), then no-arg). Nothing in the app calls either constructor.
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevUserService {
    public <init>();
    public <init>(android.content.Context);
}
# The AIDL contract with that process, kept whole so the transaction table (including Shizuku's
# reserved destroy() code) stays out of the optimiser's reach.
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevService { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevService$* { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevCallback { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevCallback$* { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevDeviceInfo { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevOpenResult { *; }
# libbbkbevdev.so binds EvdevNative by name; explicit because the class is reachable only from
# the reader process.
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevNative {
    native <methods>;
}

# ComposingTextTracker instantiates converters with Class.newInstance().
-keepclassmembers class * implements dev.bbkb.ime.core.inputmethod.InputMethodCallback {
    <init>();
}

# Enum values are serialised by name.
-keepclassmembers enum * { *; }

-keep @interface *

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

-assumenosideeffects class dev.bbkb.ime.core.shared.Logger {
    public static int debug(...);
}

-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
