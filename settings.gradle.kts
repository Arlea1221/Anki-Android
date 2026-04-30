pluginManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // TODO enforce repositories declared here, currently it clashes with robolectricDownloader.gradle
    //  which uses a local maven repository
    // repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://jitpack.io")
        google()
        mavenCentral()
    }
}

// alphabetical ordering rather than dependency-tree ordering to avoid bikeshedding
include(
    ":api",
    ":AnkiDroid",
    ":common",
    ":common:android",
    ":compat",
    ":libanki",
    ":lint-rules",
    ":vbpd",
)