package org.fellow99.nodebridge.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fellow99.nodebridge.NodeRuntime
import org.fellow99.nodebridge.NodeRuntimeOptions
import org.fellow99.nodebridge.NodeState
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 真机探针（Phase 0 的载体）
 *
 * 一次点击就能拿到三件事的证据：
 *   ① 引擎能否被找到（dlopen/dlsym 候选链）；
 *   ② Node 是否真的在这个进程里跑起来（stage / 日志 / Node 自己写的第一行输出）；
 *   ③ JIT/WASM 在**本机 + 本 targetSdk** 下是否可用（`/facts` 的 `jit` 字段 + 热循环耗时）。
 *
 * 铁律：**失败必须可见**。每一步的失败都写到屏幕上，不靠「logcat 里有没有那行」判断。
 */
class MainActivity : Activity() {

    private lateinit var runtime: NodeRuntime
    private lateinit var status: TextView
    private lateinit var output: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val port = 15577

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = NodeRuntime.getInstance(this)

        // ① 先注册宿主能力（Node → Kotlin），再启动 Node（顺序不能反）
        registerHostCapabilities()

        setContentView(buildUi())
        render(banner())
        startStateTicker()

        // 真机/CI 取证入口：`am start -e autorun 1` ⇒ 自动跑「启动 + 读 facts」并把结果打到屏幕与 logcat
        // 注意：`am start -e autorun 1` 里 extra 实际是 String "1"，getBooleanExtra 会 ClassCastException
        // （MIUI 15 上以 warning 出现，但 autorun 分支被跳过）⇒ 用 getStringExtra 判定。
        val autorun = intent?.getStringExtra("autorun")?.let { it == "1" || it == "true" } ?: false
        if (autorun) {
            ui.postDelayed({
                // 取证前先清旧载荷（.ready marker 会跳过物化 ⇒ 旧版本 JS 会一直跑）
                java.io.File(filesDir, "node-bridge/payload").deleteRecursively()
                doStart()
            }, 400)
            ui.postDelayed({ doFacts() }, 8000)
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(24, 32, 24, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(status)

        root.addView(buttonRow(1, listOf(
            "① 探测引擎" to { doProbe() },
            "② 启动 Node" to { doStart() },
        )))
        root.addView(buttonRow(2, listOf(
            "③ 读 /facts" to { doFacts() },
            "④ 看日志" to { doLogs() },
        )))

        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(24, 8, 24, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(
            ScrollView(this).apply {
                addView(output)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun buttonRow(idx: Int, defs: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 16, 4)
        }
        for ((label, action) in defs) {
            row.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    private fun banner(): String = """
        |aosp-node-bridge · 探针
        |device : ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}
        |abi    : ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}
        |pkg    : $packageName  (targetSdk=${applicationInfo.targetSdkVersion})
        |libDir : ${applicationInfo.nativeLibraryDir}
    """.trimMargin()

    private fun render(text: String) {
        ui.post { output.text = text }
    }

    private fun say(line: String) {
        val stamped = "${timeFmt.format(Date())} $line"
        ui.post {
            val cur = output.text?.toString().orEmpty()
            output.text = if (cur.isEmpty()) stamped else "$cur\n$stamped"
            (output.parent?.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setStatus(text: String) {
        ui.post { status.text = text }
    }

    // ── 状态回显 ─────────────────────────────────────────────────────────────

    private fun startStateTicker() {
        val tick = object : Runnable {
            override fun run() {
                val s = runtime.state.value
                setStatus(
                    "state=${s::class.simpleName}  stage=${runtime.stage}  " +
                        "native=${if (runtime.nativeAvailable) "ok" else "missing"}  " +
                        "host=${runtime.host.names()}"
                )
                ui.postDelayed(this, 500)
            }
        }
        ui.post(tick)
    }

    // ── 动 作 ────────────────────────────────────────────────────────────────

    private fun registerHostCapabilities() {
        runtime.host.register("echo") { args -> """{"ok":true,"echo":$args,"handledBy":"kotlin","at":${System.currentTimeMillis()}}""" }
        runtime.host.register("user.info") {
            """{"ok":true,"pkg":"$packageName","sdk":${android.os.Build.VERSION.SDK_INT},"model":"${android.os.Build.MODEL}"}"""
        }
        runtime.host.register("shutdown") { """{"ok":true,"note":"shutdown 钩子已注册（真正的停止只能是进程退出）"}""" }
    }

    private fun doProbe() {
        say("probing engine …")
        io.execute {
            val json = runtime.probeEngine()
            say("probe -> $json")
        }
    }

    private fun doStart() {
        val opts = NodeRuntimeOptions(
            // node 直接跑引导器；业务入口由 bootstrap 里按 NODE_BRIDGE_ENTRY 解析
            entry = "bootstrap.js",
            appEntry = "selfcheck.js",
            env = mapOf(
                "NODE_BRIDGE_PORT" to port.toString(),
                "NODE_ENV" to "probe",
            ),
        )
        say("start(${opts.entry} → ${opts.appEntry}) …")
        val r = runtime.start(opts)
        say("start rc=${r.code} ok=${r.ok} state=${r.state} msg=${r.message ?: "-"}")
        if (!r.ok) {
            say("lastError=${runtime.lastError}")
            val crash = runtime.lastCrash()
            if (crash != null) say("lastCrash=${crash.raw.trim()}")
            return
        }
        io.execute {
            val st = runtime.awaitRunning(30_000)
            say("awaitRunning -> $st  stage=${runtime.stage}")
        }
    }

    private fun doFacts() {
        io.execute {
            val url = "http://127.0.0.1:$port/facts"
            say("GET $url …")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 15000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                say("HTTP $code")
                say(body)
                conn.disconnect()
            } catch (t: Throwable) {
                say("HTTP 失败：${t.javaClass.simpleName}: ${t.message}")
                say("→ 先确认 ② 已启动且 state=Running；再看 ④ 日志里 stage 卡在哪")
            }
        }
    }

    private fun doLogs() {
        io.execute {
            val logs = runtime.logs(200)
            say("---- node_bridge.log (${logs.size} 行) ----")
            for (e in logs.takeLast(120)) {
                say("[${e.stream}] ${e.text}")
            }
            say("---- 文件：${filesDir}/node-bridge/node_bridge.log ----")
            runtime.lastCrash()?.let { say("lastCrash: ${it.raw.trim()}") }
        }
    }
}
