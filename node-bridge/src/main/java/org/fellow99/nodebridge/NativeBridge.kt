package org.fellow99.nodebridge

/**
 * JNI 桥（模块 002-jni-bootstrap）。
 *
 * 唯一职责：把 Kotlin 调用变成对 `libnode_bridge.so` 的 JNI 调用，并在库加载失败时
 * **记下原因**（不抛到调用方，也不静默）—— 探针 UI 要能显示「.so 没进 APK」。
 */
internal object NativeBridge {

    const val LIB_NAME = "node_bridge"

    @Volatile
    var loadError: String? = null
        private set

    val available: Boolean get() = loadError == null

    init {
        var err: String? = null
        try {
            System.loadLibrary(LIB_NAME)
        } catch (t: Throwable) {
            err = "${t.javaClass.simpleName}: ${t.message}"
        }
        loadError = err
    }

    // ── JNI 面（与 node_bridge.c 的函数名逐字对应，**改签名必须两边同时改**）──

    external fun start(
        libDir: String,
        scriptPath: String,
        flags: String,
        home: String,
        logPath: String,
        addonDir: String,
        envPairs: String,
    ): Int

    external fun isRunning(): Boolean
    /** 引导件自身加载对象的 handle（供 NAPI addon 的 setBridge 用，绕开命名空间隔离） */
    external fun bridgeHandle(): Long

    external fun stage(): String
    external fun lastError(): String
    external fun lastCrashJson(): String

    /** 返回 `<id>\u0001<name>\u0001<args>`；无请求返回 "" */
    external fun pollHostRequest(): String
    external fun completeHostRequest(id: Long, resultJson: String): Int

    /** 只探测不启动：返回 JSON（候选链 + 命中项 + 符号） */
    external fun probe(libDir: String): String
}
