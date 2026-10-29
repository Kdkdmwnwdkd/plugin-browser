plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pluginbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pluginbrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 41
        versionName = "2.8.3"
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
}
