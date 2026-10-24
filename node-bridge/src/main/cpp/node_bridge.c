/*
 * node_bridge.c —— libnode_bridge.so：JNI 引导件（模块 002-jni-bootstrap）
 *
 * 职责（只做这五件事，别的一概不做）：
 *   ① JNI 导出极窄面：start / isRunning / stage / lastError / lastCrashJson /
 *      pollHostRequest / completeHostRequest / probe；
 *   ② detached bootstrap 线程（32 MB 栈）：stdio 重建 → env 装配 → dlopen → dlsym → node::Start；
 *   ③ stdio：fd0→/dev/null、fd1/2→各自 pipe + 独立 reader 线程（同时落文件与 logcat）；
 *   ④ 崩溃取证：信号 handler 内只 write(2)，写 `CRASH …` 行 + last-crash.json；
 *   ⑤ 宿主能力桥的**槽位表**（Node 线程 alloc/等在 condvar，宿主侧 poll/complete），
 *      并以 `anb_host_call` 导出给 NAPI addon（libnode_host_client.so）用 dlsym 取。
 *
 * 设计纪律（详见 docs/000-工程规划.md）：
 *   · argv 是连续内存，argc **不含**尾部 NULL 哨兵（哨兵不算 arg，否则 Node 5 ms 内段错误）；
 *   · 进程内 node::Start 只能一次 ⇒ g_dispatched 二次守卫，绝不重启；
 *   · 主线程绝不做 dlopen/node::Start（ANR）；
 *   · handler 内禁用 stdio / __android_log_write（非 async-signal-safe）；
 *   · 失败必须可见：每一次失败都写 stage + lastError + 日志。
 */

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define TAG "NODE_BRIDGE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_CANDIDATES 5
#define MAX_ARGS 32
#define MAX_FLAGS_LEN 4096

/* ── 全局状态（一次性、不可重启） ─────────────────────────────────────────── */

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile int g_dispatched = 0;   /* 已投递过启动（node::Start 每进程一次） */
static volatile int g_running = 0;      /* node::Start 已进入 */
static int g_pid = 0;

static char g_lib_dir[1024];
static char g_script_path[2048];
static char g_flags[MAX_FLAGS_LEN];
static char g_home[1024];
static char g_log_path[1200];
static char g_addon_dir[1024];
static char g_env_pairs[4096];
static volatile int g_no_stdio = 0;  /* 诊断开关：跳过 stdio 重建 */

static char g_stage[32] = "init";
static char g_last_error[1024];
static int g_log_fd = -1;

static void set_stage(const char *s) {
  pthread_mutex_lock(&g_lock);
  snprintf(g_stage, sizeof(g_stage), "%s", s ? s : "");
  pthread_mutex_unlock(&g_lock);
}

static void set_error(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  pthread_mutex_lock(&g_lock);
  vsnprintf(g_last_error, sizeof(g_last_error), fmt, ap);
  pthread_mutex_unlock(&g_lock);
  va_end(ap);
  LOGE("error: %s", g_last_error);
}

/* ── 文件日志（同时进 logcat） ─────────────────────────────────────────────── */

static void write_line(const char *stream, const char *text) {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  long long ms = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;

  char line[4096];
  int n = snprintf(line, sizeof(line), "%s %lld %s\n", stream ? stream : "out", ms,
                   text ? text : "");
  if (n <= 0) return;
  if (n >= (int)sizeof(line)) n = (int)sizeof(line) - 1;

  int lvl = (stream && strcmp(stream, "err") == 0) ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO;
  __android_log_write(lvl, TAG, line);
  if (g_log_fd >= 0) {
    ssize_t ignored = write(g_log_fd, line, (size_t)n);
    (void)ignored;
  }
}

/* 供其它 TU / 本文件内使用 */
static void logf_line(const char *stream, const char *fmt, ...) {
  char buf[3072];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  write_line(stream, buf);
}

/* ── stdio 重建 + 排空线程 ────────────────────────────────────────────────── */

typedef struct {
  int fd;
  const char *stream;
} reader_arg;

