package dev.fellow99.nodebridge

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 状态机（模块 001-runtime-core）。
 *
 * 与 HarmonyOS 版的差异见 `docs/000-工程规划.md` §5.2：
 * **多一个 [Exited]** —— Android 上 `process.exit()` 不会杀 App，`node::Start` 正常返回后
 * 该线程结束、运行时永久死亡（再次 start 必失败）。不显式建模就会变成
 * 「状态永远 running 但服务不响应」这种最难查的故障。
 */
sealed interface NodeState {
    data object Idle : NodeState
    data object Starting : NodeState
    data class Running(val pid: Int) : NodeState
    data class Failed(val stage: String, val message: String) : NodeState
    data object Stopping : NodeState
    data object Stopped : NodeState

    /** `node::Start` 正常返回（Node 侧主动退出 / 事件循环空转结束） */
    data class Exited(val code: Int) : NodeState
}

/** 进程内（默认）或 `:node` 独立进程（可选加固，Phase 3）。 */
enum class ProcessMode { IN_PROCESS, DEDICATED_PROCESS }

/**
 * 启动参数。**一次性输入**：`execArgv` 只能在 `node::Start` 之前定，库不提供运行期 push（原则「启动参数不可事后修改」）。
 */
data class NodeRuntimeOptions(
    /** 让 `node` 直接运行的脚本（相对 [payloadRoot] 或绝对路径）。默认 `bootstrap.js` */
    val entry: String = "bootstrap.js",
    /** `bootstrap.js` 要解析并加载的**业务入口**（相对 [payloadRoot] 或绝对路径；空 ⇒ 由 bootstrap 按 package.json:main / index.js 猜） */
    val appEntry: String? = null,
    /** Node 可写根；默认 `<filesDir>/node-bridge/payload` */
    val payloadRoot: String? = null,
    /** 给出即把 AAR 内 `assets/node-bridge/` 物化到 payloadRoot（幂等），并以其为载荷根 */
    val materializeBuiltinPayload: Boolean = true,
    val argv: List<String> = emptyList(),
    /** 传给 Node 的启动参数（`node` 之后、脚本之前）；缺省 `--no-verify-heap` */
    val execArgv: List<String> = listOf("--no-verify-heap"),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    /** 单次宿主桥 JSON 载荷上限（字节） */
    val capBytes: Long = 8L shl 20,
    val logRingSize: Int = 500,
    /** 诊断专用；开了就等于关掉 WebAssembly（见规划 §4.5） */
    val jitless: Boolean = false,
    val processMode: ProcessMode = ProcessMode.IN_PROCESS,
)

data class StartResult(val ok: Boolean, val code: Int, val state: NodeState, val message: String?)

data class NodeLogEntry(val at: Long, val stream: String, val text: String)

data class CrashRecord(val at: Long, val stage: String, val signal: Int?, val raw: String)

/** 平台能力标志：**如实上报**，绝不静默降级（规划 §3.2）。 */
data class PlatformFacts(
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    val abiList: List<String> = Build.SUPPORTED_ABIS.toList(),
    val nativeLibDir: String = "",
    val jitlessWanted: Boolean = false,
)

internal fun NodeRuntime.materializePayload(ctx: Context, opts: NodeRuntimeOptions): File {
    val root = File(opts.payloadRoot ?: File(ctx.filesDir, "node-bridge/payload").absolutePath)
    if (opts.materializeBuiltinPayload) {
        Env.materializeAssetDir(ctx, "node-bridge", root, marker = "builtin-v1")
    } else if (!root.isDirectory) {
        root.mkdirs()
    }
    return root
}
