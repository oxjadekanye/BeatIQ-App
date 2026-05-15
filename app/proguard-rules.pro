# BeatIQ release (R8)

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# OkHttp / Conscrypt (used by OkHttp on some devices)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**

# Coil
-keep class coil.util.** { *; }

# Room — runtime keeps generated impl; keep schema if you export it
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Media3 / playback service
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class com.beatiq.music.services.playback.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Jetpack Compose (generated / runtime)
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**
