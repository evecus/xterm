pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SshTerm"
include(":app")
// termlib 0.3.14 本地源码 module：需要定制选区手柄颜色（原 AAR 硬编码白色）。
include(":termlib")
