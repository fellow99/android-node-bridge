// publish.gradle.kts —— 发布面共享配置（由 node-bridge 的 build.gradle.kts apply）。
//
// 插件：com.vanniktech:gradle-maven-publish-plugin 0.37.0
//   各模块 build.gradle.kts 的 buildscript 已把「AGP + 本插件」放进项目 classpath，
//   因此本脚本里可直接引用插件类型（Kotlin DSL）。
//
// 纪律（对齐 ohos-node-bridge 发布口径）：
//   - 敏感值（Portal Token / GPG）只从用户级 ~/.gradle/gradle.properties 读取，绝不入库
//   - RELEASE=false（默认）时 Central/签名任务被禁用，防误发
//   - 版本号：node-bridge = VERSION_NAME；node-runtime = NODE_VERSION（子模块 afterEvaluate 覆盖）
//   - 坐标：groupId = 模块 android.namespace；artifactId = 模块名

// 插件 jar 在模块 buildscript classpath：用该 classloader 取类并 apply（脚本自身 classloader 看不到它）。
// 0.37.0 起插件类为 abstract（Kotlin 编译细节）：直接 newInstance 不可用，
// 用 JDK 动态代理实现 Plugin 接口，apply(Project) 里反射调真实插件类的 apply 逻辑。
// 注意：真实类是 abstract 但 apply 是 concrete 实例方法 —— 代理无法调实例方法，
// 因此改用「字节码层面不可行 → 回退 0.33.0」或「让 Gradle 自己加载插件 id」。
// 最终方案：把插件 classpath 加到 settings 的 pluginManagement，用正式 id apply。
// 见 settings.gradle.kts 的 buildscript 段。此处不再手动反射。
println("[publish] 插件经 settings pluginManagement 加载（id=com.vanniktech.maven.publish.base）")

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
// version：node-bridge = VERSION_NAME
// 由 tools/provision.mjs 与 runtime.lock.json 做一致性断言）。
val artifactId = project.name
// 版本：node-bridge = VERSION_NAME（自身 semver）；
val versionName = providers.gradleProperty("VERSION_NAME").orNull ?: project.version.toString()
mp("coordinates", "io.github.fellow99", artifactId, versionName)
mp("pomFromGradleProperties")

// 发布目标：Central Portal（0.37.0 起 publishToMavenCentral 直连 central.sonatype.com，
// 无 OSSRH staging 流程）。签名 (boolean requiresToken, DeploymentValidation validation)。
// requiresToken=false（配置期不强制 Token，任务执行前另有下方 enabled 门禁）；validation=null 走默认。
// 双 primitive 重载 (boolean, boolean)：第一参 requiresToken，第二参 = 是否验证 deployment。
// 用 (false, false)：配置期不强制 Token、不做 deployment 验证（任务执行前另有 enabled 门禁）。
val ptmc2BoolMethod = mavenPublishingExt.javaClass.methods
    .first { it.name == "publishToMavenCentral" && it.parameterCount == 2
        && it.parameterTypes[0] == java.lang.Boolean.TYPE
        && it.parameterTypes[1] == java.lang.Boolean.TYPE }
try {
    ptmc2BoolMethod.invoke(mavenPublishingExt, java.lang.Boolean.FALSE, java.lang.Boolean.FALSE)
} catch (e: java.lang.reflect.InvocationTargetException) {
    throw e.targetException
}
mp("signAllPublications")
// 单 variant：release + sources jar（必须在 AGP 求值前）；构造器为 (String, boolean, boolean)
val svClass = Class.forName("com.vanniktech.maven.publish.AndroidSingleVariantLibrary", true, project.buildscript.classLoader)
// 0.37.0 构造器：(JavadocJar, SourcesJar, String variant)
val javadocJarNone: Any = Class.forName("com.vanniktech.maven.publish.JavadocJar\$None", true, project.buildscript.classLoader)
    .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
val sourcesJar: Any = Class.forName("com.vanniktech.maven.publish.SourcesJar\$Sources", true, project.buildscript.classLoader)
    .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
val svCtor = svClass.getConstructor(
    Class.forName("com.vanniktech.maven.publish.JavadocJar", true, project.buildscript.classLoader),
    Class.forName("com.vanniktech.maven.publish.SourcesJar", true, project.buildscript.classLoader),
    String::class.java)
val singleVariant = svCtor.newInstance(javadocJarNone, sourcesJar, "release")
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

// ── POM SCM url 补丁（Central Portal 强制要求 <scm><url>）────────────────────
// pomFromGradleProperties 只填 connection/developerConnection，不填 url。
// 在 generatePom 任务 doLast 里直接改 XML。
tasks.configureEach {
    if (name.startsWith("generatePomFile")) {
        doLast {
            // 实际路径：build/publications/maven/pom-default.xml
            val realPom = layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile
            if (realPom.exists()) {
                var xml = realPom.readText()
                if ("<scm>" in xml && "<url>" !in xml.substring(xml.indexOf("<scm>"), xml.indexOf("</scm>"))) {
                    val urlTag = "    <url>https://github.com/fellow99/aosp-node-bridge</url>\n  </scm>"
                    xml = xml.replaceFirst("</scm>", urlTag)
                    realPom.writeText(xml)
                    println("[publish] POM SCM url 已补: $realPom")
                }
            }
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
