# Clean-room Kotlin project.

# Reflection-sensitive code here lives in libraries that ship their own rules,
# merged automatically by AGP:
#   - tink-android 1.20.0: META-INF/proguard/protobuf.pro keeps the fields of
#     its shaded protobuf messages (their parsers introspect them reflectively).
#   - kotlinx-serialization-json 1.12.0: ships rules for its serializers; the
#     app only uses JsonObject/JsonElement directly, so no app-level keeps are
#     needed (the old gson keep list went away with org.json).
# The old app-level "-keep class com.google.crypto.tink.** { *; }" copied from
# early Tink docs kept ~1900 classes; current Tink registers its managers
# statically, so that blanket rule is no longer needed and was removed as
# overly broad.
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**

# Ktor's OkHttp engine references desktop/JVM-only APIs (java.nio, javax certs)
# that never load on Android; R8 warns about them without any runtime impact.
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
