plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose 编译器由这个插件提供，必须应用
    id("org.jetbrains.kotlin.plugin.compose")
}

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

    buildTypes {
        debug {
            // 便于在设置页区分"哪个版本在跑"
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"debug\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "BUILD_TYPE_LABEL", "\"release\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