static void *stdio_reader(void *p) {
  reader_arg *a = (reader_arg *)p;
  char buf[2048];
  size_t used = 0;
  for (;;) {
    ssize_t n = read(a->fd, buf + used, sizeof(buf) - used - 1);
    if (n <= 0) break;
    used += (size_t)n;
    size_t start = 0;
    for (size_t i = 0; i < used; i++) {
      if (buf[i] == '\n') {
        buf[i] = 0;
        write_line(a->stream, buf + start);
        start = i + 1;
      }
    }
    if (start > 0) {
      memmove(buf, buf + start, used - start);
      used -= start;
    }
    if (used >= sizeof(buf) - 2) { /* 单行超长：强制切一块，绝不无限增长 */
      buf[used] = 0;
      write_line(a->stream, buf);
      used = 0;
    }
  }
  if (used > 0) {
    buf[used] = 0;
    write_line(a->stream, buf);
  }
  return NULL;
}

static int pipe_reader(pthread_t *th, int *rfd, const char *stream) {
  static reader_arg args[2];
  static int idx = 0;
  if (idx >= 2 || th == NULL) return -1;
  args[idx].fd = *rfd;
  args[idx].stream = stream;
  int rc = pthread_create(th, NULL, stdio_reader, &args[idx]);
  if (rc == 0) {
    pthread_detach(*th);
    idx++;
  }
  return rc;
}

static int rebuild_stdio(void) {
  int devnull = open("/dev/null", O_RDONLY);
  if (devnull >= 0) {
    dup2(devnull, STDIN_FILENO);
    if (devnull != STDIN_FILENO) close(devnull);
  }
  int out_p[2], err_p[2];
  if (pipe(out_p) != 0 || pipe(err_p) != 0) {
    set_error("pipe() 失败：%s", strerror(errno));
    return -1;
  }
  dup2(out_p[1], STDOUT_FILENO);
  dup2(err_p[1], STDERR_FILENO);
  close(out_p[1]);
  close(err_p[1]);

  pthread_t t1, t2;
  int r1 = pipe_reader(&t1, &out_p[0], "out");
  int r2 = pipe_reader(&t2, &err_p[0], "err");
  if (r1 != 0 || r2 != 0) {
    set_error("reader 线程创建失败：%d/%d", r1, r2);
    return -1;
  }
  return 0;
}

/* ── 崩溃取证（handler 内只允许 write(2)/open(2)）────────────────────────── */

static char g_crash_json_path[1200];

static void crash_handler(int sig, siginfo_t *si, void *uc) {
  /* 手写十进制，避免 snprintf/stdio（非 async-signal-safe） */
  char line[256];
  size_t p = 0;
  const char *head = "\nCRASH sig=";
  for (const char *c = head; *c && p < sizeof(line) - 1; c++) line[p++] = *c;
  { /* sig */
    char num[12];
    int n = 0, v = sig;
    if (v == 0) num[n++] = '0';
    while (v > 0 && n < 11) { num[n++] = (char)('0' + (v % 10)); v /= 10; }
    while (n > 0 && p < sizeof(line) - 1) line[p++] = num[--n];
  }
  const char *mid = " si_addr=";
  for (const char *c = mid; *c && p < sizeof(line) - 1; c++) line[p++] = *c;
  {
    uintptr_t addr = si ? (uintptr_t)si->si_addr : 0;
    char num[20];
    int n = 0;
    if (addr == 0) num[n++] = '0';
    while (addr > 0 && n < 19) { num[n++] = "0123456789abcdef"[(addr & 0xf)]; addr >>= 4; }
    if (p < sizeof(line) - 1) line[p++] = '0';
    if (p < sizeof(line) - 1) line[p++] = 'x';
    while (n > 0 && p < sizeof(line) - 1) line[p++] = num[--n];
  }
  const char *tail = "\n";
  for (const char *c = tail; *c && p < sizeof(line) - 1; c++) line[p++] = *c;

  if (g_log_fd >= 0) {
    ssize_t ignored = write(g_log_fd, line, p);
    (void)ignored;
  }
  if (g_crash_json_path[0] != 0) {
    int fd = open(g_crash_json_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
      const char *json = "{\"stage\":\"crash\",\"signal\":";
      ssize_t ignored = write(fd, json, strlen(json));
      (void)ignored;
      char num[12];
      int n = 0, v = sig;
      while (v > 0 && n < 11) { num[n++] = (char)('0' + (v % 10)); v /= 10; }
      while (n > 0) { ssize_t ig = write(fd, &num[--n], 1); (void)ig; }
      ignored = write(fd, "}\n", 2);
      (void)ignored;
      close(fd);
    }
  }
  signal(sig, SIG_DFL);
  raise(sig);
}

