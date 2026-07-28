# R8/ProGuard rules for release builds (minifyEnabled true).
#
# The critical concern here is Gson. All persisted state — per-app FeedbackConfig and
# the NotificationLog history — is stored as JSON whose keys ARE the Kotlin field
# names. If R8 renames those fields, two things break:
#   1. Saved settings and history become unreadable, and
#   2. field names can change between releases, silently orphaning user data on update.
# So the model classes below are kept verbatim, names included.

# --- Gson ---------------------------------------------------------------------
# Generic signatures must survive for TypeToken<List<NotificationLog>> to resolve;
# without this, Gson deserializes into LinkedTreeMap and blows up with a
# ClassCastException at runtime.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses, EnclosingMethod

-dontwarn sun.misc.**

# TypeToken relies on its own generic superclass being introspectable.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Honour @SerializedName if it is ever introduced.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Serialized model classes -------------------------------------------------
# Field names are the on-disk JSON schema: keep them exactly.
-keep class com.notifylter.app.FeedbackConfig { *; }
-keep class com.notifylter.app.NotificationLog { *; }

# --- Fragments ----------------------------------------------------------------
# FragmentManager recreates fragments reflectively by class name. Manifest-declared
# components are kept by AGP automatically, but fragments are not.
-keep public class * extends androidx.fragment.app.Fragment

# --- Kotlin -------------------------------------------------------------------
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
