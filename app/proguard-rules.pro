-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
}
-assumenosideeffects class icu.nullptr.polyglot.util.LoggerKt {
    public static void logV(...);
    public static void logD(...);
}
