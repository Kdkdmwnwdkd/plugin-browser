plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pluginbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pluginbrowser"
        minSdk = 24
        targetSdk = 34
        // gecko 分支专用版本号：跟 main 的 2.11.x 明确分开，
        // 装上之后一眼能看出这是原型包，不是正式版。
        versionCode = 56
        versionName = "2.12.0-gecko.1"

        // GeckoView 的 AAR 里带 arm64-v8a / armeabi-v7a / x86 / x86_64 四套 .so，
        // 不裁的话 APK 直接 150MB+。原型只留 arm64——2017 年之后的手机基本都是。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // 固定签名：keystore/kite.jks 就是历史上一直在用的那把调试钥匙，
        // 目的是让以后任何环境下打出来的包都能覆盖安装，不会因为签名变了丢数据。
        create("kite") {
            storeFile = rootProject.file("keystore/kite.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("kite")
        }
        release {
            signingConfig = signingConfigs.getByName("kite")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":plugin-api"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // GitHub 通道要用自定义 DNS（DoH）和代理，WebView 自带的网络栈这两样都改不了
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 二维码：只取 core（纯 Java 编码器），不要 android 那个包——它带一堆 Activity
    implementation("com.google.zxing:core:3.5.3")

    // GeckoView：Firefox 的内核。这一行是 gecko 分支存在的全部理由——
    // 用它就不再依赖手机里那个 WebView，内核版本由我们自己决定。
    //
    // 为什么钉在 135 而不是最新的 160：从 140 起 GeckoView 改用 kotlin-stdlib 2.x 发布，
    // 而我们项目的 Kotlin 编译器还是 1.9.24，读不了 2.x 的元数据，会直接编译失败。
    // 135 是本项目语言版本能用的最新一版（2025-02，Firefox 135）。
    // 等 Kotlin 升到 2.x 再往上走。
    implementation("org.mozilla.geckoview:geckoview:135.0.20250216192613")
}
