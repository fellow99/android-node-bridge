#!/usr/bin/env node
/*
 * tools/smoke-host.mjs —— host 侧门禁（**不接真机**）
 *
 * 三条独立检查，任何一条失败即退出 1：
 *   ① 载荷 JS 语法（node --check 每个 payload/*.js）
 *   ② 运行时八项 ELF 门禁（复用 provision.mjs 的实现：--check）
 *   ③ 桥的 JSON 契约（用假宿主跑一遍 NodeHostBridge 的语义：未注册/异常/正常）
 *
 * 用法：node tools/smoke-host.mjs
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let failed = 0;

function section(t) {
  process.stdout.write(`\n── ${t} ──\n`);
}
function ok(t) {
  process.stdout.write(`✔ ${t}\n`);
}
function bad(t) {
  process.stdout.write(`✘ ${t}\n`);
  failed++;
}

// ① 载荷语法
section('① payload/*.js 语法');
const payloadDir = path.join(ROOT, 'payload');
for (const f of fs.readdirSync(payloadDir).filter((x) => x.endsWith('.js'))) {
  const r = spawnSync(process.execPath, ['--check', path.join(payloadDir, f)], { encoding: 'utf8' });
  if (r.status === 0) ok(`${f}`);
  else bad(`${f}: ${(r.stderr || '').split('\n')[0]}`);
}

// ② ELF 门禁（复用 provision --check；16 KB 未对齐时允许 --allow-page-align-4k 由调用方决定）
section('② 运行时八项 ELF 门禁');
const lock = JSON.parse(fs.readFileSync(path.join(ROOT, 'runtime.lock.json'), 'utf8'));
const target = path.join(ROOT, 'node-runtime/src/main/jniLibs', lock.active.abi, 'libnode.so');
if (!fs.existsSync(target)) {
  bad(`未投放运行时：${target}（先 node tools/provision.mjs --out … --from …）`);
} else {
  const allow4k = process.argv.includes('--allow-page-align-4k');
  const provArgs = [path.join(ROOT, 'tools/provision.mjs'), '--check'];
  if (allow4k) provArgs.push('--allow-page-align-4k');
  const r = spawnSync(process.execPath, provArgs, { encoding: 'utf8' });
  process.stdout.write(r.stdout || '');
  if (r.status === 0) ok(allow4k ? '门禁全过（16 KB 页对齐已被显式放宽：仅 bring-up）' : '门禁全过');
  else if (r.status === 2) bad('门禁不合格（加 --allow-page-align-4k 可在 bring-up 阶段放行 16 KB 页对齐）');
  else bad(`provision --check 异常退出 ${r.status}`);
}

// ③ 桥的 JSON 契约（纯 JS 复刻 Kotlin NodeHostBridge 的三条语义）
section('③ 宿主桥 JSON 契约');
const registry = new Map();
registry.set('echo', (args) => `{"ok":true,"echo":${args || 'null'}}`);
registry.set('boom', () => {
  throw new Error('intentional');
});
function dispatch(name, argsJson) {
  const h = registry.get(name);
  if (!h) return `{"ok":false,"error":"unregistered","name":"${name}"}`;
  try {
    return h(argsJson);
  } catch (e) {
    return `{"ok":false,"error":"handler-threw","name":"${name}","message":"${e.message}"}`;
  }
}
const cases = [
  ['echo', '{"n":1}', (o) => o.ok === true && o.echo.n === 1],
  ['boom', '{}', (o) => o.ok === false && o.error === 'handler-threw'],
  ['nope', '{}', (o) => o.ok === false && o.error === 'unregistered'],
];
for (const [name, args, check] of cases) {
  let parsed = null;
  try {
    parsed = JSON.parse(dispatch(name, args));
  } catch (e) {
    bad(`${name}: 返回的不是 JSON（${e.message}）`);
    continue;
  }
  if (check(parsed)) ok(`${name} → ${JSON.stringify(parsed).slice(0, 80)}`);
  else bad(`${name} → 契约不符：${JSON.stringify(parsed)}`);
}

section(failed === 0 ? '结果：全过' : `结果：${failed} 项失败`);
process.exit(failed === 0 ? 0 : 1);
