#!/usr/bin/env node
/*
 * tools/build-bridge.mjs —— 交叉编译引导件与 NAPI addon（模块 002 的构建步骤）
 *
 * 为什么不用 CMake：本机没有 CMake/ninja，而 NDK 自带 clang 足够完成这两个极小的 .so；
 *   直调 clang 让「构建 = 一条可复现命令」，且不引入 AGP 的 externalNativeBuild 依赖。
 *   需要 CMake 的场合用 node-bridge/src/main/cpp/CMakeLists.txt（等价语义）。
 *
 * 用法：
 *   node tools/build-bridge.mjs --out <dir> [--abi arm64-v8a] [--api 24] [--force]
 *
 * 退出码：0 成功（或可跳过的缺件）· 1 硬失败
 */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
function argOf(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const OUT = path.resolve(argOf('--out', path.join(ROOT, 'node-bridge/build/generated/jniLibs/arm64-v8a')));
const ABI = argOf('--abi', 'arm64-v8a');
const API = argOf('--api', '24');

const ABI_TRIPLE = { 'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7a-linux-androideabi' };

function log(msg) {
  process.stdout.write(`[build-bridge] ${msg}\n`);
}

function findNdk() {
  const candidates = [];
  if (process.env.ANDROID_NDK_HOME) candidates.push(process.env.ANDROID_NDK_HOME);
  if (process.env.ANDROID_NDK_ROOT) candidates.push(process.env.ANDROID_NDK_ROOT);

  // 从 local.properties / 默认 SDK 目录里扫 ndk/<version>
  const sdkDirs = [];
  const localProps = path.join(ROOT, 'local.properties');
  if (fs.existsSync(localProps)) {
    const m = /sdk\.dir\s*=\s*(.+)/.exec(fs.readFileSync(localProps, 'utf8'));
    if (m) sdkDirs.push(m[1].trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\'));
  }
  if (process.env.LOCALAPPDATA) sdkDirs.push(path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'));
  if (process.env.HOME) sdkDirs.push(path.join(process.env.HOME, 'Android', 'Sdk'));

  for (const sdk of sdkDirs) {
    const ndkRoot = path.join(sdk, 'ndk');
    if (!fs.existsSync(ndkRoot)) continue;
    for (const v of fs.readdirSync(ndkRoot)) candidates.push(path.join(ndkRoot, v));
  }
  for (const c of candidates) {
    if (fs.existsSync(path.join(c, 'toolchains', 'llvm', 'prebuilt'))) return c;
  }
  return null;
}

function hostTag(ndk) {
  const prebuilt = path.join(ndk, 'toolchains', 'llvm', 'prebuilt');
  const tags = fs.readdirSync(prebuilt);
  const want = process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'darwin' : 'linux';
  const hit = tags.find((t) => t.startsWith(want));
  if (!hit) throw new Error(`找不到 ${want} 的 prebuilt 工具链：${tags.join(', ')}`);
  return hit;
}

function run(cmd, cmdArgs) {
  log(`${path.basename(cmd)} ${cmdArgs.join(' ')}`);
  // Windows 上 NDK 的交叉 clang 是 .cmd 包装脚本：Node 18+ 禁止直接 spawn .cmd ⇒ 必须经 shell
  // （我们的路径不含空格，故 shell 拼接在这里是安全的）
  const viaShell = process.platform === 'win32' && cmd.toLowerCase().endsWith('.cmd');
  const r = spawnSync(viaShell ? `"${cmd}"` : cmd, cmdArgs, { stdio: 'inherit', shell: viaShell });
  if (r.status !== 0) {
    throw new Error(`退出码 ${r.status}${r.error ? ' (' + r.error.message + ')' : ''}: ${cmd}`);
  }
}

function main() {
  const ndk = findNdk();
  if (!ndk) {
    log('未找到 Android NDK —— 跳过原生构建（APK 里不会有 libnode_bridge.so，探针会如实报「.so 未加载」）');
    log('修法：sdkmanager "ndk;27.2.12479018" 或 export ANDROID_NDK_HOME=<ndk>');
    process.exit(0);
  }
  log(`NDK = ${ndk}`);

  const triple = ABI_TRIPLE[ABI];
  if (!triple) throw new Error(`不支持的 ABI: ${ABI}`);

  const bin = path.join(ndk, 'toolchains', 'llvm', 'prebuilt', hostTag(ndk), 'bin');
  const clang = path.join(bin, `${triple}${API}-clang${process.platform === 'win32' ? '.cmd' : ''}`);
  if (!fs.existsSync(clang)) throw new Error(`找不到 clang：${clang}`);

  fs.mkdirSync(OUT, { recursive: true });

  // ① 引导件（只链 log/dl；**不**链 libnode，靠 dlopen）
  const bridge = path.join(OUT, 'libnode_bridge.so');
  run(clang, [
    '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-fvisibility=hidden',
    '-Wl,-soname,libnode_bridge.so',
    '-o', bridge,
    path.join(ROOT, 'node-bridge/src/main/cpp/node_bridge.c'),
    '-llog', '-ldl',
  ]);
  log(`✔ ${bridge} (${fs.statSync(bridge).size} B)`);

  // ② NAPI addon（头文件随仓库自带；napi_* 留作未定义符号，运行期由 dlopen 的 libnode.so 解析）
  const includeDir = path.join(ROOT, 'node-bridge/src/main/cpp/include/node');
  const haveHeaders = fs.existsSync(path.join(includeDir, 'node_api.h'));

  if (!haveHeaders) {
    log(`跳过 node_host_client：缺 Node NAPI 头(${includeDir})`);
    return;
  }

  const client = path.join(OUT, 'libnode_host_client.so');
  run(clang, [
    '-shared', '-fPIC', '-O2', '-Wall', '-fvisibility=hidden',
    `-I${includeDir}`,
    '-Wl,-soname,libnode_host_client.so',
    '-Wl,-z,undefs',
    '-o', client,
    path.join(ROOT, 'node-bridge/src/main/cpp/node_host_client.c'),
    '-llog',
  ]);
  log(`✔ ${client} (${fs.statSync(client).size} B)`);
  log('提示：napi_* 符号留作未定义（-z undefs），运行期由已 dlopen 的 libnode.so（RTLD_GLOBAL）解析');
}

try {
  main();
} catch (e) {
  process.stderr.write(`[build-bridge] 失败：${e.message}\n`);
  process.exit(1);
}
