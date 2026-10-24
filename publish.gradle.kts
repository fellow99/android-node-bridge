// publish.gradle.kts —— 发布面共享配置（由 node-bridge / node-runtime 的 build.gradle.kts apply）。
//
// 插件：com.vanniktech:gradle-maven-publish-plugin 0.33.0
//   各模块 build.gradle.kts 的 buildscript 已把「AGP + 本插件」放进项目 classpath，
//   因此本脚本里可直接引用插件类型（Kotlin DSL）。
//
// 纪律（对齐 ohos-node-bridge 发布口径）：
//   - 敏感值（Portal Token / GPG）只从用户级 ~/.gradle/gradle.properties 读取，绝不入库
//   - RELEASE=false（默认）时 Central/签名任务被禁用，防误发
//   - 版本号：node-bridge = VERSION_NAME；node-runtime = NODE_VERSION（子模块 afterEvaluate 覆盖）
//   - 坐标：groupId = 模块 android.namespace；artifactId = 模块名

// 插件 jar 在模块 buildscript classpath：用该 classloader 取类并 apply（脚本自身 classloader 看不到它）
val buildscriptLoader = project.buildscript.classLoader
val basePluginClass = Class.forName("com.vanniktech.maven.publish.MavenPublishBasePlugin", true, buildscriptLoader)
val basePluginCtor = basePluginClass.getDeclaredConstructor()
basePluginCtor.isAccessible = true
val basePluginApply = basePluginClass.getMethod("apply", org.gradle.api.Project::class.java)
basePluginApply.invoke(basePluginCtor.newInstance(), project)

apply(plugin = "signing")

// ── 求值期配置（AGP 读 singleVariants / coordinates 定值之前必须完成）──────────
val mavenPublishingExt = extensions.getByName("mavenPublishing")
fun mp(name: String, vararg args: Any?) {
    // 反射到 extension 实例的方法（Kotlin 脚本里 Any 展开会吞掉 vararg 数组，统一走 invoke 两参形式）
    val m = (mavenPublishingExt as Any).javaClass.methods
        .first { it.name == name && it.parameterCount == args.size }
    try {
        m.invoke(mavenPublishingExt, args)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    } catch (e: IllegalArgumentException) {
        // vararg 方法：invoke 需要 (obj, Object[]); 非 vararg 单参方法上面会 mismatch，这里重试展开
        m.invoke(mavenPublishingExt, *args)
    }
}
// 坐标：groupId 固定 io.github.fellow99（与 README/规划一致）。
// version：node-bridge = VERSION_NAME；node-runtime = NODE_VERSION（= Node 版本，
// 由 tools/provision.mjs 与 runtime.lock.json 做一致性断言）。
val artifactId = project.name
// 版本：node-bridge = VERSION_NAME（自身 semver）；
//       node-runtime 的坐标 version 在模块 build.gradle.kts 里经 project.version + publication 覆写为 NODE_VERSION。
val versionName = providers.gradleProperty("VERSION_NAME").orNull ?: "0.1.0"
mp("coordinates", "io.github.fellow99", artifactId, versionName)
mp("pomFromGradleProperties")
// 发布目标：Central Portal（新版 https://central.sonatype.com，直接 POST，无 OSSRH staging 流程）。
// 0.33.0 的 (boolean) 重载会被 Kotlin synthetic 方法干扰（反射按名匹配漂到 (SonatypeHost) 版），
// 按签名精确取 (boolean) 方法调用：
val ptmcBoolMethod = mavenPublishingExt.javaClass.methods
    .first { it.name == "publishToMavenCentral"
        && it.parameterCount == 1
        && it.parameterTypes[0] == java.lang.Boolean.TYPE }
try {
    ptmcBoolMethod.invoke(mavenPublishingExt, java.lang.Boolean.FALSE)
} catch (e: java.lang.reflect.InvocationTargetException) {
    throw e.targetException
}
mp("signAllPublications")
// 单 variant：release + sources jar（必须在 AGP 求值前）；构造器为 (String, boolean, boolean)
val svClass = Class.forName("com.vanniktech.maven.publish.AndroidSingleVariantLibrary", true, project.buildscript.classLoader)
val svCtor = svClass.constructors.first()
svCtor.isAccessible = true
val singleVariant = svCtor.newInstance("release", java.lang.Boolean.valueOf(true))
mp("configure", singleVariant)

val releaseEnabled = providers.gradleProperty("RELEASE").orNull?.toBoolean() == true
val portalTokenReady = providers.gradleProperty("mavenCentralUsername").orNull?.isNotBlank() == true &&
    providers.gradleProperty("mavenCentralPassword").orNull?.isNotBlank() == true

// 插件与 AGP 都在 afterEvaluate 后可用：所有配置统一放这里（含 mavenPublishing extension）
afterEvaluate {
    // ── 签名（Maven Central 强制；凭据在用户级 gradle.properties）──
    val keyId = providers.gradleProperty("signing.keyId").orNull
    val keyPass = providers.gradleProperty("signing.password").orNull
    val keyData = providers.gradleProperty("signing.keyData").orNull
    val keyRing = providers.gradleProperty("signing.secretKeyRingFile").orNull
    val s = extensions.getByName("signing") as Any
    val sm = s.javaClass.methods
    sm.first { it.name == "setRequired" && it.parameterTypes[0] == java.lang.Boolean.TYPE }
        .invoke(s, releaseEnabled && portalTokenReady)
    if (!keyData.isNullOrBlank()) {
        sm.first { it.name == "useInMemoryPgpKeys" && it.parameterCount == 3 }
            .invoke(s, keyId, keyData, keyPass)
    } else if (!keyRing.isNullOrBlank()) {
        // 0.33.0 的 SigningExtension 无 usePgpKeyRingFile：自动用 gpg 把密钥环导出为 in-memory keyData
        // （一次性，之后建议直接把导出结果填进 signing.keyData 并删掉 secretKeyRingFile）。
        val tmp = java.nio.file.Files.createTempFile("aosp-bridge-sec", ".asc").toFile()
        try {
            val passphraseFile = java.nio.file.Files.createTempFile("aosp-bridge-pass", ".txt").toFile()
            passphraseFile.writeText(keyPass ?: "")
            try {
                val proc = ProcessBuilder(
                    "gpg", "--batch", "--yes",
                    "--pinentry-mode", "loopback",
                    "--passphrase-file", passphraseFile.absolutePath,
                    "--armor", "--export-secret-keys", keyId ?: "default-key",
                ).redirectOutput(tmp).redirectErrorStream(true).start()
                if (proc.waitFor() != 0 || !tmp.isFile() || tmp.length() == 0L) {
                    error("publish.gradle.kts: gpg 导出私钥失败（secretKeyRingFile=$keyRing 仅作参考，导出按 keyId=$keyId）")
                }
                val exported = tmp.readText()
                sm.first { it.name == "useInMemoryPgpKeys" && it.parameterCount == 3 }
                    .invoke(s, keyId, exported, keyPass)
            } finally {
                passphraseFile.delete()
            }
        } finally {
            tmp.delete()
        }
    }
}

// ── 发布门禁：RELEASE=false 或缺 Token 时禁用 Central/签名任务 ────────────────
// 注意：插件 0.33.0 的 Central Portal 任务名不含 "MavenCentral" 字样
//（publishMavenPublicationToMavenCentralRepository / publishToMavenCentral*），按前缀匹配。
tasks.configureEach {
    if (name.startsWith("signMaven") || name == "publishToMavenCentral"
        || name.contains("ToMavenCentral")) {
        enabled = enabled && releaseEnabled && portalTokenReady
    }
}
