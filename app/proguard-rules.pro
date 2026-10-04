# Xposed / libxposed entry points must survive R8.
-keep class moe.yanhe.xmsound.hook.** { *; }
-keep class * extends io.github.libxposed.api.XposedModule { *; }
-keepclassmembers class * extends io.github.libxposed.api.XposedModule { <init>(...); }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class moe.yanhe.xmsound.** {
    *** Companion;
}
-keepclasseswithmembers class moe.yanhe.xmsound.** {
    kotlinx.serialization.KSerializer serializer(...);
}
