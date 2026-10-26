plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pluginbrowser.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pluginbrowser.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
    // 仅编译期依赖宿主接口：运行时不打包 plugin-api，由宿主 App 提供类
    compileOnly(project(":plugin-api"))
}
