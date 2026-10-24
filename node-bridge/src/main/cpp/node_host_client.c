/*
 * node_host_client.c —— libnode_host_client.so：Node 侧的宿主能力入口（NAPI addon）
 *
 * 为什么要**另一个** .so（而不是把 addon 能力塞进 libnode_bridge.so）：
 *   · libnode_bridge.so 由 `System.loadLibrary("nodebridge")` 加载（JNI 侧），它的符号可见性、
 *     依赖（-llog）、以及「被 Node 的 process.dlopen 二次 dlopen」这三件事互相干扰；
 *   · 本 .so **不链接任何 napi_\* 以外的运行时实现**：`napi_*` 由已 `RTLD_GLOBAL` 加载的
 *     `libnode.so` 在运行期解析（链接期显式 `-l:libnode.so` 只为留下 `DT_NEEDED libnode.so`，
 *     Android 的 linker 必须看到这条 NEEDED 才会连接 addon 与引擎）；
 *   · 真正的阻塞逻辑在 libnode_bridge.so 的 `anb_host_call`（导出符号），这里只 dlsym 转发
 *     ⇒ 两个 .so 共享同一份槽位表语义，Kotlin 侧继续 poll/complete。
 *
 * 失败纪律：找不到 `anb_host_call`（说明引导件没被加载）⇒ **抛错**，绝不返回空串。
 */

#include <node_api.h>
#include <js_native_api.h> /* NAPI C API（node_api.h 只含模块导出宏） */
#include <stdint.h>

#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NODE_BRIDGE", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NODE_BRIDGE", __VA_ARGS__)

#define ANB_NAME_CAP 128
#define ANB_ARGS_CAP 8192
#define ANB_RESULT_CAP 65536

typedef int (*anb_host_call_fn)(const char *name, const char *args, char *out, size_t cap);
typedef void *(*anb_self_handle_fn)(void);

static anb_host_call_fn g_host_call = NULL;
static int g_resolve_attempts = 0;

/*
 * 为什么不能只靠 dlsym(RTLD_DEFAULT)：Android 上 JNI 类加载器命名空间会把
 * System.loadLibrary 进来的 libnode_bridge.so 与 Node 侧 process.dlopen 的
 * addon 隔离，RTLD_DEFAULT 只搜**当前加载对象**的依赖闭包，搜不到另一个命名空间的库。
 * 兜底：按 NODE_BRIDGE_ADDON_DIR 显式 dlopen 引导件（同路径二次 dlopen 只加引用计数，
 * 不会造成双份状态）。
 */
static anb_host_call_fn resolve_host_call(void) {
  if (g_host_call != NULL) return g_host_call;
  g_resolve_attempts++;
  void *sym = dlsym(RTLD_DEFAULT, "anb_host_call");
  if (sym == NULL) {
    /* 兜底 ①：裸 soname dlopen（linker 可能从已加载集合里解析） */
    void *h = dlopen("libnode_bridge.so", RTLD_NOW | RTLD_GLOBAL);
    if (h == NULL) {
      const char *dir = getenv("NODE_BRIDGE_ADDON_DIR");
      if (dir != NULL && dir[0] != 0) {
        char cand[1600];
        snprintf(cand, sizeof(cand), "%s/libnode_bridge.so", dir);
        h = dlopen(cand, RTLD_NOW | RTLD_GLOBAL);
      }
    }
    if (h != NULL) {
      sym = dlsym(h, "anb_host_call");
      if (sym != NULL) LOGI("[anb] 回退 dlopen 命中 anb_host_call");
      else LOGE("[anb] dlopen 成功但缺 anb_host_call");
    } else {
      LOGE("[anb] dlopen libnode_bridge.so 失败：%s（可改由 Kotlin 侧调 setBridge(handle) 显式传）",
           dlerror() ? dlerror() : "?");
    }
  }
  if (sym == NULL) return NULL;
  g_host_call = (anb_host_call_fn)sym;
  return g_host_call;
}

/* 读一个字符串参数到缓冲；返回 0 成功、-1 失败（已抛错） */
static int read_arg(napi_env env, napi_value *argv, size_t argc, size_t idx, char *dest, size_t cap,
                    const char *label) {
  dest[0] = 0;
  if (idx >= argc) return 0;
  size_t needed = 0;
  if (napi_get_value_string_utf8(env, argv[idx], NULL, 0, &needed) != napi_ok) {
    char msg[160];
    napi_throw_type_error(env, NULL, label);
    (void)msg;
    return -1;
  }
  if (needed + 1 > cap) {
    napi_throw_range_error(env, NULL, "argument too long");
    return -1;
  }
  size_t written = 0;
  if (napi_get_value_string_utf8(env, argv[idx], dest, cap, &written) != napi_ok) {
    napi_throw_error(env, NULL, "read string failed");
    return -1;
  }
  return 0;
}

