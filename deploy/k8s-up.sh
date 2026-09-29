#!/usr/bin/env bash
# FluxMES 一键打包并部署到本地 K8s（Docker Desktop / docker-desktop context）
# 前置：① 已执行 bash scripts/mvn.sh -DskipTests package（后端 jar）
#       ② 已执行 cd fluxmes && npm run build（前端 dist）
#       ③ Docker Desktop 已启动且 K8s 集群运行中
# 用法：bash deploy/k8s-up.sh          # 构建镜像 + 部署
#       bash deploy/k8s-up.sh --apply-only   # 跳过镜像构建，仅重新 apply
set -euo pipefail
export PATH="/usr/bin:/mingw64/bin:$PATH"
export LANG=zh_CN.UTF-8
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── 前置检查 ────────────────────────────────────────────────────────────────
docker version >/dev/null 2>&1 || { echo "✗ Docker daemon 未就绪，请先启动 Docker Desktop"; exit 1; }
kubectl get nodes >/dev/null 2>&1 || { echo "✗ K8s 集群未就绪（确认 kubectl context 为 docker-desktop）"; exit 1; }
[ -f "$ROOT/apps/api-java/target/api-java-0.2.0.jar" ] || { echo "✗ 缺少后端 jar，先执行: bash scripts/mvn.sh -DskipTests package"; exit 1; }
[ -f "$ROOT/fluxmes/dist/index.html" ] || { echo "✗ 缺少前端 dist，先执行: cd fluxmes && npm run build"; exit 1; }

# ── 构建镜像 ────────────────────────────────────────────────────────────────
if [ "${1:-}" != "--apply-only" ]; then
  echo "==> docker build fluxmes-api:local"
  docker build -t fluxmes-api:local "$ROOT/apps/api-java"
  echo "==> docker build fluxmes-web:local"
  docker build -t fluxmes-web:local "$ROOT/fluxmes"
fi

# ── 部署（顺序滚动：pg → api → web）────────────────────────────────────────
kubectl apply -f "$ROOT/deploy/k8s/fluxmes.yaml"

echo "==> 等待 PostgreSQL 就绪（首次拉取 postgres:16 镜像可能较慢）"
kubectl -n fluxmes rollout status deploy/fluxmes-pg  --timeout=300s
echo "==> 等待 API 就绪（首次启动含 6 片 schema 建表 + 全量 Seeder 播种）"
kubectl -n fluxmes rollout status deploy/fluxmes-api --timeout=300s
echo "==> 等待 Web 就绪"
kubectl -n fluxmes rollout status deploy/fluxmes-web --timeout=120s

echo
echo "✅ 部署完成 → http://localhost:8088   （演示账号 admin/admin123；80 端口已被 vibe-erp/web 占用）"
echo
kubectl -n fluxmes get pods,svc
