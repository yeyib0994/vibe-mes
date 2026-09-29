#!/usr/bin/env bash
# FluxMES PostgreSQL 容器（Docker Desktop）
# 用法：bash deploy/run-pg.sh   （需先启动 Docker Desktop）
set -e

docker run -d \
  --name fluxmes-pg \
  -e POSTGRES_USER=fluxmes \
  -e POSTGRES_PASSWORD=fluxmes \
  -e POSTGRES_DB=fluxmes \
  -p 5432:5432 \
  postgres:16

echo "PostgreSQL 已启动：jdbc:postgresql://localhost:5433/fluxmes (fluxmes/fluxmes)"
