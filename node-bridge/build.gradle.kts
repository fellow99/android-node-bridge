plugins {
    alias(libs.plugins.android.library)
    // 注意：**不应用** org.jetbrains.kotlin.android —— AGP 9 自带 Kotlin 支持（builtInKotlin 默认 true），
    // 再应用 KGP 会与之争抢名为 `kotlin` 的 extension。
}

android {
    namespace = "org.fellow99.nodebridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 两个「生成物落进默认 source set」的任务（都不入库，见 .gitignore）：
//   src/main/jniLibs/arm64-v8a/*.so      ← tools/build-bridge.mjs（NDK clang）
//   src/main/assets/node-bridge/*.js     ← payload/（唯一事实源）
// 不用 sourceSets.srcDir 覆盖：AGP 9 的 Kotlin DSL 里该写法类型不兼容（见构建排障）。
// ─────────────────────────────────────────────────────────────────────────────

val buildBridgeNative = tasks.register<Exec>("buildBridgeNative") {
    group = "build"
    description = "交叉编译 libnode_bridge.so 与 libnode_host_client.so（NDK clang 直调）"
    workingDir = rootProject.projectDir
    commandLine(
        "node", "tools/build-bridge.mjs",
        "--out", file("src/main/jniLibs/arm64-v8a").absolutePath,
        "--abi", "arm64-v8a",
    )
    outputs.dir(file("src/main/jniLibs/arm64-v8a"))
}

val syncPayloadAssets = tasks.register<Sync>("syncPayloadAssets") {
    group = "build"
    description = "把 payload/*.js 同步到 assets/node-bridge/"
    from(rootProject.file("payload")) { include("**/*.js") }
    into(file("src/main/assets/node-bridge"))
}

tasks.named("preBuild") {
    dependsOn(buildBridgeNative, syncPayloadAssets)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