/* invoke(name: string, argsJson: string) -> resultJson: string */
static napi_value Invoke(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2];
  napi_value undef;
  napi_get_undefined(env, &undef);
  argv[0] = undef;
  argv[1] = undef;
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok) {
    napi_throw_error(env, NULL, "invoke: cb_info failed");
    return undef;
  }

  static char name[ANB_NAME_CAP];
  static char args[ANB_ARGS_CAP];
  if (read_arg(env, argv, argc, 0, name, sizeof(name), "invoke: name must be string") != 0) {
    return undef;
  }
  if (read_arg(env, argv, argc, 1, args, sizeof(args), "invoke: args must be JSON string") != 0) {
    return undef;
  }

  anb_host_call_fn fn = resolve_host_call();
  if (fn == NULL) {
    char msg[192];
    /* 手工拼提示，避免依赖 snprintf 系列在 napi 上下文里的额外开销 */
    const char *a = "anb_host_call not found（引导件未加载？尝试次数=";
    size_t p = 0;
    for (const char *c = a; *c && p < sizeof(msg) - 8; c++) msg[p++] = *c;
    msg[p++] = (char)('0' + (g_resolve_attempts % 10));
    msg[p++] = ')';
    msg[p] = 0;
    napi_throw_error(env, "ERR_ANB_NO_HOST_CALL", msg);
    return undef;
  }

  static char out[ANB_RESULT_CAP];
  out[0] = 0;
  int rc = fn(name, args, out, sizeof(out));
  if (rc != 0) {
    napi_throw_error(env, rc == -2 ? "ERR_ANB_TIMEOUT" : "ERR_ANB_HOST_CALL",
                     rc == -2 ? "host call timeout" : "host call failed (no free slot or bad request)");
    return undef;
  }

  napi_value ret;
  if (napi_create_string_utf8(env, out, NAPI_AUTO_LENGTH, &ret) != napi_ok) {
    napi_throw_error(env, NULL, "invoke: create result string failed");
    return undef;
  }
  return ret;
}

/* setBridge(handle)：Node 侧把引导件的加载对象 handle 传进来（绕开命名空间隔离）。
 * 用法：addon.setBridge(NativeBridge.bridgeHandle())。返回 0 成功、-1 失败。 */
static napi_value SetBridge(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  if (argc < 1) {
    napi_throw_type_error(env, NULL, "setBridge(handle)");
    return argv[0];
  }
  napi_valuetype t = napi_undefined;
  if (napi_typeof(env, argv[0], &t) != napi_ok || t != napi_bigint) {
    napi_throw_type_error(env, NULL, "handle 必须是 bigint（外部指针）");
    return argv[0];
  }
  int64_t handle = 0;
  bool lossless = false;
  if (napi_get_value_bigint_int64(env, argv[0], &handle, &lossless) != napi_ok) lossless = true;
  if (lossless) {
    napi_throw_range_error(env, NULL, "handle 溢出");
    return argv[0];
  }
  void *h = (void *)(intptr_t)handle;
  void *sym = dlsym(h, "anb_host_call");
  if (sym == NULL) {
    napi_throw_error(env, "ERR_ANB_BAD_HANDLE", "dlsym(handle, anb_host_call) 失败");
    napi_value zero;
    napi_create_int32(env, -1, &zero);
    return zero;
  }
  g_host_call = (anb_host_call_fn)sym;
  LOGI("[anb] setBridge 命中 anb_host_call");
  napi_value one;
  napi_create_int32(env, 0, &one);
  return one;
}

/* ping() -> "pong:<attempts>"：只用于验证 addon 与引导件的符号链是否通 */
static napi_value Ping(napi_env env, napi_callback_info info) {
  (void)info;
  char buf[48];
  const char *head = "pong:";
  size_t p = 0;
  for (const char *c = head; *c; c++) buf[p++] = *c;
  buf[p++] = (resolve_host_call() != NULL) ? '1' : '0';
  buf[p] = 0;
  napi_value ret;
  napi_create_string_utf8(env, buf, NAPI_AUTO_LENGTH, &ret);
  return ret;
}

static napi_value Init(napi_env env, napi_value exports) {
  napi_value fn;
  napi_create_function(env, "setBridge", NAPI_AUTO_LENGTH, SetBridge, NULL, &fn);
  napi_set_named_property(env, exports, "setBridge", fn);
  napi_create_function(env, "invoke", NAPI_AUTO_LENGTH, Invoke, NULL, &fn);
  napi_set_named_property(env, exports, "invoke", fn);
  napi_create_function(env, "ping", NAPI_AUTO_LENGTH, Ping, NULL, &fn);
  napi_set_named_property(env, exports, "ping", fn);

  /* 自举：Node 侧 process.dlopen 加载本 addon 时，libnode_bridge.so 通常已在进程内
   * （JNI 引导件先加载）。此时 dlopen 回退能命中；若命名空间隔离导致失败，
   * 由宿主在首次 invoke 前调 setBridge(handle)（handle 经 NativeBridge.bridgeHandle() 拿）。 */
  /* 自举：若 libnode_bridge.so 与本 addon 在同一 linker 命名空间，dlsym/dlopen 回退直接命中；
   * 否则（Android 的 JNI 类加载器命名空间隔离）等宿主注入 handle（NODE_BRIDGE_BRIDGE_HANDLE → setBridge）。 */
  (void)resolve_host_call();
  return exports;
}

/* Node 的 process.dlopen 只认这个符号名 */
__attribute__((visibility("default"))) napi_value napi_register_module_v1(napi_env env,
                                                                         napi_value exports) {
  return Init(env, exports);
}
