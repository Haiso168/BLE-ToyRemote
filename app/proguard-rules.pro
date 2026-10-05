# ===========================================================================
# YCM 遥控器 —— R8 / ProGuard 规则
#
# release 构建开启了 isMinifyEnabled + isShrinkResources。
# 本项目大量使用「系统通过反射或清单实例化」的组件，这类东西 R8 看不出来
# 被谁引用，会被误删，导致运行时才崩溃。所以必须显式保留。
# ===========================================================================

# ---------------------------------------------------------------------------
# 1. 清单里声明的组件（R8 不会自动分析 AndroidManifest）
#    AlarmService 会被 AlarmReceiver 用 Intent 启动，属于隐式引用
# ---------------------------------------------------------------------------
-keep class com.ycm.remote.alarm.AlarmReceiver { *; }
-keep class com.ycm.remote.alarm.AlarmService { *; }
-keep class com.ycm.remote.MainActivity { *; }

# ---------------------------------------------------------------------------
# 2. 自定义 View / Compose
# ---------------------------------------------------------------------------
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ---------------------------------------------------------------------------
# 3. Kotlin 协程
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# 4. AndroidX Lifecycle（ViewModel/StateFlow 相关有反射用法）
# ---------------------------------------------------------------------------
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---------------------------------------------------------------------------
# 5. FileProvider（清单里声明，authorities 用到 ${applicationId}）
# ---------------------------------------------------------------------------
-keep class androidx.core.content.FileProvider { *; }

# ---------------------------------------------------------------------------
# 6. 枚举：RingtoneKind 用 valueOf() 反序列化，R8 可能把枚举常量名改掉
# ---------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** fromString(java.lang.String);
}

# ---------------------------------------------------------------------------
# 7. 保留行号，便于崩溃日志定位（release 出问题时没有行号几乎没法查）
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 8. 序列化 / 反射相关的通用保留
# ---------------------------------------------------------------------------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ---------------------------------------------------------------------------
# 9. 抑制三方库缺失的可选依赖告警（否则 R8 可能直接构建失败）
# ---------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
