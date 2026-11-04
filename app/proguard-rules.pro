# Clean-room Kotlin project.

# Reflection-sensitive code here lives in libraries that ship their own rules,
# merged automatically by AGP:
#   - tink-android 1.20.0: META-INF/proguard/protobuf.pro keeps the fields of
#     its shaded protobuf messages (their parsers introspect them reflectively).
#   - gson 2.13.2: META-INF/proguard/gson.pro keeps @SerializedName fields,
#     TypeToken signatures and adapter constructors.
# The old app-level "-keep class com.google.crypto.tink.** { *; }" copied from
# early Tink docs kept ~1900 classes; current Tink registers its managers
# statically, so that blanket rule is no longer needed and was removed as
# overly broad. com.google.protobuf, com.google.api and org.joda are not on
# the runtime classpath at all anymore.
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
