buildscript {
    // 与 node-bridge 相同：发布插件 classpath（见 node-bridge/build.gradle.kts 注释）
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.33.0")
    }
}

plugins {
    alias(libs.plugins.android.library)
}

// ─────────────────────────────────────────────────────────────────────────────
// node-runtime：**只装引擎**（libnode.so + 可选 libc++_shared.so + 版本常量）。
// 版本号 = Node 版本号（由 tools/provision.mjs 按 runtime.lock.json 注入）。
// 二进制不入库：src/main/jniLibs/ 由 provision.mjs 投放。
// ─────────────────────────────────────────────────────────────────────────────

apply(from = rootProject.file("publish.gradle.kts"))

// node-runtime 版本 = Node 版本（NODE_VERSION）：发布插件默认取 project.version，
// 这里显式钉死（tools/provision.mjs 保证 NODE_VERSION 与 runtime.lock.json 一致）。
version = (providers.gradleProperty("NODE_VERSION").orNull
    ?: error("node-runtime 需要 NODE_VERSION（= Node 版本）；当前未设置"))
afterEvaluate {
    // 发布坐标的 version 在 publication 上：插件求值期已按当时的 project.version 定值，
    // 而本模块 project.version 在 apply 脚本之后才被设为 NODE_VERSION，故此处同步。
    val publishing = extensions.getByType(org.gradle.api.publish.PublishingExtension::class.java)
    publishing.publications.forEach { pub ->
        (pub as Any).javaClass.methods
            .first { it.name == "setVersion" && it.parameterCount == 1 }
            .invoke(pub, version.toString())
    }
}

android {
    namespace = "org.fellow99.noderuntime"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // 不抽取原生库（AGP 8+ 默认）：直接从 APK 映射，`System.loadLibrary` / dlopen 均可用
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
}
