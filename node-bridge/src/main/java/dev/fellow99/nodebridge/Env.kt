package dev.fellow99.nodebridge

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream

/**
 * 环境与文件系统契约（模块 001 / 004）。
 *
 * 与 HarmonyOS 版的关键差异（规划 §4.8 #1）：Android 的 `assets/` **不是文件系统路径**，
 * Node 的 `require` 读不到，所以载荷必须先「物化」到 `filesDir`。物化是**幂等**的：
 * `.ready-<marker>` 命中且目标存在 ⇒ 跳过。
 */
internal object Env {

    fun homeDir(ctx: Context): File = File(ctx.filesDir, "node-bridge").apply { mkdirs() }

    fun cacheTmp(ctx: Context): File = File(ctx.cacheDir, "node-bridge/tmp").apply { mkdirs() }

    fun logFile(ctx: Context): File = File(homeDir(ctx), "node_bridge.log")

    fun crashFile(ctx: Context): File = File(homeDir(ctx), "node_bridge.log.crash.json")

    fun marker(root: File, marker: String): File = File(root, ".ready-$marker")

    /**
     * 把 `assets/<assetDir>` 下的全部文件（递归）复制到 [dest]。
     * 已存在且大小一致的跳过；完成后写 `.ready-<marker>`。
     *
     * @return 实际写入的文件数
     */
    fun materializeAssetDir(ctx: Context, assetDir: String, dest: File, marker: String): Int {
        val ready = marker(dest, marker)
        if (ready.isFile && dest.isDirectory) return 0
        dest.mkdirs()

        var written = 0
        copyDir(ctx.assets, assetDir, dest) { written++ }
        ready.writeText("${System.currentTimeMillis()}\n")
        return written
    }

    private fun copyDir(am: AssetManager, assetPath: String, dest: File, onWrite: () -> Unit) {
        val children = runCatching { am.list(assetPath) }.getOrNull() ?: return
        if (children.isEmpty()) {
            // 叶子：单个文件
            val target = File(dest, assetPath.substringAfterLast('/'))
            if (target.isFile && target.length() > 0) return
            runCatching {
                am.open(assetPath).use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) }
                }
                onWrite()
            }
            return
        }
        for (child in children) {
            val childAsset = if (assetPath.isEmpty()) child else "$assetPath/$child"
            val childDest = File(dest, child)
            val grandChildren = runCatching { am.list(childAsset) }.getOrNull()
            if (!grandChildren.isNullOrEmpty()) {
                childDest.mkdirs()
                copyDir(am, childAsset, childDest, onWrite)
            } else {
                val target = File(dest, child)
                if (target.isFile && target.length() > 0) continue
                runCatching {
                    am.open(childAsset).use { input ->
                        FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) }
                    }
                    onWrite()
                }
            }
        }
    }

    /** 读日志文件尾部，解析成 `<stream> <epochMs> <text>` 行 */
    fun tailLog(file: File, maxLines: Int): List<NodeLogEntry> {
        if (!file.isFile) return emptyList()
        val lines = ArrayDeque<String>()
        runCatching {
            file.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    lines.addLast(line)
                    if (lines.size > maxLines) lines.removeFirst()
                }
            }
        }
        return lines.mapNotNull { line ->
            val first = line.indexOf(' ')
            val second = if (first > 0) line.indexOf(' ', first + 1) else -1
            if (first > 0 && second > first) {
                val stream = line.substring(0, first)
                val at = line.substring(first + 1, second).toLongOrNull() ?: 0L
                NodeLogEntry(at, stream, line.substring(second + 1))
            } else {
                NodeLogEntry(0L, "out", line)
            }
        }
    }
}
