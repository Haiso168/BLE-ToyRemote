// Gradle 的 Kotlin DSL 脚本默认只导入 java.lang 和少量 Gradle API，
// 所以用到 JDK 里的类必须显式 import，否则会报 "Unresolved reference: util"。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose 编译器由这个插件提供，必须应用
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// release 签名配置
//
// 优先级：
//   1. 本地 keystore.properties（把自己电脑上的 keystore 路径/密码写进去，不提交）
//   2. 环境变量（CI 用 GitHub Secrets 注入）
//   3. 都没有 -> release 不签名（构建仍能跑，但产物无法安装）
//
// 关键：签名必须**固定不变**。Android 靠签名判断"是不是同一个 App"，
// 换了签名就无法覆盖安装，只能卸载重装（数据全丢）。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // 用 use{} 保证流被关闭；Properties.load 需要显式接收方，否则会被当成局部函数
        keystorePropsFile.inputStream().use { stream -> load(stream) }
    }
}

fun signingValue(key: String, envName: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(envName)

val releaseStoreFile = signingValue("storeFile", "YCM_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "YCM_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "YCM_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "YCM_KEY_PASSWORD")

val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStoreFile).exists()

android {
    namespace = "com.ycm.remote"
    compileSdk = 34

    // 版本号集中在根目录 gradle.properties 里维护（ycm.versionCode / ycm.versionName）
    val appVersionCode: Int = (project.findProperty("ycm.versionCode") as String?)?.toInt() ?: 1
    val appVersionName: String = (project.findProperty("ycm.versionName") as String?) ?: "1.0"

    defaultConfig {
        applicationId = "com.ycm.remote"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // 同时启用 v1/v2/v3 签名，兼容老设备
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 便于在标题栏区分"哪个版本在跑"
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"debug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"release\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 编译器级 opt-in，避免到处写 @OptIn。
        // 注意：函数上的 @OptIn 不会穿透到 lambda 字面值里
        //（例如传给 SectionCard 的 content），所以这类"实验性 API"
        // 用全局 opt-in 更省事、也不会漏。
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlin.ExperimentalStdlibApi",
        )
    }

    buildFeatures {
        compose = true
        // 需要读取 BuildConfig.VERSION_NAME 显示当前版本
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // collectAsStateWithLifecycle 位于此包
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // 底部导航与按钮图标需要扩展图标库
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
