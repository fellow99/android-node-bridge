#!/usr/bin/env node
/**
 * release-maven.mjs —— Maven Central 发布流水线（对标 ohos-node-bridge 的 tools/release.mjs）。
 *
 * 用法：
 *   node tools/release-maven.mjs --check-version   # 版本一致性 + AAR 结构断言（可进 CI，无需凭据）
 *   node tools/release-maven.mjs --all             # = --check-version + 本地 publish 验证
 *
 * 纪律：任何一步失败 ⇒ exit 2，不继续（避免「打了包但没验」）。
 * 真实发布（gradle publishToMavenCentral）需 RELEASE=true + 用户级 Portal Token/GPG 就位，
 * 由人工在交互式终端执行（见 docs 发布 runbook）。
 */
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { resolve, dirname, join } from 'node:path';

const ROOT = resolve(dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);

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

section('① 版本一致性（runtime.lock.json ⇔ gradle.properties ⇔ AAR 产物）');
const lock = JSON.parse(readFileSync(join(ROOT, 'runtime.lock.json'), 'utf8'));
const active = lock.active ?? lock;
const nodeVersion = active.node ?? active.version;
assert(typeof nodeVersion === 'string' && /^\d+\.\d+\.\d+$/.test(nodeVersion), 'runtime.lock.json 有 Node 版本', nodeVersion);

const gradleProps = readFileSync(join(ROOT, 'gradle.properties'), 'utf8');
const prop = (k) => {
  const m = gradleProps.match(new RegExp(`^${k}=(.+)$`, 'm'));
  return m ? m[1].trim() : null;
};
const NODE_VERSION = prop('NODE_VERSION');
const VERSION_NAME = prop('VERSION_NAME');
assert(NODE_VERSION === nodeVersion, 'NODE_VERSION == runtime.lock.json', `${NODE_VERSION} vs ${nodeVersion}`);
assert(/^\d+\.\d+\.\d+$/.test(VERSION_NAME ?? ''), 'VERSION_NAME 是合法 semver', VERSION_NAME);

const sha256OfBuffer = (buf) => createHash('sha256').update(buf).digest('hex');

section('② 本地 publish 验证（publishToMavenLocal + 签名 + AAR 结构）');
gradle(':node-bridge:publishToMavenLocal', ':node-runtime:publishToMavenLocal');
const m2 = (p) => resolve(process.env.USERPROFILE ?? '', '.m2/repository', p);
const bridgeAar = m2(`io/github/fellow99/node-bridge/${VERSION_NAME}/node-bridge-${VERSION_NAME}.aar`);
const runtimeAar2 = m2(`io/github/fellow99/node-runtime/${NODE_VERSION}/node-runtime-${NODE_VERSION}.aar`);
assert(existsSync(bridgeAar), 'node-bridge AAR 已发布到 m2', bridgeAar);
assert(existsSync(runtimeAar2), 'node-runtime AAR 已发布到 m2（版本=Node 版本）', runtimeAar2);

// 签名文件齐全（.asc）
for (const f of [bridgeAar, runtimeAar2]) {
  if (existsSync(f)) assert(existsSync(f + '.asc'), `签名存在 ${f.split('/').pop()}.asc`);
}

section('③ AAR 结构断言');
// node-bridge：2 个 .so + 3 个 payload js
{
  const list = execFileSync('unzip', ['-l', bridgeAar], { stdio: 'pipe' }).toString();
  const so = list.match(/jni\/arm64-v8a\/[\w.]+\.so/g) ?? [];
  assert(so.length === 2, 'node-bridge 恰含 2 个 .so（bridge+host_client）', so.join(', '));
  for (const js of ['bootstrap.js', 'host.js', 'selfcheck.js']) {
    assert(list.includes(`assets/node-bridge/${js}`), `node-bridge 含 assets/node-bridge/${js}`);
  }
}
// node-runtime：libnode.so + libc++_shared.so（libc++ 只在本包，防消费方撞车）；libnode.so SHA-256 == 锁值
{
  const list = execFileSync('unzip', ['-l', runtimeAar2], { stdio: 'pipe' }).toString();
  assert(list.includes('jni/arm64-v8a/libnode.so'), 'node-runtime 含 libnode.so');
  assert(list.includes('jni/arm64-v8a/libc++_shared.so'), 'node-runtime 含 libc++_shared.so（唯一供给方）');
  const soBuf = execFileSync('unzip', ['-p', runtimeAar2, 'jni/arm64-v8a/libnode.so'], { maxBuffer: 256 * 1024 * 1024 });
  const release = (lock.releases ?? []).find((r) => r.node === nodeVersion) ?? {};
  assert(sha256OfBuffer(soBuf) === release.sha256, 'AAR 内 libnode.so SHA-256 == runtime.lock.json', sha256OfBuffer(soBuf).slice(0, 12));
}

section(failures === 0 ? '✅ 全绿 —— 可进入真实发布（RELEASE=true + 交互式终端）' : `❌ ${failures} 项未过`);
process.exit(failures === 0 ? 0 : 2);
