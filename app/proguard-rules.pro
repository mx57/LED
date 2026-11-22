# Services, receivers, activities and XML-inflated views are kept via manifest/aapt rules,
# but explicit rules ensure R8 optimizer doesn't strip constructors, reflection targets, or data classes.

# Preserve line numbers and source files for readable stacktraces in DebugLog and issue reports
-keepattributes SourceFile,LineNumberTable

# Custom Views instantiated programmatically or from XML
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Explicitly preserve all BroadcastReceivers and Services
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }

# Data models used in updater, battery telemetry, and notification store
-keep class com.glowled.app.core.Updater$Release { *; }
-keep class com.glowled.app.core.BatteryInfo { *; }
-keep class com.glowled.app.core.NotificationStore$Entry { *; }
-keep class com.glowled.app.core.NotificationStore$AppEntry { *; }

# Preserve Enums used in settings (e.g. LedStyle, CutoutShape, MultiMode)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
