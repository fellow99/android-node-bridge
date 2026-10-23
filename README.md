# android-node-bridge

> **一句话**：把**真正的 Node.js 运行时**以 `JNI 引导 + dlopen + node::Start` 的形态嵌进 **Android 应用自己的进程**，
> 拆成两个可发布的 AAR —— **`node-bridge`**（接口 + 加载器）与 **`node-runtime`**（只装引擎 `libnode.so`）。
> 结构对标 [`ohos-node-bridge`](https://github.com/fellow99/ohos-node-bridge)（`node-runtime` + `node-bridge` 双包）。

工程规划（唯一权威）：[`docs/000-工程规划.md`](docs/000-工程规划.md) · 真机记录：[`docs/002-真机验证记录.md`](docs/002-真机验证记录.md)

## 目录

```
android-node-bridge/
├── node-bridge/          # AAR ①：Kotlin API + JNI 引导件（libnode_bridge.so）+ NAPI addon + 载荷 assets
├── node-runtime/         # AAR ②：只装 libnode.so（+ libc++_shared.so）+ 版本常量 + 许可
├── payload/              # Node 侧 JS（唯一事实源，构建期同步进 AAR 的 assets/node-bridge/）
├── samples/minimal-app/  # 真机探针（P0-1 JIT 矩阵：modern=targetSdk36 / legacy=targetSdk28）
├── tools/                # build-bridge / provision（八项 ELF 门禁）/ smoke-host / collect-evidence
├── runtime.lock.json     # ★ 运行时唯一事实源（版本 + SHA-256 + 门禁 + 出处 + 真机记录）
└── docs/ specs/
```

## 快速开始（本机已可复现）

```bash
# ① 投放运行时（二进制不入库；八项 ELF 门禁 + SHA-256 硬校验）
node tools/provision.mjs --out node-runtime/src/main/jniLibs \
  --from /path/to/libnode.so --allow-page-align-4k        # bring-up 产物是 4 KB 对齐

# ② 编译引导件与 addon（NDK clang 直调，不需要 CMake/ninja）
node tools/build-bridge.mjs --out node-bridge/src/main/jniLibs/arm64-v8a

# ③ host 侧门禁（不接真机）
node tools/smoke-host.mjs

# ④ 出探针 APK（两个 flavor 对应 P0-1 的判定矩阵对照组）
gradle :samples:minimal-app:assembleModernDebug
gradle :samples:minimal-app:assembleLegacyDebug

# ⑤ 真机取证（安装 → autorun → logcat + adb forward + curl /facts）
bash tools/collect-evidence.sh modern
```

## 消费方接入三步

```kotlin
// build.gradle.kts
implementation(project(":node-bridge"))      // 或 io.github.fellow99:node-bridge
runtimeOnly(project(":node-runtime"))        // 或 io.github.fellow99:node-runtime:24.x.y

// ① 先注册宿主能力，再启动（顺序不能反）
val rt = NodeRuntime.getInstance(context)
rt.host.register("fs.pickFolder") { argsJson -> /* 主线程执行，返回 JSON */ }
val r = rt.start(
    NodeRuntimeOptions(
        entry = "bootstrap.js",          // node 直接跑的脚本
        appEntry = "selfcheck.js",       // bootstrap 解析的业务入口
        env = mapOf("NODE_BRIDGE_PORT" to "15577"),
    ),
)
if (!r.ok) Log.e("app", "${r.state} ${r.message}")   // 失败必须可见
```

Node 侧：

```js
const host = require('./host.js');            // 由库的 assets 提供（物化到 filesDir 后可 require）
const info = await host.invoke('fs.pickFolder', { title: '选择目录' });
```

## 已知约束（都会如实上报，不静默降级）

| 约束 | 说明 |
|---|---|
| `node::Start` **每进程一次** | 崩溃/退出即终态；`NodeState.Exited` 显式建模。可重启的形态是 `:node` 独立进程（规划 §4.9 B） |
| `assets` **不是文件系统路径** | 载荷必须先物化到 `filesDir`（库已内建 `/node-bridge` 物化，幂等） |
| JIT 需实测 | `targetSdk ≥ 29` 的 W^X/`execmem` 是唯一可能封死 V8 JIT 的地方 → 探针的 `/facts.jit` 给结论 |
| `libnode.so` 3 个硬要求 | `ET_DYN` + 无 `PT_INTERP` + 导出 `_ZN4node5StartEiPPc`；`DT_NEEDED` 含 `libc++_shared.so` |
| addon 需 `DT_NEEDED libnode.so` | 否则 `symbol not found`（`tools/build-bridge.mjs` 已按此链路编译） |

## 构建环境

- Gradle 9.4.1 + AGP 9.2.1 + JDK 21；`maven.google.com` 不可达时走阿里云镜像（见 `settings.gradle.kts`）
- AGP 9 **自带 Kotlin 支持**：不要再应用 `org.jetbrains.kotlin.android`（会争抢 `kotlin` extension）
- NDK `27.2.12479018`（`tools/build-bridge.mjs` 自动发现，也可 `ANDROID_NDK_HOME` 指定）
- `compileSdk 36` / `minSdk 24` / `targetSdk` 36（modern）· 28（legacy 对照）/ 仅 `arm64-v8a`

## 许可

本仓库 MIT。`node-runtime` 随包分发 Node.js 运行时，适用其上游许可 —— 见
[`node-runtime/THIRD-PARTY-NOTICES.md`](node-runtime/THIRD-PARTY-NOTICES.md)（自建产物落地时必须补齐 Node 官方 `LICENSE` 原文）。
