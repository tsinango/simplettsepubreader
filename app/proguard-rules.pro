# App-specific keep rules belong here. Room and Compose publish consumer rules.

# sherpa-onnx exposes JNI methods from these Kotlin classes. Renaming them breaks
# native method lookup in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# GenerationCancellationCallback is passed to native JNI code (OfflineTts JNI callback).
# R8 must not rename its invoke() method because the native library resolves it by
# signature at runtime via JNI GetMethodID.
-keep class com.example.epubreader.tts.ReaderTtsService$GenerationCancellationCallback { *; }
