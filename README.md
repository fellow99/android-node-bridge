# aosp-node-bridge

[**English**](README.md) · [中文](README_zh.md)

Embed a **real Node.js runtime inside your Android app's own process** — not a WebView, not a remote service, not a separate binary. Node runs in-process via a JNI bootstrap that calls `dlopen("libnode.so")` + `node::Start`, with a bidirectional bridge between JavaScript and Kotlin.

The runtime engine ships as a **separate package** ([aosp-node-runtime](https://github.com/fellow99/aosp-node-runtime)), so the bridge and the Node version evolve independently.

- Architecture/design doc: [`docs/000-工程规划.md`](docs/000-工程规划.md)
- On-device verification log: [`docs/002-真机验证记录.md`](docs/002-真机验证记录.md)

## Packages

| Package | Maven coordinate | Version | Contents |
|---|---|---|---|
| Bridge | `io.github.fellow99:node-bridge` | `0.2.0` | Kotlin API, JNI bootstrap (`libnode_bridge.so`), NAPI addon (`libnode_host_client.so`), JS payload |
| Runtime | `io.github.fellow99:node-runtime` | `26.13.0` | `libnode.so` + `libc++_shared.so` only ([aosp-node-runtime](https://github.com/fellow99/aosp-node-runtime)) |

### Releases — Maven Central (`io.github.fellow99:node-bridge`)

> ⚠️ **AARCH64 (arm64-v8a) only** — no x86 / x86_64 / arm32 artifacts.

| Version | OS | Arch | Link |
|---|---|---|---|
| 0.1.0 | Android | arm64-v8a | https://repo1.maven.org/maven2/io/github/fellow99/node-bridge/0.1.0/ |
| 0.2.0 | Android | arm64-v8a | https://repo1.maven.org/maven2/io/github/fellow99/node-bridge/0.2.0/ |

Requirements: `minSdk 24` · `arm64-v8a` · the engine is loaded at runtime (not compile-time).

## Quick start

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fellow99:node-bridge:0.2.0")
    runtimeOnly("io.github.fellow99:node-runtime:26.13.0") // engine, Node-version pinned
}
```

```kotlin
// Register host capabilities FIRST, then start (order matters).
val rt = NodeRuntime.getInstance(context)

rt.host.register("fs.pickFolder") { argsJson ->
    // Runs on the main thread; return a JSON string.
    """{"ok":true,"path":"/sdcard/Download"}"""
}

val result = rt.start(
    NodeRuntimeOptions(
        entry = "bootstrap.js",       // script node runs directly
        appEntry = "selfcheck.js",    // business entry resolved by bootstrap
        env = mapOf("MY_CONFIG" to "value"),
    ),
)
if (!result.ok) Log.e("node", "${result.state} ${result.message}") // failures stay visible

// Observe lifecycle state: Idle → Starting → Running → Exited/Failed
rt.state.collect { state -> /* ... */ }
```

Inside Node, call the host:

```js
const host = require('./host.js');           // provided by the library assets
const res = await host.invoke('fs.pickFolder', { title: 'Pick a folder' });
// res === { ok: true, path: '/sdcard/Download' }
```

## How it works

```
┌─────────────────────────────── App process ───────────────────────────────┐
│                                                                            │
│   Kotlin (NodeRuntime / NodeHostBridge)                                    │
│        ▲                              │ host requests (JSON over JNI)      │
│        │ result (JSON)                ▼                                    │
│   libnode_bridge.so  ◀── slot table ──▶  libnode_host_client.so (NAPI)     │
│        ▲ dlopen + dlsym                        ▲ process.dlopen            │
│        │                                       │                            │
│    libnode.so  (Node.js + V8, from node-runtime)  ◀── bootstrap/host.js    │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

- **`libnode_bridge.so`** links only `log`/`dl`. It never links `libnode`; it locates the engine with `dlopen` and `node::Start` with `dlsym`, so the bridge is decoupled from the Node version.
- **`libnode_host_client.so`** is a NAPI addon. Its `napi_*` symbols are intentionally left undefined (`-z undefs`) and resolved at runtime against the already-`dlopen`'d `libnode.so` (`RTLD_GLOBAL`) — guaranteeing Node's own NAPI implementation is used.
- **JS payload** (`bootstrap.js`, `host.js`, `selfcheck.js`) lives in AAR `assets`, which Android cannot `require` directly. The library materializes it into `filesDir/node-bridge` (idempotent) before start.

## Repository layout

```
aosp-node-bridge/
├── node-bridge/             # the AAR: Kotlin API + two .so + JS assets
│   └── src/main/
│       ├── cpp/             # node_bridge.c, node_host_client.c, bundled NAPI headers
│       └── java/org/fellow99/nodebridge/
├── payload/                 # JS payload (source of truth; copied into AAR assets)
├── samples/minimal-app/     # on-device probe (modern targetSdk 36 / legacy targetSdk 28)
├── tools/                   # build-bridge / smoke-host / release-maven / collect-evidence
└── docs/
```

## Building locally

The bridge builds with the NDK clang directly (no CMake/ninja required for the default path):

```bash
# Build the two .so into node-bridge/src/main/jniLibs/arm64-v8a
node tools/build-bridge.mjs

# Host-side gates without a device (JS syntax + bridge JSON contract)
node tools/smoke-host.mjs

# Build the probe APK
gradle :samples:minimal-app:assembleModernDebug
```

The bundled NAPI headers (`node-bridge/src/main/cpp/include/node/`) are the stable N-API surface and rarely change across Node majors. `libnode.so` itself is never needed at bridge build time.

## Constraints (reported, never silently degraded)

| Constraint | Detail |
|---|---|
| `node::Start` is once per process | A crash/exit is terminal (`NodeState.Exited`/`Failed`). A restartable form runs Node in a `:node` process. |
| `assets/` is not a filesystem path | The payload must be materialized to `filesDir` first (the library does this). |
| JIT must be verified on device | W^X/`execmem` on modern targetSdk can block V8 JIT. The probe's `/facts.jit` reports the verdict (JIT and WASM verified working on Android 15). |
| Engine has hard requirements | `libnode.so` must be `ET_DYN`, have no `PT_INTERP`, and export `_ZN4node5StartEiPPc`. |

## License

[MIT](LICENSE)
