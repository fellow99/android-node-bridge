'use strict';

/*
 * bootstrap.js —— 载荷引导器（模块 004-payload-bootstrap 的 Node 端）
 *
 * 它只回答四个问题，且**不**替消费方决定业务：
 *   ① 运行根在哪：`NODE_BRIDGE_PAYLOAD_ROOT`（Kotlin 侧物化后的可写目录）→ cwd；
 *   ② 入口是谁：`NODE_BRIDGE_ENTRY`（绝对路径）→ 该目录 `package.json:main` → `index.js`；
 *   ③ CJS 还是 ESM：`require` 失败且错误码在 ESM 白名单内 ⇒ 退 `import(pathToFileURL())`；
 *   ④ 崩了怎么办：`uncaughtException` / `unhandledRejection` 写**独立通道** `<HOME>/payload/app.log`，
 *      **绝不** `process.exit`（进程内 Node 没有优雅退出路径，见规划 §6.2 ⑱）。
 *
 * 日志前缀 `<stream> <epochMs> ` 由原生层 reader 线程加（契约 §5.7），本文件只写纯文本。
 */

const fs = require('fs');
const path = require('path');

const ESM_REQUIRE_CODES = ['ERR_REQUIRE_ESM', 'ERR_REQUIRE_ASYNC_MODULE'];

let guardInstalled = false;

function log(msg) {
  try {
    process.stdout.write('[bootstrap] ' + msg + '\n');
  } catch (_) {
    try { process.stderr.write('[bootstrap] WARN stdout 不可写\n'); } catch (__) { /* 放弃 */ }
  }
}

function appLogPath() {
  const home = process.env.NODE_BRIDGE_HOME || process.env.HOME || '.';
  return path.join(home, 'payload', 'app.log');
}

function appendAppLog(line) {
  const p = appLogPath();
  try {
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.appendFileSync(p, `${Date.now()} ${line}\n`);
  } catch (_) {
    /* 独立通道也写不了：只留 stderr，绝不再抛 */
  }
}

function installGuard() {
  if (guardInstalled) return;
  guardInstalled = true;
  const record = (kind) => (err) => {
    const text = `${kind} ${(err && err.stack) || String(err)}`;
    appendAppLog(text);
    try { process.stderr.write('[bootstrap] ' + text + '\n'); } catch (_) { /* ignore */ }
    // 绝不 process.exit：那会让 node::Start 返回、运行时永久死亡
  };
  process.on('uncaughtException', record('uncaughtException'));
  process.on('unhandledRejection', record('unhandledRejection'));
}

function resolveEntry(root) {
  const fromEnv = process.env.NODE_BRIDGE_ENTRY;
  if (fromEnv && fromEnv.length > 0) {
    const abs = path.isAbsolute(fromEnv) ? fromEnv : path.resolve(root, fromEnv);
    if (fs.existsSync(abs)) return abs;
    log(`WARN NODE_BRIDGE_ENTRY 不存在：${abs}`);
  }
  const pkgPath = path.join(root, 'package.json');
  if (fs.existsSync(pkgPath)) {
    try {
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
      if (pkg && typeof pkg.main === 'string') {
        const abs = path.resolve(root, pkg.main);
        if (fs.existsSync(abs)) return abs;
      }
    } catch (e) {
      log(`WARN package.json 解析失败：${e.message}`);
    }
  }
  for (const name of ['index.js', 'index.cjs', 'main.js']) {
    const abs = path.join(root, name);
    if (fs.existsSync(abs)) return abs;
  }
  return null;
}

async function loadEntry(entry) {
  try {
    require(entry);
    return 'cjs';
  } catch (e) {
    if (e && ESM_REQUIRE_CODES.includes(e.code)) {
      const { pathToFileURL } = require('url');
      await import(pathToFileURL(entry).href);
      return 'esm';
    }
    throw e;
  }
}

async function main() {
  installGuard();

  const root = process.env.NODE_BRIDGE_PAYLOAD_ROOT || process.cwd();
  const cwd = process.env.NODE_BRIDGE_CWD || root;
  try {
    process.chdir(cwd);
  } catch (e) {
    log(`WARN chdir(${cwd}) 失败：${e.message}`);
  }

  log(`node=${process.version} platform=${process.platform} arch=${process.arch}`);
  log(`root=${root}`);
  log(`cwd=${process.cwd()}`);
  log(`execArgv=${JSON.stringify(process.execArgv)}`);

  const entry = resolveEntry(root);
  if (entry === null) {
    log('ERROR 找不到入口（NODE_BRIDGE_ENTRY / package.json:main / index.js 均不可用）');
    appendAppLog('ENTRY_MISSING root=' + root);
    // 保持进程活着，方便宿主侧读到日志（进程内 Node 退出即终态）
    return;
  }
  log(`entry=${entry} (${fs.statSync(entry).size} B)`);

  const kind = await loadEntry(entry);
  log(`entry loaded as ${kind}`);
}

main().catch((e) => {
  const text = `bootstrap failed: ${(e && e.stack) || String(e)}`;
  appendAppLog(text);
  log('ERROR ' + text);
});
