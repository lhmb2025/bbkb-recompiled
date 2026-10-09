# R8 rules for BlackBerry Keyboard
#
# Only rules that protect something R8 cannot see belong here. Manifest-declared
# components (the IME service, KeyInterceptorService, AndroidSpellCheckerService,
# the receivers, the activities and FileProvider) are kept by AGP's generated
# rules; library-internal requirements are covered by the consumer rules the
# libraries ship. Do not add blanket package keeps — they defeat shrinking,
# inlining and class merging wholesale.

# ---------------------------------------------------------------------------
# Attributes (one consolidated block)
# ---------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
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

# Any class declaring native methods keeps its name so the JNI symbol resolves.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Framework contracts R8 cannot infer
# ---------------------------------------------------------------------------
# Views inflated from XML need their (Context, AttributeSet) constructors, and
# animated properties are set by name.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
    public *** get*();
}

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
# by name (also covered by the generic native-methods rule above; explicit here
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

# ---------------------------------------------------------------------------
# Gson-deserialised models (field names are the wire format)
# ---------------------------------------------------------------------------
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# These classes are only ever instantiated by Gson (reflectively), so nothing in the code
# constructs them. A members-only rule is not enough: R8 sees a class that is never
# instantiated, drops its fields and the class, and the emoji board came up empty in the
# first release builds (5.0.0-beta.18..20) while debug builds were fine. Keep the classes
# themselves, nested skin/variant classes included.
-keep class dev.bbkb.ime.keyboard.inputboard.emoji.EmojiData { *; }
-keep class dev.bbkb.ime.keyboard.inputboard.emoji.EmojiData$* { *; }
-keep class dev.bbkb.ime.personaldictionary.model.** { *; }

# Enum values are serialised by name.
-keepclassmembers enum * { *; }

# ---------------------------------------------------------------------------
# Annotations
# ---------------------------------------------------------------------------
-keep @interface *
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ---------------------------------------------------------------------------
# Logging strip-out
# ---------------------------------------------------------------------------
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

-assumenosideeffects class dev.bbkb.ime.core.shared.Logger {
    public static void debug(...);
}

# ---------------------------------------------------------------------------
# Warnings
# ---------------------------------------------------------------------------
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
