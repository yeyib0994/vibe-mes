#!/usr/bin/env bash
# FluxMES 外部系统模拟器一键启停（Windows / Git Bash）
#
# 起两个独立进程（都不属于 MES 应用，可单独 kill 用来演练「外部系统不可用」）：
#   ScadaSimulatorMain   OPC-UA Server   opc.tcp://localhost:4840/fluxmes
#   ExtMockServiceMain   LIMS/ERP/WMS    http://localhost:9100
#
# 用法：
#   bash scripts/sim-up.sh            启动（已在跑则报错退出）
#   bash scripts/sim-up.sh --stop     停止
#   bash scripts/sim-up.sh --status   查看状态
#   bash scripts/sim-up.sh --restart  重启
#
# 日志：tools/ext-simulator/target/sim-logs/{scada,ext-mock}.log
# PID ：tools/ext-simulator/target/sim.pids
#
# 注：就绪判定用 ASCII 的 "READY" 标记，不用中文串——中文在 Windows 控制台
#     可能被按 GBK 落盘，grep 中文会因编码不一致而永远等不到。
set -uo pipefail

export PATH="/usr/bin:/mingw64/bin:$PATH"
export LANG=zh_CN.UTF-8

ROOT="E:/Yeyib0/vibe-mes"
SIM_DIR="$ROOT/tools/ext-simulator"
TARGET="$SIM_DIR/target"
LOGS="$TARGET/sim-logs"
PIDS="$TARGET/sim.pids"
JAR="$TARGET/ext-simulator-0.1.0.jar"

SCADA_PORT="${SCADA_PORT:-4840}"
MOCK_PORT="${MOCK_PORT:-9100}"

# java 解析顺序：JAVA_HOME → WORKSPACE_JDK → PATH → 本机常见路径
resolve_java() {
  local c
  for c in "${JAVA_HOME:-}/bin/java" "${WORKSPACE_JDK:-}/bin/java"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return; }
  done
  if command -v java >/dev/null 2>&1; then command -v java; return; fi
  for c in "/e/App/Java21/bin/java" "/c/Users/yyb/.workbuddy/binaries/jdk/21.0.12+7/bin/java"; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  echo ""
}

JAVA="$(resolve_java)"

log() { echo "[sim-up] $*"; }

running_pids() {
  [ -f "$PIDS" ] || return 0
  while read -r pid; do
    [ -n "$pid" ] || continue
    kill -0 "$pid" 2>/dev/null && echo "$pid"
  done < "$PIDS"
}

stop_all() {
  local any=0
  if [ -f "$PIDS" ]; then
    while read -r pid; do
      [ -n "$pid" ] || continue
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null && log "已停止 PID $pid"
        any=1
      fi
    done < "$PIDS"
    rm -f "$PIDS"
  fi
  [ "$any" = 0 ] && log "没有在跑的模拟器"
  return 0
}

status_all() {
  if [ ! -f "$PIDS" ]; then log "未启动（无 PID 文件）"; return 0; fi
  local n=0
  while read -r pid; do
    [ -n "$pid" ] || continue
    if kill -0 "$pid" 2>/dev/null; then log "PID $pid 运行中"; n=$((n+1)); else log "PID $pid 已退出"; fi
  done < "$PIDS"
  [ -f "$LOGS/scada.log" ] && log "SCADA 就绪标记：$(grep -ac 'READY' "$LOGS/scada.log" 2>/dev/null || echo 0)"
  [ -f "$LOGS/ext-mock.log" ] && log "HTTP  就绪标记：$(grep -ac 'READY' "$LOGS/ext-mock.log" 2>/dev/null || echo 0)"
  log "存活进程数 $n"
}

build_if_needed() {
  if [ ! -f "$JAR" ]; then
    log "未找到 $JAR，先构建…"
    ( cd "$ROOT" && bash scripts/mvn-sim.sh package -q -DskipTests ) || {
      log "构建失败，请检查 Maven/JDK"; exit 1; }
  fi
}

wait_ready() {
  local file="$1" name="$2" tries="${3:-40}"
  for _ in $(seq 1 "$tries"); do
    grep -q "READY" "$file" 2>/dev/null && { log "$name 已就绪"; return 0; }
    sleep 1
  done
  log "$name 在 ${tries}s 内未就绪，看日志：$file"
  return 1
}

start_all() {
  [ -n "$JAVA" ] || { log "找不到 java，请设置 JAVA_HOME 或用 WORKSPACE_JDK"; exit 1; }
  if [ -n "$(running_pids)" ]; then
    log "已有模拟器在运行；先 --stop 或 --restart"
    exit 1
  fi

  build_if_needed
  mkdir -p "$LOGS"
  : > "$PIDS"

  log "启动 OPC-UA 模拟器 :$SCADA_PORT …"
  ( cd "$SIM_DIR" && "$JAVA" -Dstdout.encoding=UTF-8 \
      -cp "$JAR;target/lib/*" com.fluxmes.sim.ScadaSimulatorMain --port "$SCADA_PORT" \
      > "$LOGS/scada.log" 2>&1 ) &
  echo "$!" >> "$PIDS"

  log "启动 LIMS/ERP/WMS Mock :$MOCK_PORT …"
  ( cd "$SIM_DIR" && "$JAVA" -Dstdout.encoding=UTF-8 \
      -cp "$JAR;target/lib/*" com.fluxmes.sim.ExtMockServiceMain --port "$MOCK_PORT" \
      > "$LOGS/ext-mock.log" 2>&1 ) &
  echo "$!" >> "$PIDS"

  wait_ready "$LOGS/scada.log" "OPC-UA Server" 40
  wait_ready "$LOGS/ext-mock.log" "HTTP Mock 服务" 20

  log "完成。MES 侧把 fluxmes.integration.*.mode 改成 real 即可接上这四个替身。"
  log "  停止：bash scripts/sim-up.sh --stop"
}

case "${1:-}" in
  --stop)    stop_all ;;
  --status)  status_all ;;
  --restart) stop_all; sleep 2; start_all ;;
  -h|--help) sed -n '2,20p' "$0" ;;
  "")        start_all ;;
  *)         log "未知参数：$1（可用 --stop / --status / --restart）"; exit 1 ;;
esac
