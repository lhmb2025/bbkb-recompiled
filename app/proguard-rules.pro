# R8 rules for BlackBerry Keyboard
#
# Only rules that protect something R8 cannot see belong here. Manifest-declared
# components (the IME service, KeyInterceptorService, AndroidSpellCheckerService,
# the receivers, the activities and FileProvider) are kept by AGP's generated
# rules; library-internal requirements are covered by the consumer rules the
# libraries ship. Do not add blanket package keeps — they defeat shrinking,
# inlining and class merging wholesale.

# ---------------------------------------------------------------------------
# Attributes
# ---------------------------------------------------------------------------
# Only what AGP's default file (proguard-android-optimize.txt) does not already keep. It
# keeps Signature, InnerClasses, EnclosingMethod, AnnotationDefault and the RuntimeVisible*
# annotation attributes; listing them here again, or `*Annotation*`, changes nothing in the
# output (checked 2026-11-05: byte-identical dex).
-keepattributes SourceFile, LineNumberTable

# ---------------------------------------------------------------------------
# Package names
# ---------------------------------------------------------------------------
# R8's repackageclasses optimization causes Class.getPackage() to return null,
# which crashes any static initializer that calls
# SomeClass.class.getPackage().getName().
-keeppackagenames com.blackberry.**

# ---------------------------------------------------------------------------
# JNI boundary
# ---------------------------------------------------------------------------
# libnative-lib.so resolves com/blackberry/nuanceshim/{NuanceSDK,KeyInfo,WordInfo}
# by name and reads their fields/constructors directly, and app/src/main/cpp
# defines Java_com_blackberry_nuanceshim_* entry points for Xt9Kdb,
# Xt9KdbVariant, Et9Probe and Xt9Trace. The whole package must survive intact.
-keep class com.blackberry.nuanceshim.** { *; }
-keepclassmembers class com.blackberry.nuanceshim.** { *; }

# Called from native code (upcall).
-keepclassmembers class com.blackberry.nuanceshim.NuanceSDK {
    void updateAutoCommittedString(java.lang.String);
}

# Every other class that declares native methods keeps its name, so the JNI symbol
# resolves, through AGP's default rule (-keepclasseswithmembernames ... native <methods>).

# ---------------------------------------------------------------------------
# Framework contracts R8 cannot infer
# ---------------------------------------------------------------------------
# Views inflated from XML need their (Context, AttributeSet) constructors, and
# animated properties are set by name.
# NOT a copy of the build's own rules, although it looks like one. AAPT keeps the
# constructors of every view named in a layout and AGP's default file keeps one-argument
# setters and getters, but only on views that are in use; this rule also keeps every
# public View subclass whole and under its own name, used or not. Measured 2026-11-05:
# without it the dex is 671 KB smaller (494 fewer classes, mostly unused Material,
# ConstraintLayout and window-layout code) and R8 optimises the whole app differently,
# so dropping it needs a full release pass on the KEY2. Left in place on purpose.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
    public *** get*();
}

# Same story as the View rule: AGP's default keeps CREATOR on the Parcelables that are in
# use, this one also pins every Parcelable class and its name. Measured 2026-11-05: without
# it the dex is 48 KB smaller; the 74 classes it alone holds are all library classes
# (androidx, Material, Shizuku), none of the app's. Left in place for the same reason.
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

# CustomDeviceConfigManager.findMatchingPreloadedConfig() enumerates
# R.xml.device_config_* via reflection over the generated R$xml fields.
-keepclassmembers class dev.bbkb.ime.R$xml {
    public static <fields>;
}

-keep public class * extends java.lang.Exception

# The IME service is reflected on by the platform for a few @hide hooks and is
# the process entry point; keep it whole rather than reasoning about each hook.
-keep class * extends android.inputmethodservice.InputMethodService { *; }

# ---------------------------------------------------------------------------
# Shizuku evdev touch reader (dev.bbkb.ime.core.device.touch.shizuku)
# ---------------------------------------------------------------------------
# Shizuku's server starts EvdevUserService in its own app_process by CLASS NAME
# (the ComponentName built in RealShizukuFacade) and instantiates it reflectively,
# trying the (Context) constructor and then the no-arg one. Nothing in the app
# calls either, so without this R8 would strip them and the reader process
# would fail to start in release builds only.
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevUserService {
    public <init>();
    public <init>(android.content.Context);
}
# The AIDL contract between the IME and that process: interfaces, Stub/Proxy and
# the structured parcelables. Both ends ship in this one APK, but keeping the
# generated binder classes whole keeps the transaction table (including Shizuku's
# reserved destroy() code) out of the optimiser's reach.
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevService { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevService$* { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevCallback { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.IEvdevCallback$* { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevDeviceInfo { *; }
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevOpenResult { *; }
# libbbkbevdev.so binds Java_dev_bbkb_ime_core_device_touch_shizuku_EvdevNative_*
# by name (also covered by AGP's default native-methods rule; explicit here
# because the class is otherwise only reachable from the reader process).
-keep class dev.bbkb.ime.core.device.touch.shizuku.EvdevNative {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Reflection inside the app
# ---------------------------------------------------------------------------
# ComposingTextTracker instantiates the registered converters with
# Class.newInstance(); their no-arg constructors must survive.
-keepclassmembers class * implements dev.bbkb.ime.core.inputmethod.InputMethodCallback {
    <init>();
}

# Enum values are serialised by name.
-keepclassmembers enum * { *; }

# ---------------------------------------------------------------------------
# Annotations
# ---------------------------------------------------------------------------
# @androidx.annotation.Keep needs nothing here: androidx.annotation ships its own rules.
-keep @interface *

# ---------------------------------------------------------------------------
# Logging strip-out
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

-assumenosideeffects class dev.bbkb.ime.core.shared.Logger {
    public static int debug(...);
}

# ---------------------------------------------------------------------------
# Warnings
# ---------------------------------------------------------------------------
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
