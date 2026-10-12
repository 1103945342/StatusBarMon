# Xposed API 由 LSPosed 在运行时提供（compileOnly），不随 APK 打包。
-dontwarn de.robv.android.xposed.**

# 入口类名被 assets/xposed_init 按全名引用，不能被混淆或裁剪。
-keep class com.aimini.statusbarmon.xposed.XposedEntry { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }

# 注入到 SystemUI 进程的视图会在运行时被反射/布局系统使用，保留类结构。
-keep class com.aimini.statusbarmon.xposed.StatusBarMonitorView { *; }
-keep class com.aimini.statusbarmon.xposed.CircleGaugeView { *; }
-keep class com.aimini.statusbarmon.xposed.StatusBarInjector { *; }

# 保留枚举与注解信息，避免 R8 优化影响 XSharedPreferences 读取。
-keepclassmembers enum * { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
