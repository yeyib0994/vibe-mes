#!/usr/bin/env bash
# FluxMES 本地一键启动 + 端到端自检（Windows Git Bash / macOS / Linux）
#
#   bash scripts/dev-up.sh            # 起 PG + 后端 + 跑自检
#   bash scripts/dev-up.sh --frontend # 额外起前端（Vite 5173，/api 代理 8080）
#
# 前置：Docker Desktop 已启动（docker ps 可用）；Java 21；Node 18+
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

die() { echo "❌ $1"; exit 1; }

echo "==> 1/4 检查 Docker daemon"
docker info >/dev/null 2>&1 || die "Docker daemon 未就绪，请先启动 Docker Desktop"

echo "==> 2/4 启动 PostgreSQL（fluxmes-pg:5433）"
if docker ps -a --format '{{.Names}}' | grep -qx 'fluxmes-pg'; then
  docker start fluxmes-pg >/dev/null && echo "    已存在的容器已启动"
else
  docker run -d --name fluxmes-pg \
    -e POSTGRES_USER=fluxmes -e POSTGRES_PASSWORD=fluxmes -e POSTGRES_DB=fluxmes \
    -p 5433:5432 postgres:16 >/dev/null || die "创建 PG 容器失败"
  echo "    容器已创建"
fi
for i in $(seq 1 30); do
  docker exec fluxmes-pg pg_isready -U fluxmes >/dev/null 2>&1 && { echo "    PostgreSQL 就绪"; break; }
  sleep 2
done

echo "==> 3/4 启动后端（8080，自动建表 + 种子）"
JAR="$ROOT/apps/api-java/target/api-java-0.2.0.jar"
[ -f "$JAR" ] || die "找不到 $JAR，请先执行 mvn package"
nohup java -jar "$JAR" > /tmp/fluxmes-api.log 2>&1 &
for i in $(seq 1 40); do
  grep -q "Started Application" /tmp/fluxmes-api.log 2>/dev/null && { echo "    后端已启动"; break; }
  sleep 2
done
grep -q "Started Application" /tmp/fluxmes-api.log 2>/dev/null \
  || { echo "    后端启动异常，日志："; tail -20 /tmp/fluxmes-api.log; exit 1; }

if [[ "${1:-}" == "--frontend" ]]; then
  echo "==> 3.5/4 启动前端（5173）"
  (cd fluxmes && nohup npm run dev -- --port 5173 > /tmp/fluxmes-web.log 2>&1 &)
  echo "    前端启动中：http://localhost:5173"
fi

echo "==> 4/4 端到端自检"
node scripts/verify-phase1.mjs
echo
node scripts/verify-phase5.mjs
echo
node scripts/verify-phase6.mjs

if [[ "${1:-}" == "--all" || "${2:-}" == "--all" ]]; then
  echo
  echo "==> 追加全量回归（Phase E / G / H）"
  node scripts/verify-food-safety.mjs
  node scripts/verify-phase3.mjs
  node scripts/verify-phase4.mjs
fi

echo
echo "完成。契约：http://localhost:8080/v3/api-docs · 日志：/tmp/fluxmes-api.log"
echo "用法：bash scripts/dev-up.sh [--frontend] [--all]"
