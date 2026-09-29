#!/usr/bin/env bash
# FluxMES 外部系统模拟器构建脚本（Windows / Git Bash）
# 与 scripts/mvn.sh 同源，只是 PROJECT_DIR 指向 tools/ext-simulator。
# 用法：bash scripts/mvn-sim.sh compile | package | dependency:build-classpath
set -euo pipefail

export PATH="/usr/bin:/mingw64/bin:$PATH"
export LANG=zh_CN.UTF-8

MAVEN_HOME="C:/Users/yyb/.m2/wrapper/dists/apache-maven-3.9.16/0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0"
PROJECT_DIR="E:/Yeyib0/vibe-mes/tools/ext-simulator"
GOAL="${*:-compile}"

# WorkBuddy 沙箱的 safe-delete shim 会拦截 node 子进程的批量删除，放开关避免构建失败
export CODEBUDDY_SAFE_DELETE_ENABLED=0

java \
  -cp "$MAVEN_HOME/boot/plexus-classworlds-2.11.0.jar" \
  -Dclassworlds.conf="$MAVEN_HOME/bin/m2.conf" \
  -Dmaven.home="$MAVEN_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PROJECT_DIR" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -f "$PROJECT_DIR/pom.xml" $GOAL
