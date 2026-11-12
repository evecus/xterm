// termlib 0.3.14 本地源码 module。
//
// 为什么不再直接用 Maven 上的 org.connectbot:termlib:0.3.14：
// 该版本把选区手柄颜色硬编码为 Color.White（Terminal.kt 的 drawSelectionHandle 默认参数），
// 浅色主题下白色手柄与白色终端背景融为一体，看起来「没有拖动条」；0.3.15 源码与 0.3.14
// 完全一致，也没有暴露任何手柄颜色 API。把源码纳入工程后才能干净地补上可配置颜色，
// 同时完整保留库自带的长按选区 / HandleDrag 拖动手势 / 放大镜 / 全选粘贴工具条。
//
// 依赖版本严格对齐原 AAR 的 POM（Compose BOM 2026.09.00、Kotlin 2.4.21），
// 源码、res、jniLibs、consumer proguard 规则均从 termlib-0.3.14.aar 原样复制。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // 4 个 ABI 的 libjni_cb_term.so 放在默认路径 src/main/jniLibs/，
    // 由 app 的 splits 按 ABI 过滤后打进 APK。
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    api("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.runtime:runtime")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
