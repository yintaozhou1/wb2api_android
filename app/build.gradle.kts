plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.wb.api"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.wb.api"
        minSdk = 32
        targetSdk = 37
        versionCode = 48
        versionName = "1.6.9"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 只保留中文 / 英文资源（单 APK 分发，不需要其它语言）：作用与原先 defaultConfig 里的
    // `resourceConfigurations` 相同，但那一个在 AGP 9 已**弃用**（AGP 10 会移除：
    // "Support for resource configurations will be removed"），原话给的替代就是
    // androidResources.localeFilters。这里改用新 API，既消除构建警告也避免将来被移除时踩空。
    androidResources {
        localeFilters += listOf("zh", "en")
    }

    // 发布版签名凭据**不写进仓库**（源码公开在 GitHub 时尤为重要：密钥文件与口令
    // 一旦提交，任何人都能伪造同签名 APK）。改为从 local.properties 或同名环境变量读取；
    // 缺省时不配置签名 —— AGP 会产出 `app-release-unsigned.apk`，公开源码依然可直接构建。
    //
    // 本地/CI 出正式包时，在 local.properties（已被 .gitignore 忽略）写入：
    //   RELEASE_STORE_FILE=keystore/your.jks
    //   RELEASE_STORE_PASSWORD=...
    //   RELEASE_KEY_ALIAS=...
    //   RELEASE_KEY_PASSWORD=...
    // 说明：这里**手写**解析 local.properties（而非 java.util.Properties）—— 在 Gradle 的
    // android { } DSL 作用域内 `java` 会解析成 JavaPluginExtension，`java.util.Properties`
    // 会直接编译不过（Unresolved reference 'util'）。
    val signingMap: Map<String, String> = run {
        val f = rootProject.file("local.properties")
        if (!f.exists()) emptyMap()
        else f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    }
    fun signingSecret(key: String): String? =
        (signingMap[key] ?: System.getenv(key))?.takeIf { it.isNotBlank() }
    val releaseStoreFile = signingSecret("RELEASE_STORE_FILE")
    val hasReleaseSigning = releaseStoreFile != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingSecret("RELEASE_STORE_PASSWORD")
                keyAlias = signingSecret("RELEASE_KEY_ALIAS")
                keyPassword = signingSecret("RELEASE_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 未配置签名凭据时留空（产出未签名包），避免公开源码在没有密钥的环境下构建失败。
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 不把「依赖签名信息」写进 APK：AGP 默认会塞一块 DEPENDENCY 元数据（列出全部依赖 +
    // 签名摘要），去除它体积更小、也不在包内暴露完整依赖清单。本应用不发布到 Google Play，
    // 不需要保留该块（该块仅用于 Play 的依赖漏洞检查）。
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            // 显式声明「不压缩原生库」：.so 直接以未压缩形式随包安装（loadLibrary 时无需解压），
            // 也是 16 KB 页对齐（Android 15+ 要求，Google Play 2025-11 起强制）的前提。
            // AGP 在 minSdk 32 下默认值已是 false，这里写死以免将来 minSdk/AGP 变化时被静默改回。
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/versions/9/**"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4")

    // 真·液态玻璃：Kyant0/AndroidLiquidGlass（Backdrop）——AGSL 实时折射（lens）
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")

}