static void install_crash_handlers(void) {
  static const int sigs[] = {SIGSEGV, SIGILL, SIGBUS, SIGABRT, SIGFPE, SIGTRAP};
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = crash_handler;
  sa.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);
  for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); i++) {
    sigaction(sigs[i], &sa, NULL);
  }
}

/* ── 宿主能力桥：槽位表 ──────────────────────────────────────────────────── */

#define ANB_SLOTS 8
#define ANB_NAME_CAP 128
#define ANB_ARGS_CAP 8192
#define ANB_RESULT_CAP 65536
#define ANB_TIMEOUT_MS 10000

enum { SLOT_FREE = 0, SLOT_PENDING = 1, SLOT_INFLIGHT = 2, SLOT_DONE = 3 };

typedef struct {
  int state;
  long long id;
  char name[ANB_NAME_CAP];
  char args[ANB_ARGS_CAP];
  char result[ANB_RESULT_CAP];
  pthread_cond_t cv;
} anb_slots_t;

static anb_slots_t g_slots[ANB_SLOTS];
static pthread_mutex_t g_slot_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t g_slot_once = PTHREAD_ONCE_INIT;
static long long g_slot_seq = 0;

static void slots_init_once(void) {
  for (int i = 0; i < ANB_SLOTS; i++) {
    g_slots[i].state = SLOT_FREE;
    pthread_cond_init(&g_slots[i].cv, NULL);
  }
}

/*
 * Node 线程调用：分配槽位 → 等宿主 complete（超时 10 s）。
 * 返回 0 成功、-1 无空槽、-2 超时。
 */
__attribute__((visibility("default"))) int
anb_host_call(const char *name, const char *args, char *out, size_t cap) {
  if (name == NULL) return -1;
  pthread_once(&g_slot_once, slots_init_once);

  pthread_mutex_lock(&g_slot_lock);
  anb_slots_t *slot = NULL;
  for (int i = 0; i < ANB_SLOTS; i++) {
    if (g_slots[i].state == SLOT_FREE) { slot = &g_slots[i]; break; }
  }
  if (slot == NULL) {
    pthread_mutex_unlock(&g_slot_lock);
    return -1;
  }
  slot->id = ++g_slot_seq;
  snprintf(slot->name, sizeof(slot->name), "%s", name);
  snprintf(slot->args, sizeof(slot->args), "%s", args ? args : "");
  slot->result[0] = 0;
  slot->state = SLOT_PENDING;

  struct timespec deadline;
  clock_gettime(CLOCK_REALTIME, &deadline);
  deadline.tv_sec += ANB_TIMEOUT_MS / 1000;
  deadline.tv_nsec += (long)(ANB_TIMEOUT_MS % 1000) * 1000000L;
  if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec += 1; deadline.tv_nsec -= 1000000000L; }

  while (slot->state != SLOT_DONE) {
    int rc = pthread_cond_timedwait(&slot->cv, &g_slot_lock, &deadline);
    if (rc == ETIMEDOUT) {
      slot->state = SLOT_FREE;
      pthread_mutex_unlock(&g_slot_lock);
      return -2;
    }
  }
  if (out != NULL && cap > 0) {
    snprintf(out, cap, "%s", slot->result);
  }
  slot->state = SLOT_FREE;
  pthread_mutex_unlock(&g_slot_lock);
  return 0;
}

/* ── dlopen/dlsym 候选链 ─────────────────────────────────────────────────── */

typedef int (*node_start_fn)(int argc, char **argv);
static const char *kStartSym = "_ZN4node5StartEiPPc";

/*
 * 顺序：① RTLD_DEFAULT（若宿主已 System.loadLibrary("node")）
 *       ② 裸名 libnode.so（Android linker 会按 APK 的 native lib 目录解析）
 *       ③ <libDir>/libnode.so   ④ <libDir>/libs/arm64/libnode.so   ⑤ <libDir>/libs/arm64-v8a/libnode.so
 */
