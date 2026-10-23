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

#include <dlfcn.h>
#include <stddef.h>
#include <string.h>

#define ANB_NAME_CAP 128
#define ANB_ARGS_CAP 8192
#define ANB_RESULT_CAP 65536

typedef int (*anb_host_call_fn)(const char *name, const char *args, char *out, size_t cap);

static anb_host_call_fn g_host_call = NULL;
static int g_resolve_attempts = 0;

static anb_host_call_fn resolve_host_call(void) {
  if (g_host_call != NULL) return g_host_call;
  g_resolve_attempts++;
  void *sym = dlsym(RTLD_DEFAULT, "anb_host_call");
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
  napi_create_function(env, "invoke", NAPI_AUTO_LENGTH, Invoke, NULL, &fn);
  napi_set_named_property(env, exports, "invoke", fn);
  napi_create_function(env, "ping", NAPI_AUTO_LENGTH, Ping, NULL, &fn);
  napi_set_named_property(env, exports, "ping", fn);
  return exports;
}

/* Node 的 process.dlopen 只认这个符号名 */
__attribute__((visibility("default"))) napi_value napi_register_module_v1(napi_env env,
                                                                         napi_value exports) {
  return Init(env, exports);
}
