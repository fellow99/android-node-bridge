aosp-node-bridge / node-runtime —— 第三方组件与许可

本模块只承载 **Node.js 运行时二进制**（`libnode.so`），不含本仓库自有的可执行代码。

## 当前产物（Phase 0 bring-up，**非**最终产物）

| 项 | 值 |
|---|---|
| 文件 | `libnode.so`（`arm64-v8a`） |
| Node | **v18.20.4** |
| 大小 | 62,475,584 B |
| SHA-256 | `7c907316beb6e78e34495926c9ac1befe079369b14650d508ea812929258250c` |
| 来源 | `nodejs-mobile-react-native@18.20.4`（npm，`package/android/libnode/bin/arm64-v8a/libnode.so`）→ 由 `tools/provision.mjs` 投放 |
| 性质 | 🔶 **仅用于 bring-up 与 P0-1/P0-2 真机探针**；目标形态是**自建 Node 24.x LTS**（见 `docs/000-工程规划.md` §4.6 / 附录 D） |
| SONAME | `libnode.so` |
| DT_NEEDED | `libm.so` / `libdl.so` / `liblog.so` / **`libc++_shared.so`** / `libc.so` |

## 许可

- Node.js 本体：MIT（含 V8 / OpenSSL / ICU / libuv / c-ares 等第三方组件，各自许可见 Node 官方 `LICENSE` 文件）。
- ⚠️ **待补**：`LICENSE`（Node 官方原文）必须在**自建产物**落地时一并放入本目录；
  bring-up 阶段的产物来自 npm 包，其许可原文见 `nodejs-mobile-react-native` 包内的 `LICENSE`。
  发布（Maven）前必须补齐，见规划 §9.5 G4。
- `libc++_shared.so` 来自 Android NDK r27c（Apache-2.0 / LLVM 例外条款），随 NDK 分发。