static node_start_fn resolve_start(char *hit, size_t hit_cap) {
  void *sym = dlsym(RTLD_DEFAULT, kStartSym);
  if (sym != NULL) {
    snprintf(hit, hit_cap, "RTLD_DEFAULT");
    logf_line("out", "[bridge] dlsym(RTLD_DEFAULT, node::Start) -> %p", sym);
    return (node_start_fn)sym;
  }

  char cands[MAX_CANDIDATES][1200];
  int n = 0;
  snprintf(cands[n++], sizeof(cands[0]), "libnode.so");
  if (g_lib_dir[0]) {
    snprintf(cands[n++], sizeof(cands[0]), "%s/libnode.so", g_lib_dir);
    snprintf(cands[n++], sizeof(cands[0]), "%s/libs/arm64/libnode.so", g_lib_dir);
    snprintf(cands[n++], sizeof(cands[0]), "%s/libs/arm64-v8a/libnode.so", g_lib_dir);
  }
  for (int i = 0; i < n; i++) {
    void *h = dlopen(cands[i], RTLD_NOW | RTLD_GLOBAL);
    logf_line("out", "[bridge] dlopen('%s') -> %s", cands[i],
              h != NULL ? "OK" : (dlerror() ? dlerror() : "NULL"));
    if (h == NULL) continue;
    sym = dlsym(h, kStartSym);
    if (sym != NULL) {
      snprintf(hit, hit_cap, "%s", cands[i]);
      return (node_start_fn)sym;
    }
    set_error("dlopen 成功但缺 node::Start 符号：%s（产物可能不是 --shared 构建）", cands[i]);
  }
  return NULL;
}

/* ── env 装配 ────────────────────────────────────────────────────────────── */

static void apply_env(const char *env_pairs) {
  setenv("UV_USE_IO_URING", "0", 1);
  if (g_home[0]) {
    setenv("HOME", g_home, 1);
    setenv("USERPROFILE", g_home, 1);
    char tmp[1024];
    snprintf(tmp, sizeof(tmp), "%s/tmp", g_home);
    mkdir(tmp, 0755);
    setenv("TMPDIR", tmp, 1);
    setenv("TMP", tmp, 1);
    setenv("TEMP", tmp, 1);
  }
  unsetenv("NODE_OPTIONS");
  if (g_addon_dir[0]) setenv("NODE_BRIDGE_ADDON_DIR", g_addon_dir, 1);
  if (g_home[0]) setenv("NODE_BRIDGE_HOME", g_home, 1);

  if (env_pairs != NULL && env_pairs[0] != 0) {
    char buf[4096];
    snprintf(buf, sizeof(buf), "%s", env_pairs);
    char *save = NULL;
    for (char *line = strtok_r(buf, "\n", &save); line != NULL; line = strtok_r(NULL, "\n", &save)) {
      char *eq = strchr(line, '=');
      if (eq == NULL) continue;
      *eq = 0;
      setenv(line, eq + 1, 1);
    }
  }
}

/* ── bootstrap 线程 ──────────────────────────────────────────────────────── */

static void *bootstrap(void *arg) {
  (void)arg;

  set_stage("stdio");
  if (rebuild_stdio() != 0) {
    set_stage("failed");
    return NULL;
  }
  logf_line("out", "[bridge] pid=%d stage=stdio ok（fd0=/dev/null, fd1/2=pipe+reader）", getpid());

  install_crash_handlers();

  set_stage("env");
  apply_env(g_env_pairs);
  logf_line("out", "[bridge] HOME=%s TMPDIR=%s", getenv("HOME"), getenv("TMPDIR"));

  set_stage("dlopen");
  char hit[1200];
  node_start_fn start = resolve_start(hit, sizeof(hit));
  if (start == NULL) {
    if (g_last_error[0] == 0) set_error("dlopen/dlsym 失败：找不到 %s", kStartSym);
    set_stage("failed");
    return NULL;
  }
  logf_line("out", "[bridge] 命中引擎：%s", hit);

  set_stage("dlsym");
  /* 组 argv：argv[0]="node" → flags 按空白切词 → scriptPath；argc **不含**尾部 NULL */
  char *argv[MAX_ARGS];
  int argc = 0;
  argv[argc++] = (char *)"node";

  static char flags_buf[MAX_FLAGS_LEN];
  snprintf(flags_buf, sizeof(flags_buf), "%s", g_flags);
  char *fsave = NULL;
  for (char *tok = strtok_r(flags_buf, " \t", &fsave); tok != NULL && argc < MAX_ARGS - 2;
       tok = strtok_r(NULL, " \t", &fsave)) {
    argv[argc++] = tok;
  }
  argv[argc++] = g_script_path;
  argv[argc] = NULL; /* 哨兵：不算进 argc */

  logf_line("out", "[bridge] argv(%d):", argc);
  for (int i = 0; i < argc; i++) logf_line("out", "[bridge]   argv[%d]=%s", i, argv[i]);

  set_stage("start");
  g_running = 1;
  logf_line("out", "[bridge] calling node::Start(argc=%d) ...", argc);
  int rc = start(argc, argv);
  g_running = 0;
  logf_line("out", "[bridge] node::Start returned rc=%d errno=%d", rc, errno);
  set_stage("exited");
  return NULL;
}

