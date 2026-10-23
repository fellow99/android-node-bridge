#!/usr/bin/env node
/*
 * tools/provision.mjs —— 运行时投放与**八项 ELF 门禁**（模块 005-runtime-build-verify）
 *
 * 唯一事实源是仓库根的 `runtime.lock.json`（版本 + 大小 + SHA-256 + SONAME + NEEDED + 出处）。
 * 二进制**不入库**：本工具把产物从本地文件/归档里幂等投放进模块的 jniLibs。
 *
 * 用法：
 *   node tools/provision.mjs --check                        # 只校验已投放的产物
 *   node tools/provision.mjs --out node-runtime/src/main/jniLibs --from <libnode.so>
 *   node tools/provision.mjs --out ... --include-headers --headers-from <node/include 目录>
 *   node tools/provision.mjs --inspect <libnode.so>          # 只打印八项门禁结果
 *
 * 退出码：0 全过 · 2 门禁不合格 · 1 用法/IO 错误
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCK_PATH = path.join(ROOT, 'runtime.lock.json');

const args = process.argv.slice(2);
const has = (f) => args.includes(f);
function argOf(name, fallback = null) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}

function say(msg) {
  process.stdout.write(`[provision] ${msg}\n`);
}

// ── 极简 ELF64（小端）解析：只回答门禁需要的问题 ──────────────────────────────

function parseElf(buf) {
  if (buf.length < 64 || buf.readUInt32BE(0) !== 0x7f454c46) throw new Error('不是 ELF 文件');
  const little = buf[5] === 1;
  if (!little) throw new Error('只支持小端 ELF（AArch64 一定是小端）');
  const u16 = (o) => buf.readUInt16LE(o);
  const u32 = (o) => buf.readUInt32LE(o);
  const u64 = (o) => Number(buf.readBigUInt64LE(o));

  const eType = u16(16);
  const eMachine = u16(18);
  const ePhoff = u64(32);
  const ePhentsize = u16(54);
  const ePhnum = u16(56);
  const eShoff = u64(40);
  const eShentsize = u16(58);
  const eShnum = u16(60);
  const eShstrndx = u16(62);

  let ptInterp = 0;
  const loadAligns = [];
  for (let i = 0; i < ePhnum; i++) {
    const o = ePhoff + i * ePhentsize;
    const pType = u32(o);
    const pAlign = u64(o + 48);
    if (pType === 3) ptInterp++;
    if (pType === 1) loadAligns.push(pAlign);
  }

  const sections = [];
  for (let i = 0; i < eShnum; i++) {
    const o = eShoff + i * eShentsize;
    sections.push({
      nameOff: u32(o),
      type: u32(o + 4),
      offset: u64(o + 24),
      size: u64(o + 32),
      link: u32(o + 40),
      entsize: u64(o + 56),
    });
  }
  const shstr = sections[eShstrndx];
  const strAt = (base, off) => {
    const s = base + off;
    let e = s;
    while (e < buf.length && buf[e] !== 0) e++;
    return buf.toString('utf8', s, e);
  };
  const byName = new Map();
  for (const s of sections) byName.set(strAt(shstr.offset, s.nameOff), s);

  const dynamic = byName.get('.dynamic');
  const dynstr = new Map();
  if (dynamic) {
    for (let i = 0; i * 16 < dynamic.size; i++) {
      const o = dynamic.offset + i * 16;
      const tag = Number(buf.readBigInt64LE(o));
      const val = u64(o + 8);
      if (tag === 0) break;
      if (tag === 1 || tag === 14) {
        const nm = strAt(byName.get('.dynstr').offset, val);
        dynstr.set(tag + ':' + nm, true);
      }
    }
  }
  const soname = [...dynstr.keys()].filter((k) => k.startsWith('14:')).map((k) => k.slice(3));
  const needed = [...dynstr.keys()].filter((k) => k.startsWith('1:')).map((k) => k.slice(2));

  const dynsym = byName.get('.dynsym');
  const symFound = [];
  if (dynsym) {
    const strtab = sections[dynsym.link];
    for (let i = 0; i * 24 < dynsym.size; i++) {
      const o = dynsym.offset + i * 24;
      const nameOff = u32(o);
      if (nameOff === 0) continue;
      const stInfo = buf[o + 4];
      const stShndx = u16(o + 6);
      const name = strAt(strtab.offset, nameOff);
      if (name === '_ZN4node5StartEiPPc') {
        symFound.push({ name, type: stInfo & 0xf, bind: stInfo >> 4, defined: stShndx !== 0 });
      }
    }
  }

  const version = (() => {
    const m = /v(\d+)\.(\d+)\.(\d+)/.exec(buf.toString('latin1'));
    return m ? m[0] : null;
  })();

  return { eType, eMachine, ptInterp, loadAligns, soname, needed, startSyms: symFound, version };
}

function gates(buf, expect) {
  let elf;
  try {
    elf = parseElf(buf);
  } catch (e) {
    return { ok: false, results: [{ id: 'parse', ok: false, detail: e.message }] };
  }
  const sha256 = crypto.createHash('sha256').update(buf).digest('hex');
  const minAlign = elf.loadAligns.length ? Math.min(...elf.loadAligns) : 0;
  const r = [];
  const add = (id, ok, detail) => r.push({ id, ok, detail });

  add('① e_type = ET_DYN', elf.eType === 3, `e_type=${elf.eType}`);
  add('② PT_INTERP = 0', elf.ptInterp === 0, `PT_INTERP=${elf.ptInterp}`);
  add('③ e_machine = AArch64', elf.eMachine === 183, `e_machine=${elf.eMachine}`);
  add('④ SONAME = libnode.so', elf.soname.includes('libnode.so'), `SONAME=${JSON.stringify(elf.soname)}`);
  const startOk = elf.startSyms.some((s) => s.defined && s.type === 2 && s.bind === 1);
  add('⑤ node::Start 已定义并导出', startOk, JSON.stringify(elf.startSyms));
  if (expect?.sha256) add('⑥ SHA-256 与锁一致', sha256 === expect.sha256, `${sha256.slice(0, 16)}… vs ${expect.sha256.slice(0, 16)}…`);
  else add('⑥ SHA-256（无锁值，仅记录）', true, sha256);
  if (expect?.size) add('⑦ 大小与锁一致', buf.length === expect.size, `${buf.length} vs ${expect.size}`);
  else add('⑦ 大小（无锁值，仅记录）', true, `${buf.length}`);
  add('⑧ 16 KB 页对齐', minAlign >= 0x4000, `min LOAD align=0x${minAlign.toString(16)}（4 KB 产物在 16 KB 设备上会装不上）`);

  const allow4k = process.argv.includes('--allow-page-align-4k');
  const gate8 = r.find((x) => x.id.startsWith('⑧'));
  const hardOk = r.every((x) => (allow4k && x === gate8 ? true : x.ok));
  if (allow4k && gate8 && !gate8.ok) {
    say('⚠ --allow-page-align-4k 放行「16 KB 页对齐」：**仅 bring-up**（4 KB 页设备可用；上架/16 KB 设备不合格）');
  }
  return { ok: hardOk, results: r, elf, sha256 };
}

function printGates(g) {
  for (const x of g.results) say(`${x.ok ? '✔' : '✘'} ${x.id} — ${x.detail}`);
}

// ── 主流程 ──────────────────────────────────────────────────────────────────

function readLock() {
  if (!fs.existsSync(LOCK_PATH)) throw new Error(`缺 ${LOCK_PATH}`);
  return JSON.parse(fs.readFileSync(LOCK_PATH, 'utf8'));
}

function findLibcxx() {
  const ndk = process.env.ANDROID_NDK_HOME || process.env.ANDROID_NDK_ROOT;
  const roots = [];
  if (ndk) roots.push(ndk);
  const sdk = (() => {
    const lp = path.join(ROOT, 'local.properties');
    if (fs.existsSync(lp)) {
      const m = /sdk\.dir\s*=\s*(.+)/.exec(fs.readFileSync(lp, 'utf8'));
      if (m) return m[1].trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\');
    }
    return process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk') : null;
  })();
  if (sdk && fs.existsSync(path.join(sdk, 'ndk'))) {
    for (const v of fs.readdirSync(path.join(sdk, 'ndk'))) roots.push(path.join(sdk, 'ndk', v));
  }
  for (const r of roots) {
    const pre = path.join(r, 'toolchains', 'llvm', 'prebuilt');
    if (!fs.existsSync(pre)) continue;
    for (const tag of fs.readdirSync(pre)) {
      const p = path.join(pre, tag, 'sysroot', 'usr', 'lib', 'aarch64-linux-android', 'libc++_shared.so');
      if (fs.existsSync(p)) return p;
    }
  }
  return null;
}

function main() {
  const lock = readLock();
  const active = lock.active;
  const rel = lock.releases.find((x) => x.node === active.node && x.abi === active.abi);
  if (!rel) throw new Error(`runtime.lock.json 里没有 active 记录：${JSON.stringify(active)}`);

  const inspectPath = argOf('--inspect');
  if (inspectPath) {
    const buf = fs.readFileSync(inspectPath);
    const g = gates(buf, rel);
    printGates(g);
    say(g.ok ? '门禁：全过' : '门禁：不合格');
    process.exit(g.ok ? 0 : 2);
  }

  const outDir = argOf('--out');
  const from = argOf('--from');

  if (!outDir) {
    // --check：校验已投放产物
    const target = path.join(ROOT, 'node-runtime/src/main/jniLibs', active.abi, 'libnode.so');
    if (!fs.existsSync(target)) {
      say(`未投放：${target}（先跑 --out …）`);
      process.exit(2);
    }
    const buf = fs.readFileSync(target);
    const g = gates(buf, rel);
    printGates(g);
    const cxx = path.join(path.dirname(target), 'libc++_shared.so');
    say(`${fs.existsSync(cxx) ? '✔' : '✘'} libc++_shared.so ${fs.existsSync(cxx) ? '已投放' : '缺失（libnode.so 的 DT_NEEDED 需要它）'}`);
    say(g.ok ? '门禁：全过' : '门禁：不合格');
    process.exit(g.ok ? 0 : 2);
  }

  if (!from) throw new Error('--out 需要配 --from <libnode.so>');
  const buf = fs.readFileSync(from);
  const g = gates(buf, rel);
  printGates(g);
  if (!g.ok) {
    say('门禁不合格 ⇒ 拒绝投放');
    process.exit(2);
  }
  const destDir = path.resolve(ROOT, outDir, active.abi);
  fs.mkdirSync(destDir, { recursive: true });
  const dest = path.join(destDir, 'libnode.so');
  fs.copyFileSync(from, dest);

  const cxx = findLibcxx();
  if (cxx) {
    fs.copyFileSync(cxx, path.join(destDir, 'libc++_shared.so'));
    say(`✔ libc++_shared.so ← ${cxx}`);
  } else {
    say('! 未找到 libc++_shared.so（NDK 未安装？）—— APK 里会缺这个依赖，dlopen 必失败');
  }

  if (has('--include-headers')) {
    const headersFrom = argOf('--headers-from') || path.join(ROOT, 'node-runtime/include/node');
    const headersTo = path.join(ROOT, 'node-runtime/include/node');
    if (path.resolve(headersFrom) !== path.resolve(headersTo) && fs.existsSync(headersFrom)) {
      fs.rmSync(headersTo, { recursive: true, force: true });
      fs.cpSync(headersFrom, headersTo, { recursive: true });
      say(`✔ 头文件 ← ${headersFrom}`);
    } else if (fs.existsSync(headersTo)) {
      say(`✔ 头文件已在位：${headersTo}`);
    } else {
      say('! 无头文件来源（--headers-from 指向 node/include 目录）');
    }
  }

  // 版本常量（供 node-runtime AAR 里的资源使用）
  const resDir = path.join(ROOT, 'node-runtime/src/main/resources/META-INF');
  fs.mkdirSync(resDir, { recursive: true });
  fs.writeFileSync(
    path.join(resDir, 'node-runtime.properties'),
    [
      `node.version=${rel.node}`,
      `abi=${rel.abi}`,
      `size=${buf.length}`,
      `sha256=${g.sha256}`,
      `source=${rel.source}`,
      `provisionedAt=${new Date().toISOString()}`,
      '',
    ].join('\n'),
  );
  say(`投放完成：${dest}`);
}

try {
  main();
} catch (e) {
  process.stderr.write(`[provision] 失败：${e.message}\n`);
  process.exit(1);
}
