# App-specific keep rules belong here. Room and Compose publish consumer rules.

# sherpa-onnx exposes JNI methods from these Kotlin classes. Renaming them breaks
# native method lookup in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# R8 may remove or rename the invoke() method on lambda classes that are
# passed to native JNI callbacks (OfflineTts.generateWithConfigAndCallback).
# The native code resolves invoke() by signature at runtime via JNI GetMethodID,
# so the method name and signature must be preserved across all Function1
# implementations used in the app.
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    *** invoke(...);
}
