plugins {
    // AGP 9 起 Kotlin 支持内置，无需再应用 org.jetbrains.kotlin.android
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xterm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xterm"
        minSdk = 24          // 与 termlib 0.3.14 保持一致
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        // 只保留中文/英文资源，其余语言资源在精简时全部剔除
        androidResources {
            localeFilters += listOf("zh", "en")
        }
    }

    // termlib 的 AAR 自带 4 个 ABI 的 libjni_cb_term.so，按 ABI 拆分，
    // universalApk=false 时只产出单 ABI APK（含 arm64-v8a 一份，约 10MB）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")   // x86_64 供本地模拟器调试用，CI 上只取 arm64 产物
            isUniversalApk = false
        }
    }

    // 本机终端 / proot 需要真实 PTY：编译 app/src/main/cpp 下的 JNI 库。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // proot（libproot.so / libproot-loader.so）按绝对路径 exec，必须解压到
    // nativeLibraryDir，不能只映射进 APK；extractNativeLibs 也在 Manifest 中声明。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // workflow 把缺失的 Secrets 设为空字符串 ''，所以要用 isNullOrEmpty()
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val hasSigning = !keystorePassword.isNullOrEmpty()

    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
                storePassword = keystorePassword
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true        // R8 代码收缩 + 混淆
            isShrinkResources = true      // 无用资源剔除
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- ConnectBot 全家桶：Compose 终端模拟器 + SSH 协议栈 ----
    implementation("org.connectbot:termlib:0.3.14")
    implementation("org.connectbot:sshlib:2.2.23")

    // ---- Jetpack Compose（BOM 版本必须与 termlib 0.3.14 一致，否则二进制不兼容）----
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 各页面 1:1 复刻 Aurora 的 Material 图标（文件夹/上传/下载/系统信息等），
    // 版本由 BOM 统一管理。
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
