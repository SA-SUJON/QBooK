# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep WebView related classes
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

# Keep ViewModel classes
-keep class org.qbook.viewmodel.** { *; }

# Keep Application class
-keep class org.qbook.QBooKApplication { *; }

# Keep MainActivity
-keep class org.qbook.ui.MainActivity { *; }

# WebView JavaScript interface if any
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep for download manager
-keep class androidx.core.content.FileProvider { *; }

# Material Design 3
-dontwarn com.google.android.material.**

# Lifecycle
-keep class androidx.lifecycle.** { *; }

# OkHttp and other networking (if used)
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
# --- Dustbook additions ---
# JavaScript bridge used for blob: downloads
-keepclassmembers class org.qbook.ui.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class org.qbook.utils.** { *; }
-keep class org.qbook.ui.SettingsActivity$HiddenSettingsFragment { *; }
-keep class * extends androidx.preference.Preference { *; }
