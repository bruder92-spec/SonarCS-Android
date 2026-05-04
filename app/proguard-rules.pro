-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep JNI bridge for ONNX native libraries
-keep class com.microsoft.onnxruntime.** { *; }
