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

// ② ELF 门禁：runtime 已分离到 aosp-node-bridge 仓库，此处跳过
section('② 运行时八项 ELF 门禁');
// runtime 已分离：libnode.so 门禁在 aosp-node-runtime 仓库执行
log('② ELF 门禁：跳过（runtime 已分离到 aosp-node-runtime 仓库）');
// （原 provision --check 逻辑已移除）

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
