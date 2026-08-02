# Add project specific ProGuard rules here.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pennypilot.app.**$$serializer { *; }
-keepclassmembers class com.pennypilot.app.** { *** Companion; }
-keepclasseswithmembers class com.pennypilot.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Google Crypto / Tink (EncryptedSharedPreferences)
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
