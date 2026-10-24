# aosp-node-bridge

[English](README.md) · [**中文**](README_zh.md)

把**真正的 Node.js 运行时嵌进 Android 应用自己的进程**——不是 WebView、不是远程服务、也不是独立二进制。Node 通过 JNI 引导件在进程内运行：`dlopen("libnode.so")` + `node::Start`，并在 JavaScript 与 Kotlin 之间建立双向桥。

引擎本体作为**独立包**发布（[aosp-node-runtime](https://github.com/fellow99/aosp-node-runtime)），因此 bridge 与 Node 版本可以各自独立演进。

- 架构/设计文档：[`docs/000-工程规划.md`](docs/000-工程规划.md)
- 真机验证记录：[`docs/002-真机验证记录.md`](docs/002-真机验证记录.md)

## 两个包

| 包 | Maven 坐标 | 版本 | 内容 |
|---|---|---|---|
| Bridge | `io.github.fellow99:node-bridge` | `0.2.0` | Kotlin API、JNI 引导件（`libnode_bridge.so`）、NAPI addon（`libnode_host_client.so`）、JS 载荷 |
| Runtime | `io.github.fellow99:node-runtime` | `24.2.0` | 仅 `libnode.so` + `libc++_shared.so`（[aosp-node-runtime](https://github.com/fellow99/aosp-node-runtime)） |

环境要求：`minSdk 24` · `arm64-v8a` · 引擎在运行期加载（而非编译期链接）。

## 快速开始

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fellow99:node-bridge:0.2.0")
    runtimeOnly("io.github.fellow99:node-runtime:24.2.0") // 引擎，按 Node 版本钉死
}
```

```kotlin
// ① 先注册宿主能力，再启动（顺序不能反）。
val rt = NodeRuntime.getInstance(context)

rt.host.register("fs.pickFolder") { argsJson ->
    // 在主线程执行；返回 JSON 字符串。
    """{"ok":true,"path":"/sdcard/Download"}"""
}

val result = rt.start(
    NodeRuntimeOptions(
        entry = "bootstrap.js",       // node 直接运行的脚本
        appEntry = "selfcheck.js",    // bootstrap 解析的业务入口
        env = mapOf("MY_CONFIG" to "value"),
    ),
)
if (!result.ok) Log.e("node", "${result.state} ${result.message}") // 失败必须可见

// 观察生命周期状态：Idle → Starting → Running → Exited/Failed
rt.state.collect { state -> /* ... */ }
```

Node 侧调用宿主：

```js
const host = require('./host.js');           // 由库的 assets 提供
const res = await host.invoke('fs.pickFolder', { title: '选择目录' });
// res === { ok: true, path: '/sdcard/Download' }
```

## 工作原理

```
┌─────────────────────────────── App 进程 ───────────────────────────────┐
│                                                                        │
│   Kotlin（NodeRuntime / NodeHostBridge）                                │
│        ▲                              │ 宿主请求（JSON over JNI）        │
│        │ 结果（JSON）                  ▼                                │
│   libnode_bridge.so  ◀── 槽位表 ──▶  libnode_host_client.so（NAPI）      │
│        ▲ dlopen + dlsym                    ▲ process.dlopen            │
│        │                                   │                            │
│    libnode.so（Node.js + V8，来自 node-runtime） ◀── bootstrap/host.js  │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

- **`libnode_bridge.so`** 只链 `log`/`dl`，绝不链 `libnode`；它用 `dlopen` 定位引擎、用 `dlsym` 取 `node::Start`，因此 bridge 与 Node 版本解耦。
- **`libnode_host_client.so`** 是 NAPI addon。它的 `napi_*` 符号刻意留作未定义（`-z undefs`），运行期由已 `dlopen` 的 `libnode.so`（`RTLD_GLOBAL`）解析——保证用的一定是 Node 自己那套 NAPI 实现。
- **JS 载荷**（`bootstrap.js`、`host.js`、`selfcheck.js`）放在 AAR 的 `assets` 里，而 Android 的 `assets` 不能被 `require` 直接读取，所以库在启动前把它**物化**到 `filesDir/node-bridge`（幂等）。

## 仓库结构

```
aosp-node-bridge/
├── node-bridge/             # AAR：Kotlin API + 两个 .so + JS assets
│   └── src/main/
│       ├── cpp/             # node_bridge.c、node_host_client.c、自带 NAPI 头文件
│       └── java/org/fellow99/nodebridge/
├── payload/                 # JS 载荷（唯一事实源；构建期复制进 AAR assets）
├── samples/minimal-app/     # 真机探针（modern=targetSdk 36 / legacy=targetSdk 28）
├── tools/                   # build-bridge / smoke-host / release-maven / collect-evidence
└── docs/
```

## 本地构建

bridge 默认直接调用 NDK clang 编译（不需要 CMake/ninja）：

```bash
# 把两个 .so 编译进 node-bridge/src/main/jniLibs/arm64-v8a
node tools/build-bridge.mjs

# 不接真机的 host 侧门禁（JS 语法 + 桥 JSON 契约）
node tools/smoke-host.mjs

# 出探针 APK
gradle :samples:minimal-app:assembleModernDebug
```

自带的 NAPI 头文件（`node-bridge/src/main/cpp/include/node/`）是稳定的 N-API 表面，跨 Node 大版本极少变动。bridge 编译期完全不需要 `libnode.so`。

## 已知约束（都会如实上报，不静默降级）

| 约束 | 说明 |
|---|---|
| `node::Start` 每进程一次 | 崩溃/退出即终态（`NodeState.Exited`/`Failed`）。可重启的形态是把 Node 放进 `:node` 独立进程。 |
| `assets/` 不是文件系统路径 | 载荷必须先物化到 `filesDir`（库已内建该逻辑）。 |
| JIT 需真机实测 | 现代 targetSdk 的 W^X/`execmem` 可能封死 V8 JIT。探针的 `/facts.jit` 给出结论（已在 Android 15 上验证 JIT 与 WASM 均可用）。 |
| 引擎有硬性要求 | `libnode.so` 必须为 `ET_DYN`、无 `PT_INTERP`、导出 `_ZN4node5StartEiPPc`。 |

## 许可证

[MIT](LICENSE)
