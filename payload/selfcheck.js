'use strict';

/*
 * selfcheck.js —— 端到端自检载荷（模块 006-observability 的探针）
 *
 * 一次成功运行会暴露：
 *   GET /facts  → 这台设备上「Node 真的在跑 + JIT/WASM 是否可用 + 文件可写可读 + 宿主桥通不通」
 *   GET /log    → 本次运行的日志尾（含原生层 reader 转发的 stdout/stderr）
 *   GET /diag   → 状态聚合
 *   GET /host?name=echo&args={} → 直接打一次宿主能力（验证 Node → Kotlin 链路）
 *
 * 这份 JSON 就是**一次真机成功运行的证书**（规划 §5.7）——不要用「logcat 有没有那行」当判据。
 */

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const host = require('./host.js');

const PORT = Number(process.env.NODE_BRIDGE_PORT || 15577);
const START_AT = Date.now();

// ── JIT / WASM 判定（P0-1 的核心指标）──────────────────────────────────────────

function probeJit() {
  const out = { jitless: process.execArgv.includes('--jitless') };

  // ① WebAssembly：--jitless 下 WebAssembly 未定义（OHOS 实测同型结论）
  try {
    if (typeof WebAssembly === 'undefined') {
      out.wasm = 'undefined';
    } else {
      // 一个最小的合法 wasm 模块：(module) 的二进制头 + version
      const minimal = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
      out.wasm = WebAssembly.validate(minimal) ? 'ok' : 'invalid';
    }
  } catch (e) {
    out.wasm = 'throw:' + e.message;
  }

  // ② 热循环：JIT 生效时 2e6 次迭代应在数十毫秒内
  try {
    const t0 = process.hrtime.bigint();
    let acc = 0;
    for (let i = 0; i < 2e6; i++) acc = (acc + i * 3) % 1000003;
    const t1 = process.hrtime.bigint();
    out.hotLoopMs = Number(t1 - t0) / 1e6;
    out.hotLoopAcc = acc;
  } catch (e) {
    out.hotLoopMs = -1;
    out.hotLoopError = e.message;
  }
  return out;
}

// ── 文件系统：谁写谁读（C3 纪律）──────────────────────────────────────────────

function probeFs() {
  const out = { dir: null, roundTrip: false, error: null };
  try {
    const dir = path.join(process.env.NODE_BRIDGE_HOME || os.homedir(), 'payload');
    fs.mkdirSync(dir, { recursive: true });
    out.dir = dir;
    const f = path.join(dir, 'fs-probe.txt');
    const payload = 'anb:' + Date.now();
    fs.writeFileSync(f, payload);
    const back = fs.readFileSync(f, 'utf8');
    out.roundTrip = back === payload;
    fs.unlinkSync(f);
  } catch (e) {
    out.error = e.message;
  }
  return out;
}

// ── 线程 ─────────────────────────────────────────────────────────────────────

function probeWorker() {
  return new Promise((resolve) => {
    let wt;
    try {
      wt = require('worker_threads');
    } catch (e) {
      resolve({ available: false, error: e.message });
      return;
    }
    if (!wt || !wt.Worker) {
      resolve({ available: false, error: 'Worker 未定义' });
      return;
    }
    const timer = setTimeout(() => resolve({ available: true, ran: false, error: 'timeout' }), 5000);
    try {
      const w = new wt.Worker('require("worker_threads").parentPort.postMessage(21*2)', { eval: true });
      w.on('message', (m) => {
        clearTimeout(timer);
        resolve({ available: true, ran: true, result: m });
        w.terminate();
      });
      w.on('error', (e) => {
        clearTimeout(timer);
        resolve({ available: true, ran: false, error: String(e && e.message) });
      });
    } catch (e) {
      clearTimeout(timer);
      resolve({ available: true, ran: false, error: String(e && e.message) });
    }
  });
}

// ── 宿主桥 ───────────────────────────────────────────────────────────────────

async function probeHost() {
  const ping = host.ping();
  const out = { ping };
  try {
    out.echo = await host.invoke('echo', { from: 'node', at: Date.now() });
  } catch (e) {
    out.echo = { ok: false, code: e.code || null, message: String(e.message) };
  }
  try {
    out.unregistered = await host.invoke('definitely.not.registered', {});
  } catch (e) {
    out.unregistered = { ok: false, code: e.code || null, message: String(e.message) };
  }
  return out;
}

