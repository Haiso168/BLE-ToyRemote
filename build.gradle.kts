plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 起启用 Compose 必须显式应用 Compose Compiler 插件，
    // 且版本号必须与 Kotlin 版本严格一致。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
