plugins {
    alias(libs.plugins.android.application)
}

// ─────────────────────────────────────────────────────────────────────────────
// 探针工程（samples/minimal-app）
//
// 两个 flavor 是为 **P0-1（JIT/W^X）判定矩阵**准备的：
//   · modern → targetSdk 36（目标形态；Android 10+ 的 W^X 在 targetSdk≥29 时生效）
//   · legacy → targetSdk 28（对照实验：若 legacy 能 JIT 而 modern 不能，根因就是 W^X/execmem）
// 两个 flavor 的 applicationId 不同，可以同时装在一台机器上对照。
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "org.fellow99.nodebridge.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.fellow99.nodebridge.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "jitMatrix"
    productFlavors {
        create("modern") {
            dimension = "jitMatrix"
            targetSdk = libs.versions.targetSdkModern.get().toInt()
            applicationIdSuffix = ".modern"
            versionNameSuffix = "-modern"
        }
        create("legacy") {
            dimension = "jitMatrix"
            targetSdk = libs.versions.targetSdkLegacy.get().toInt()
            applicationIdSuffix = ".legacy"
            versionNameSuffix = "-legacy"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":node-bridge"))
    // 引擎包：只装 libnode.so（+ libc++ 共享库 + 版本常量资源），从 Maven Central 消费。
    // 用 runtimeOnly：bridge 编译期不依赖任何引擎符号（-z undefs），引擎可独立换版。
    runtimeOnly(libs.node.runtime)
}
