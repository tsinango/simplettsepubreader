# App-specific keep rules belong here. Room and Compose publish consumer rules.

# sherpa-onnx exposes JNI methods from these Kotlin classes. Renaming them breaks
# native method lookup in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Bert-VITS2-MNN JNI classes
-keep class com.example.bertvits2.** { *; }
-keep class com.example.cppjieba.** { *; }
-keep class com.example.cpptokenizer.** { *; }