/* ── JNI 工具 ────────────────────────────────────────────────────────────── */

static int jstr(JNIEnv *env, jstring s, char *dst, size_t cap) {
  if (dst == NULL || cap == 0) return -1;
  dst[0] = 0;
  if (s == NULL) return 0;
  const char *c = (*env)->GetStringUTFChars(env, s, NULL);
  if (c == NULL) return -1;
  snprintf(dst, cap, "%s", c);
  (*env)->ReleaseStringUTFChars(env, s, c);
  return 0;
}

static jstring jret(JNIEnv *env, const char *s) {
  return (*env)->NewStringUTF(env, s ? s : "");
}

/* ── JNI 导出面（类：org.fellow99.nodebridge.NativeBridge）────────────────── */

JNIEXPORT jint JNICALL
Java_org_fellow99_nodebridge_NativeBridge_start(JNIEnv *env, jobject thiz, jstring libDir,
                                                jstring scriptPath, jstring flags, jstring home,
                                                jstring logPath, jstring addonDir,
                                                jstring envPairs) {
  (void)thiz;
  pthread_mutex_lock(&g_lock);
  if (g_dispatched) {
    pthread_mutex_unlock(&g_lock);
    LOGE("start() 二次调用被拒（node::Start 每进程一次）");
    return -1;
  }
  jstr(env, libDir, g_lib_dir, sizeof(g_lib_dir));
  jstr(env, scriptPath, g_script_path, sizeof(g_script_path));
  jstr(env, flags, g_flags, sizeof(g_flags));
  jstr(env, home, g_home, sizeof(g_home));
  jstr(env, logPath, g_log_path, sizeof(g_log_path));
  jstr(env, addonDir, g_addon_dir, sizeof(g_addon_dir));
  g_dispatched = 1;
  pthread_mutex_unlock(&g_lock);

  if (g_script_path[0] == 0) {
    set_error("scriptPath 为空");
    return -2;
  }

  /* 日志文件 + 崩溃记录路径（先开 fd，后面 dup2 不会覆盖它） */
  if (g_log_path[0] != 0) {
    g_log_fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    snprintf(g_crash_json_path, sizeof(g_crash_json_path), "%s.crash.json", g_log_path);
  }

  /* envPairs：落到全局，bootstrap 线程里 apply_env 时消费 */
  jstr(env, envPairs, g_env_pairs, sizeof(g_env_pairs));

  pthread_attr_t attr;
  pthread_attr_init(&attr);
  pthread_attr_setstacksize(&attr, 32 * 1024 * 1024); /* V8 递归/深调用需要大栈 */
  pthread_t th;
  int rc = pthread_create(&th, &attr, bootstrap, NULL);
  pthread_attr_destroy(&attr);
  if (rc != 0) {
    set_error("pthread_create 失败：%d", rc);
    return rc;
  }
  pthread_detach(th);
  return 0;
}

JNIEXPORT jboolean JNICALL Java_org_fellow99_nodebridge_NativeBridge_isRunning(JNIEnv *env,
                                                                              jobject thiz) {
  (void)env;
  (void)thiz;
  return (jboolean)(g_running ? JNI_TRUE : JNI_FALSE);
}

JNIEXPORT jstring JNICALL Java_org_fellow99_nodebridge_NativeBridge_stage(JNIEnv *env,
                                                                         jobject thiz) {
  (void)thiz;
  char buf[32];
  pthread_mutex_lock(&g_lock);
  snprintf(buf, sizeof(buf), "%s", g_stage);
  pthread_mutex_unlock(&g_lock);
  return jret(env, buf);
}

JNIEXPORT jstring JNICALL Java_org_fellow99_nodebridge_NativeBridge_lastError(JNIEnv *env,
                                                                             jobject thiz) {
  (void)thiz;
  char buf[1024];
  pthread_mutex_lock(&g_lock);
  snprintf(buf, sizeof(buf), "%s", g_last_error);
  pthread_mutex_unlock(&g_lock);
  return jret(env, buf);
}

