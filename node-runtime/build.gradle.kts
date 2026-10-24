plugins {
    alias(libs.plugins.android.library)
}

// ─────────────────────────────────────────────────────────────────────────────
// node-runtime：**只装引擎**（libnode.so + 可选 libc++_shared.so + 版本常量）。
// 版本号 = Node 版本号（由 tools/provision.mjs 按 runtime.lock.json 注入）。
// 二进制不入库：src/main/jniLibs/ 由 provision.mjs 投放。
// ─────────────────────────────────────────────────────────────────────────────

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
