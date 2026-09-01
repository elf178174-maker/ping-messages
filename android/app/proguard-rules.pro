# ---------------------------------------------------------------------------
# Ping - R8 / ProGuard configuration
# ---------------------------------------------------------------------------

# Keep line numbers so crash reports from release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# --- kotlinx.serialization ---------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# --- Retrofit / OkHttp -------------------------------------------------------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# --- Room --------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger -----------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# --- Tink (crypto) -----------------------------------------------------------
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.concurrent.**

# --- WebRTC ------------------------------------------------------------------
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- Media3 / ExoPlayer ------------------------------------------------------
-dontwarn androidx.media3.**

# --- Project models ----------------------------------------------------------
# DTOs and Room entities are reflected over by serialization and Room codegen.
-keep class com.ping.messenger.data.remote.dto.** { *; }
-keep class com.ping.messenger.data.local.entity.** { *; }
-keep class com.ping.messenger.data.remote.ws.** { *; }

# Enum values are looked up by name during (de)serialization.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelables
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
