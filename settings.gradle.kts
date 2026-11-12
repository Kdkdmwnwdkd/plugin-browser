pluginManagement {
    repositories {
        // 国内镜像优先，加速 Google/阿里等仓库依赖下载
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        // GeckoView 只发布在 Mozilla 自己的仓库，Maven Central 上搜不到。
        // 这一行是 gecko 分支独有的：main 上没有任何依赖需要它。
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "PluginBrowser"

include(":app")
include(":plugin-api")
include(":sample-plugin")
