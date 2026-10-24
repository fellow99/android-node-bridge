#!/usr/bin/env node
/**
 * release-maven.mjs —— Maven Central 发布流水线（bridge 部分）。
 *
 * 用法：
 *   node tools/release-maven.mjs --check-version   # 版本一致性 + AAR 结构断言
 *
 * 注意：node-runtime 已分离到 aosp-node-runtime 仓库，其发布验证在该仓库执行。
 */
import { readFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve, dirname, join } from 'node:path';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');

let failures = 0;
function assert(ok, name, detail = '') {
  console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${name}${detail ? `  (${detail})` : ''}`);
  if (!ok) failures++;
  return ok;
}
function section(t) { console.log(`\n${t}`); }

function gradle(...args) {
  return execFileSync('gradle', [...args, '--no-daemon', '-q'], { cwd: ROOT, stdio: 'pipe', shell: process.platform === 'win32' }).toString();
}

section('① 版本一致性（gradle.properties）');
const gradleProps = readFileSync(join(ROOT, 'gradle.properties'), 'utf8');
const VERSION_NAME = (gradleProps.match(/^VERSION_NAME=(.+)$/m) || [])[1]?.trim();
assert(VERSION_NAME && /^\d+\.\d+\.\d+$/.test(VERSION_NAME), 'VERSION_NAME 是合法 semver', VERSION_NAME);

section('② 本地 publish 验证（publishToMavenLocal）');
gradle(':node-bridge:publishToMavenLocal');
const m2 = (p) => resolve(process.env.USERPROFILE ?? '', '.m2/repository', p);
const bridgeAar = m2(`io/github/fellow99/node-bridge/${VERSION_NAME}/node-bridge-${VERSION_NAME}.aar`);
assert(existsSync(bridgeAar), 'node-bridge AAR 已发布到 m2', bridgeAar);
if (existsSync(bridgeAar)) {
  assert(existsSync(bridgeAar + '.asc'), '签名存在 node-bridge.aar.asc');
}

section('③ AAR 结构断言（node-bridge）');
{
  const list = execFileSync('unzip', ['-l', bridgeAar], { stdio: 'pipe' }).toString();
  const so = list.match(/jni\/arm64-v8a\/[\w.]+\.so/g) ?? [];
  assert(so.length === 2, 'node-bridge 恰含 2 个 .so（bridge+host_client）', so.join(', '));
  for (const js of ['bootstrap.js', 'host.js', 'selfcheck.js']) {
    assert(list.includes(`assets/node-bridge/${js}`), `node-bridge 含 assets/node-bridge/${js}`);
  }
}

section(failures === 0 ? '✅ 全绿 —— 可进入真实发布' : `❌ ${failures} 项未过`);
process.exit(failures === 0 ? 0 : 2);
