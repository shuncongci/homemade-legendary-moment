# Basic class-name obfuscation; retain explicit JNI and component contracts below.
-repackageclasses 'a'
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable,!SourceDebugExtension,!LocalVariableTable,!LocalVariableTypeTable
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
