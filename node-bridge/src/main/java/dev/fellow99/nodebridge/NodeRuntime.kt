package dev.fellow99.nodebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行时核心（模块 001-runtime-core）。
 *
 * 契约要点（与 `docs/000-工程规划.md` §5.2–§5.3 一一对应）：
 *  · **进程内单例**：`start()` 只允许一次，二次返回 `ok=false` 并保留首次原因；
 *  · **投递语义**：`start()` 立即返回，不代表 Node 已就绪（判据 = 探活/`/facts`）；
 *  · **绝不 join**：`node::Start` 直到进程结束才返回，任何等待都用超时轮询；
 *  · **不假装重启**：`failed` 是终态，只允许读日志/读崩溃记录/提示重开 App；
 *  · **就绪由消费方判定**：[markRunning] / [markFailed]。
 */
class NodeRuntime private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var instance: NodeRuntime? = null

        fun getInstance(context: Context): NodeRuntime =
            instance ?: synchronized(this) {
                instance ?: NodeRuntime(context.applicationContext).also { instance = it }
            }
    }

    private val _state = MutableStateFlow<NodeState>(NodeState.Idle)

    /** 状态流（Android 有真实的 Looper，直接推送；不需要消费方 100 ms 轮询） */
    val state: StateFlow<NodeState> = _state.asStateFlow()

    /** 宿主能力桥（Node → Kotlin） */
    val host: NodeHostBridge = NodeHostBridge()

    val nativeAvailable: Boolean get() = NativeBridge.available
    val nativeLoadError: String? get() = NativeBridge.loadError

    /** 原生阶段的只读探针（init|stdio|env|dlopen|dlsym|start|exited|failed） */
    val stage: String get() = if (NativeBridge.available) NativeBridge.stage() else "no-lib"
    val lastError: String get() = if (NativeBridge.available) NativeBridge.lastError() else (NativeBridge.loadError ?: "")

    @Volatile
    private var options: NodeRuntimeOptions = NodeRuntimeOptions()
    private val started = AtomicBoolean(false)
    private val monitorStop = AtomicBoolean(false)
    private var monitor: Thread? = null
    private var dispatcher: Thread? = null
    private var logFile: File? = null

    /** 宿主进程 pid（**不是**子进程） */
    val pid: Int get() = android.os.Process.myPid()

    fun start(opts: NodeRuntimeOptions = NodeRuntimeOptions()): StartResult {
        if (!started.compareAndSet(false, true)) {
            return StartResult(false, -1, _state.value, "已启动过（node::Start 每进程一次）")
        }
        options = opts

        if (!NativeBridge.available) {
            val msg = "libnode_bridge.so 未加载：${NativeBridge.loadError}"
            _state.value = NodeState.Failed("lib", msg)
            return StartResult(false, -2, _state.value, msg)
        }

        _state.value = NodeState.Starting

        val payloadRoot = materializePayload(appContext, opts)
        val entry = resolveEntry(payloadRoot, opts.entry)
        if (entry == null) {
            val msg = "入口不存在：${opts.entry}（payloadRoot=$payloadRoot）"
            _state.value = NodeState.Failed("payload", msg)
            return StartResult(false, -3, _state.value, msg)
        }

        val home = Env.homeDir(appContext)
        val log = Env.logFile(appContext)
        logFile = log

        val flags = buildList {
            addAll(opts.execArgv)
            if (opts.jitless && none { it == "--jitless" }) add("--jitless")
        }.joinToString(" ")

        val appEntryFile = opts.appEntry?.let { resolveEntry(payloadRoot, it) }
        val envPairs = buildMap {
            putAll(opts.env)
            put("NODE_BRIDGE_PAYLOAD_ROOT", payloadRoot.absolutePath)
            if (appEntryFile != null) put("NODE_BRIDGE_ENTRY", appEntryFile.absolutePath)
            put("NODE_BRIDGE_LOG", log.absolutePath)
            put("NODE_BRIDGE_CWD", opts.cwd ?: payloadRoot.absolutePath)
        }.entries.joinToString("\n") { (k, v) -> "$k=$v" }

        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir

        val rc = try {
            NativeBridge.start(
                libDir = nativeLibDir,
                scriptPath = entry.absolutePath,
                flags = flags,
                home = home.absolutePath,
                logPath = log.absolutePath,
                addonDir = nativeLibDir,
                envPairs = envPairs,
            )
        } catch (t: Throwable) {
            val msg = "JNI start 抛异常：${t.javaClass.simpleName}: ${t.message}"
            _state.value = NodeState.Failed("jni", msg)
            return StartResult(false, -4, _state.value, msg)
        }

        if (rc != 0) {
            val msg = "投递失败 rc=$rc：$lastError"
            _state.value = NodeState.Failed(stage, msg)
            return StartResult(false, rc, _state.value, msg)
        }

        startHostDispatcher()
        startMonitor()
        return StartResult(true, 0, _state.value, null)
    }

    /** 阻塞等待（**带超时**），返回最终状态；用于探针与冷启动页。 */
    fun awaitRunning(timeoutMs: Long = 60_000): NodeState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = _state.value
            if (s is NodeState.Running || s is NodeState.Failed || s is NodeState.Exited) return s
            Thread.sleep(100)
        }
        return _state.value
    }

    /** 消费方探活成功后调用 */
    fun markRunning() {
        if (_state.value !is NodeState.Failed) _state.value = NodeState.Running(pid)
    }

    fun markFailed(reason: String) {
        _state.value = NodeState.Failed(stage, reason)
    }

    fun logs(maxLines: Int = options.logRingSize): List<NodeLogEntry> {
        val f = logFile ?: Env.logFile(appContext)
        return Env.tailLog(f, maxLines)
    }

    fun lastCrash(): CrashRecord? {
        if (!NativeBridge.available) return null
        val raw = runCatching { NativeBridge.lastCrashJson() }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        val signal = Regex("\"signal\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return CrashRecord(System.currentTimeMillis(), "crash", signal, raw)
    }

    /** 探测引擎是否可被找到（不启动 Node）：给探针 UI 用 */
    fun probeEngine(): String {
        if (!NativeBridge.available) return """{"found":false,"reason":"libnode_bridge.so 未加载"}"""
        return runCatching { NativeBridge.probe(appContext.applicationInfo.nativeLibraryDir) }
            .getOrElse { """{"found":false,"reason":"${it.message}"}""" }
    }

    fun facts(): PlatformFacts = PlatformFacts(
        nativeLibDir = appContext.applicationInfo.nativeLibraryDir,
        jitlessWanted = options.jitless,
    )

    /** 触发 Node 侧 `shutdown` 钩子（真正停止只能是进程退出） */
    fun shutdown(reason: String): Boolean {
        _state.value = NodeState.Stopping
        val ok = runCatching {
            NativeBridge.available && host.dispatch("shutdown", """{"reason":"$reason"}""").isNotBlank()
        }.getOrDefault(false)
        _state.value = if (ok) NodeState.Stopped else _state.value
        return ok
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun resolveEntry(root: File, entry: String): File? {
        val f = File(entry)
        if (f.isAbsolute) return f.takeIf { it.isFile }
        val direct = File(root, entry)
        if (direct.isFile) return direct
        // 允许把 bootstrap.js 作为入口，由它按 NODE_BRIDGE_ENTRY 解析真实业务入口
        return null
    }

    private fun startMonitor() {
        monitorStop.set(false)
        val f = logFile
        val th = Thread({
            var reportedRunning = false
            while (!monitorStop.get() && !Thread.currentThread().isInterrupted) {
                val s = runCatching { NativeBridge.stage() }.getOrDefault("")
                when (s) {
                    "failed" -> {
                        _state.value = NodeState.Failed("native", lastError)
                        return@Thread
                    }
                    "exited" -> {
                        _state.value = NodeState.Exited(0)
                        return@Thread
                    }
                }
                if (!reportedRunning && f != null) {
                    // 代理判据（A3）：出现**非** bridge 自身的 Node 输出 ⇒ Node 真的在跑
                    val nodeSpoke = Env.tailLog(f, 80).any { !it.text.startsWith("[bridge]") }
                    if (nodeSpoke && s == "start") {
                        reportedRunning = true
                        if (_state.value is NodeState.Starting) _state.value = NodeState.Running(pid)
                    }
                }
                Thread.sleep(200)
            }
        }, "anb-monitor")
        th.isDaemon = true
        monitor = th
        th.start()
    }

    private fun startHostDispatcher() {
        val main = Handler(Looper.getMainLooper())
        val th = Thread({
            while (!monitorStop.get() && !Thread.currentThread().isInterrupted) {
                val raw = runCatching { NativeBridge.pollHostRequest() }.getOrNull().orEmpty()
                if (raw.isEmpty()) {
                    Thread.sleep(50)
                    continue
                }
                val parts = raw.split('\u0001', limit = 3)
                if (parts.size < 3) continue
                val id = parts[0].toLongOrNull() ?: continue
                val name = parts[1]
                val args = parts[2]
                main.post {
                    val result = host.dispatch(name, args)
                    runCatching { NativeBridge.completeHostRequest(id, result) }
                }
            }
        }, "anb-host-dispatch")
        th.isDaemon = true
        dispatcher = th
        th.start()
    }
}
