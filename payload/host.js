'use strict';

/*
 * host.js —— Node 侧的宿主能力入口（模块 003-host-bridge 的 Node 端）
 *
 * 传输层实现：**NAPI addon**（libnode_host_client.so）→ dlsym 到引导件的槽位表 →
 * Kotlin 侧 poll/complete。addon 不可用时**结构化报错**，绝不返回空值。
 *
 * 用法：
 *   const host = require('./host.js');
 *   const facts = await host.invoke('runtime.echo', { hello: 1 });
 */

const path = require('path');

let addon = null;
let addonError = null;
let pingResult = null;
let bridgeHandle = null; // bigint；由宿主经 NODE_BRIDGE_BRIDGE_HANDLE 或 setBridge() 注入

function setBridge(handle) {
  const a = loadAddon();
  if (a === null) throw new Error('addon 未加载，无法 setBridge：' + String(addonError && addonError.message));
  const rc = a.setBridge(handle);
  if (rc !== 0) throw new Error('setBridge 失败 rc=' + rc);
  bridgeHandle = handle;
  return true;
}

function candidatePaths() {
  const out = [];
  const dirs = new Set();
  const dir = process.env.NODE_BRIDGE_ADDON_DIR || '';
  if (dir) dirs.add(dir);
  /* 兜底：从 /proc/self/maps 找 libnode.so 实际加载目录（引擎与 addon 同在 APK native lib 目录） */
  try {
    const fs = require('fs');
    const maps = fs.readFileSync('/proc/self/maps', 'utf8');
    for (const line of maps.split('\n')) {
      const m = line.match(/\s(\/.*libnode\.so)\b/);
      if (m) {
        const d = m[1].slice(0, m[1].lastIndexOf('/'));
        if (d) dirs.add(d);
        break;
      }
    }
  } catch (_) { /* 忽略：候选链继续 */ }
  for (const d of dirs) out.push(path.join(d, 'libnode_host_client.so'));
  out.push('libnode_host_client.so');
  return out;
}

function loadAddon() {
  if (addon !== null || addonError !== null) return addon;
  const errs = [];
  for (const p of candidatePaths()) {
    try {
      const m = { exports: {} };
      process.dlopen(m, p);
      addon = m.exports;
      if (global.__anbBridgeHandle !== undefined && typeof addon.setBridge === 'function') {
        /* 命名空间隔离下 addon 自举 dlsym 可能未命中 ⇒ 用宿主注入的 handle 直连槽位表；
         * handle 无效必须**当场抛错**（失败必须可见，不允许静默降级） */
        const rc = addon.setBridge(global.__anbBridgeHandle);
        if (rc !== 0) {
          throw new Error('setBridge 失败 rc=' + rc + ' handle=' + global.__anbBridgeHandle.toString(16));
        }
      }
      return addon;
    } catch (e) {
      errs.push(`${p}: ${e && e.message ? e.message : String(e)}`);
    }
  }
  addonError = new Error('host addon 不可用：' + errs.join(' | '));
  return null;
}

function ping() {
  if (pingResult !== null) return pingResult;
  const a = loadAddon();
  if (a === null) {
    pingResult = { ok: false, error: 'addon-unavailable', detail: String(addonError && addonError.message) };
    return pingResult;
  }
  try {
    const pong = a.ping();
    pingResult = { ok: true, pong: String(pong), type: typeof pong, keys: Object.keys(a || {}).join(',') };
  } catch (e) {
    pingResult = { ok: false, error: 'ping-threw', detail: String(e && e.message) };
  }
  return pingResult;
}

/**
 * 调用一个宿主能力。返回**解析后的对象**；失败时 reject 一个带 code 的错误。
 * 纪律：未注册 / 超时 / handler 抛异常都必须可判定，不允许静默。
 */
function invoke(name, payload) {
  return new Promise((resolve, reject) => {
    if (typeof name !== 'string' || name.length === 0) {
      reject(Object.assign(new Error('invoke: name 必须是非空字符串'), { code: 'bad-arg' }));
      return;
    }
    const a = loadAddon();
    if (a === null) {
      reject(Object.assign(new Error(String(addonError && addonError.message)), { code: 'addon-unavailable' }));
      return;
    }
    let argsJson;
    try {
      argsJson = JSON.stringify(payload === undefined ? null : payload);
    } catch (e) {
      reject(Object.assign(new Error('invoke: payload 无法序列化'), { code: 'bad-arg' }));
      return;
    }
    let raw;
    try {
      raw = a.invoke(name, argsJson);
      if (typeof raw === 'string' && raw.indexOf('anb_host_call not found') >= 0 && bridgeHandle !== null) {
        // 命名空间隔离：addon 自举 dlsym 未命中 ⇒ 用宿主注入的 handle 重连后再试一次
        setBridge(bridgeHandle);
        raw = a.invoke(name, argsJson);
      }
    } catch (e) {
      // addon 侧抛出的错误带 code（ERR_ANB_TIMEOUT / ERR_ANB_HOST_CALL …）
      reject(Object.assign(new Error(String(e && e.message)), { code: (e && e.code) || 'native-error' }));
      return;
    }
    let parsed;
    try {
      parsed = raw.length === 0 ? {} : JSON.parse(raw);
    } catch (e) {
      reject(Object.assign(new Error('宿主返回的不是合法 JSON'), { code: 'bad-json', raw: String(raw).slice(0, 512) }));
      return;
    }
    resolve(parsed);
  });
}

module.exports = { invoke, ping, loadAddon, setBridge };