JNIEXPORT jstring JNICALL Java_org_fellow99_nodebridge_NativeBridge_lastCrashJson(JNIEnv *env,
                                                                                 jobject thiz) {
  (void)thiz;
  char buf[2048];
  buf[0] = 0;
  if (g_crash_json_path[0] == 0) return jret(env, "");
  int fd = open(g_crash_json_path, O_RDONLY);
  if (fd < 0) return jret(env, "");
  ssize_t n = read(fd, buf, sizeof(buf) - 1);
  close(fd);
  buf[n > 0 ? n : 0] = 0;
  return jret(env, buf);
}

/* 返回 "<id>\x01<name>\x01<args>"；无请求返回 "" */
JNIEXPORT jstring JNICALL Java_org_fellow99_nodebridge_NativeBridge_pollHostRequest(JNIEnv *env,
                                                                                   jobject thiz) {
  (void)thiz;
  pthread_once(&g_slot_once, slots_init_once);
  char out[ANB_ARGS_CAP + 256];
  out[0] = 0;
  pthread_mutex_lock(&g_slot_lock);
  for (int i = 0; i < ANB_SLOTS; i++) {
    if (g_slots[i].state == SLOT_PENDING) {
      g_slots[i].state = SLOT_INFLIGHT;
      snprintf(out, sizeof(out), "%lld\x01%s\x01%s", g_slots[i].id, g_slots[i].name,
               g_slots[i].args);
      break;
    }
  }
  pthread_mutex_unlock(&g_slot_lock);
  return jret(env, out);
}

JNIEXPORT jint JNICALL
Java_org_fellow99_nodebridge_NativeBridge_completeHostRequest(JNIEnv *env, jobject thiz, jlong id,
                                                             jstring resultJson) {
  (void)thiz;
  char result[ANB_RESULT_CAP];
  jstr(env, resultJson, result, sizeof(result));
  int rc = -1;
  pthread_mutex_lock(&g_slot_lock);
  for (int i = 0; i < ANB_SLOTS; i++) {
    if (g_slots[i].state == SLOT_INFLIGHT && g_slots[i].id == (long long)id) {
      snprintf(g_slots[i].result, sizeof(g_slots[i].result), "%s", result);
      g_slots[i].state = SLOT_DONE;
      pthread_cond_signal(&g_slots[i].cv);
      rc = 0;
      break;
    }
  }
  pthread_mutex_unlock(&g_slot_lock);
  return rc;
}

/* 只做探测，不启动：返回 JSON 字符串（给探针 UI 用） */
/* 供 NAPI addon（libnode_host_client.so）setBridge(handle) 用：
 * 返回引导件自身（当前加载对象）的 handle（取任意本对象符号的地址）。
 * 为什么需要它：Android 的 JNI 类加载器命名空间把 libnode_bridge.so 与 Node 侧
 * process.dlopen 的 addon 隔离，dlsym(RTLD_DEFAULT) 搜不到另一命名空间的符号，
 * 而按路径二次 dlopen 又被 linker 拒绝（"library ... not found"，实测）。
 * 经 JNI 把 handle 传过去，addon 再 dlsym(handle, "anb_host_call") 即可。 */
JNIEXPORT jlong JNICALL
Java_org_fellow99_nodebridge_NativeBridge_bridgeHandle(JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  return (jlong)(intptr_t)anb_host_call;
}

JNIEXPORT jstring JNICALL Java_org_fellow99_nodebridge_NativeBridge_probe(JNIEnv *env, jobject thiz,
                                                                         jstring libDir) {
  (void)thiz;
  char dir[1024];
  jstr(env, libDir, dir, sizeof(dir));
  char hit[1200];
  hit[0] = 0;
  char saved[1024];
  snprintf(saved, sizeof(saved), "%s", g_lib_dir);
  snprintf(g_lib_dir, sizeof(g_lib_dir), "%s", dir);
  node_start_fn fn = resolve_start(hit, sizeof(hit));
  snprintf(g_lib_dir, sizeof(g_lib_dir), "%s", saved);

  char out[2048];
  snprintf(out, sizeof(out),
           "{\"libDir\":\"%s\",\"found\":%s,\"hit\":\"%s\",\"sym\":\"%s\",\"dlerror\":\"%s\"}", dir,
           fn != NULL ? "true" : "false", hit, kStartSym, g_last_error);
  return jret(env, out);
}
