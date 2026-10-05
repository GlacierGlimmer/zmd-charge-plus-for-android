# Endfield Charge Plus for Android — R8 rules.
-keepattributes SourceFile,LineNumberTable
-keep class com.glacierglimmer.endfieldchargeplus.core.model.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontwarn org.jetbrains.annotations.**

# Annotation-only classes referenced by Tink (transitively via androidx.security:security-crypto).
# They are compile-time annotations and are absent from the Android runtime, so R8 only needs to be
# told that they are not required.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
