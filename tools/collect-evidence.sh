#!/usr/bin/env bash
# tools/collect-evidence.sh —— 真机取证（一条命令跑完 Phase 0 的「通道 ①~④」）
#
# 用法：
#   bash tools/collect-evidence.sh [flavor] [--no-install]
#     flavor: modern（默认，targetSdk 36）| legacy（targetSdk 28，P0-1 对照）
#
# 产出：docs/evidence/<时间戳>/ 下的 logcat.txt / facts.json / node_bridge.log / summary.txt
#
# 纪律（规划 §5.7）：不用「logcat 里有没有那行」当判据；判据是 /facts 的 JSON。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLAVOR="${1:-modern}"
[ "$FLAVOR" = "--no-install" ] && FLAVOR=modern
NO_INSTALL=0
for a in "$@"; do [ "$a" = "--no-install" ] && NO_INSTALL=1; done

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB="adb"

case "$FLAVOR" in
  modern) SUFFIX=".modern"; EXTRA="";;
  legacy) SUFFIX=".legacy"; EXTRA="";;
  *) echo "未知 flavor: $FLAVOR（用 modern / legacy）"; exit 1;;
esac

PKG="org.fellow99.nodebridge.sample${SUFFIX}"
ACT="$PKG/org.fellow99.nodebridge.sample.MainActivity"
APK="$ROOT/samples/minimal-app/build/outputs/apk/${FLAVOR}/debug/minimal-app-${FLAVOR}-debug.apk"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/docs/evidence/$STAMP-$FLAVOR"
mkdir -p "$OUT"

say() { echo "[evidence] $*" | tee -a "$OUT/summary.txt"; }

say "device: $("$ADB" shell getprop ro.product.model | tr -d '\r') / API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r') / $("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
say "apk   : $APK"
say "pkg   : $PKG"

if [ "$NO_INSTALL" -eq 0 ]; then
  say "install…"
  "$ADB" install -r -t "$APK" 2>&1 | tee -a "$OUT/summary.txt"
fi

say "launch (autorun=1)…"
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" shell am force-stop org.fellow99.nodebridge.sample.legacy >/dev/null 2>&1
"$ADB" shell am force-stop org.fellow99.nodebridge.sample.modern >/dev/null 2>&1
"$ADB" shell "run-as $PKG rm -f files/node-bridge/real_port.txt" >/dev/null 2>&1
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -n "$ACT" -e autorun 1 2>&1 | tee -a "$OUT/summary.txt"

say "wait 12s（Node 起来 + 自动读 facts）…"
sleep 12

say "logcat…"
"$ADB" logcat -d -s NODE_BRIDGE:V AndroidRuntime:E DEBUG:V libc:V > "$OUT/logcat.txt" 2>&1

say "facts（adb forward + curl）…"
# 端口：默认 15577；被占时 selfcheck 会回退随机端口并写 real_port.txt
PORT="$( "$ADB" shell "run-as $PKG cat files/node-bridge/real_port.txt 2>/dev/null | tail -1" | tr -d '
' )"
[ -n "$PORT" ] || PORT=15577
say "port : $PORT"
"$ADB" forward tcp:$PORT tcp:$PORT >/dev/null 2>&1
curl -s --max-time 20 http://127.0.0.1:$PORT/facts > "$OUT/facts.json" 2>"$OUT/curl.err" || true
curl -s --max-time 10 http://127.0.0.1:$PORT/diag  > "$OUT/diag.json"  2>/dev/null || true

say "拉回沙箱日志（debug 包才能 run-as）…"
"$ADB" shell run-as "$PKG" cat files/node-bridge/node_bridge.log > "$OUT/node_bridge.log" 2>/dev/null || say "（run-as 不可用，日志只能从 logcat 看）"
"$ADB" shell run-as "$PKG" cat files/node-bridge/node_bridge.log.crash.json > "$OUT/last-crash.json" 2>/dev/null || true

if [ -s "$OUT/facts.json" ]; then
  say "✅ /facts 已拿到（$(wc -c < "$OUT/facts.json") B）"
  node -e '
    const f = require(process.argv[1]);
    console.log("node=" + f.node, "platform=" + f.platform, "arch=" + f.arch);
    console.log("jit: wasm=" + f.jit.wasm, "hotLoopMs=" + Number(f.jit.hotLoopMs).toFixed(1), "jitless=" + f.jit.jitless);
    console.log("fs.roundTrip=" + f.fs.roundTrip, "worker.ran=" + (f.worker && f.worker.ran));
    console.log("host.ping=" + JSON.stringify(f.host && f.host.ping));
  ' "$OUT/facts.json" 2>/dev/null | tee -a "$OUT/summary.txt"
else
  say "❌ /facts 为空 —— 看 $OUT/logcat.txt 的 stage/lastError"
fi

say "证据目录：$OUT"