// ── facts ────────────────────────────────────────────────────────────────────

async function buildFacts() {
  const jit = probeJit();
  const fsProbe = probeFs();
  const [worker, hostProbe] = await Promise.all([probeWorker(), probeHost()]);

  return {
    at: Date.now(),
    uptimeMs: Date.now() - START_AT,
    node: process.version,
    platform: process.platform,
    arch: process.arch,
    execPath: process.execPath,
    execArgv: process.execArgv,
    pid: process.pid,
    ppid: process.ppid,
    versions: {
      node: process.version,
      v8: process.versions.v8,
      modules: process.versions.modules,
      uv: process.versions.uv,
      openssl: process.versions.openssl,
      icu: process.versions.icu || null,
    },
    env: {
      HOME: process.env.HOME || null,
      TMPDIR: process.env.TMPDIR || null,
      UV_USE_IO_URING: process.env.UV_USE_IO_URING || null,
      NODE_BRIDGE_ADDON_DIR: process.env.NODE_BRIDGE_ADDON_DIR || null,
      NODE_OPTIONS: process.env.NODE_OPTIONS || null,
    },
    cwd: process.cwd(),
    homedir: (() => { try { return os.homedir(); } catch (e) { return 'throw:' + e.message; } })(),
    tmpdir: os.tmpdir(),
    cpus: (() => { try { return os.cpus().length; } catch (e) { return -1; } })(),
    availableParallelism: (() => { try { return os.availableParallelism ? os.availableParallelism() : null; } catch (e) { return -1; } })(),
    memMB: Math.round(process.memoryUsage().rss / 1048576),
    netIfaces: Object.keys(os.networkInterfaces()),
    jit,
    fs: fsProbe,
    worker,
    host: hostProbe,
  };
}

// ── host 侧注册（Node → Kotlin 的钩子）───────────────────────────────────────

// 注意：这些 handler 是**宿主侧**（Kotlin）注册的；Node 侧在这里只登记 shutdown 钩子。
try {
  // eslint-disable-next-line no-undef
  if (host && typeof host.onShutdown === 'function') host.onShutdown(() => undefined);
} catch (_) { /* host.js 目前不提供 onShutdown，忽略 */ }

// ── HTTP ─────────────────────────────────────────────────────────────────────

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const send = (code, obj) => {
    const body = typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2);
    res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
    res.end(body);
  };

  if (url.pathname === '/facts') {
    buildFacts().then((f) => send(200, f)).catch((e) => send(500, { ok: false, error: String(e && e.stack) }));
    return;
  }
  if (url.pathname === '/log') {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(`log file: ${process.env.NODE_BRIDGE_LOG || '(unset)'}\n`);
    return;
  }
  if (url.pathname === '/diag') {
    send(200, {
      at: Date.now(),
      uptimeMs: Date.now() - START_AT,
      pid: process.pid,
      host: host.ping(),
      env: { HOME: process.env.HOME, TMPDIR: process.env.TMPDIR },
    });
    return;
  }
  if (url.pathname === '/host') {
    const name = url.searchParams.get('name') || 'echo';
    let args = {};
    try { args = JSON.parse(url.searchParams.get('args') || '{}'); } catch (_) { args = {}; }
    host.invoke(name, args)
      .then((r) => send(200, { ok: true, name, result: r }))
      .catch((e) => send(200, { ok: false, name, code: e.code || null, error: String(e.message) }));
    return;
  }
  if (url.pathname === '/') {
    send(200, { ok: true, endpoints: ['/facts', '/log', '/diag', '/host?name=echo&args={}'] });
    return;
  }
  send(404, { ok: false, error: 'not-found', path: url.pathname });
});

server.on('error', (e) => {
  // 端口冲突是「打崩整个 App」的头号原因（规划 §5.10 ⑧）：记下来，别静默
  console.error(`[selfcheck] listen ${PORT} 失败：${e.code || ''} ${e.message}`);
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[selfcheck] listening http://127.0.0.1:${PORT}  node=${process.version} platform=${process.platform}`);
  const jit = probeJit();
  console.log(`[selfcheck] jit: wasm=${jit.wasm} hotLoopMs=${Number(jit.hotLoopMs).toFixed(1)} jitless=${jit.jitless}`);
});
