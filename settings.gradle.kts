// aosp-node-bridge —— Gradle 多模块工程
//
// 说明：本机 maven.google.com / dl.google.com 不可达，故镜像前置（阿里云）；
//      仓库顺序对 CI 无害（镜像 404 时自动回退到官方仓库）。

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "aosp-node-bridge"

include(":node-bridge")
include(":node-runtime")
include(":samples:minimal-app")
