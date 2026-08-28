# FluxMES · 报警中心后端（apps/api-java）

FluxMES 报警中心的后端服务，依据 `specs/alarm-center/`（spec / plan / tasks）实现 **Phase 1 · P0 核心闭环**：
报警列表、确认（状态机 + 审计落库）、趋势、高频源、未确认实时计数。技术栈遵循 `specs/constitution.md`
第 5 节：**Java + 最新版 Spring Boot + OpenAPI**，PostgreSQL 持久化。

## 技术栈

- Java 21（LTS）
- Spring Boot 3.5.16（Web / Data JPA / Validation）
- PostgreSQL 16（`docker-compose.yml`）
- springdoc-openapi 2.8.17（Swagger UI）

## 目录结构

```
apps/api-java/
├─ pom.xml
├─ docker-compose.yml            # 本地 PostgreSQL（宿主端口 5433，避开 5432 占用）
├─ src/main/resources/application.yml
└─ src/main/java/com/fluxmes/
   ├─ FluxMesApplication.java
   ├─ config/OpenApiConfig.java
   └─ alarm/
      ├─ domain/    Alarm · AlarmAudit · AlarmLevel · AlarmStatus（状态机）
      ├─ repo/      AlarmRepository（含高频源聚合查询）
      ├─ dto/       对外契约（对齐 plan 第 2 节数据模型）
      ├─ service/   AlarmService（列表/确认+审计/趋势/高频源/统计/计数）
      ├─ web/       AlarmController · WebConfig(CORS)
      ├─ exception/ 状态机与 404/409 统一处理
      └─ bootstrap/ AlarmSeeder（首次启动播种示例数据）
```

## 运行

### 1. 启动数据库

```bash
cd apps/api-java
docker compose up -d          # Postgres 暴露于宿主 5433
```

> 本机 Docker Hub 被墙，compose 使用 `docker.m.daocloud.io` 镜像地址。

### 2. 启动后端

```bash
# Windows 用 mvnw.cmd，Linux/macOS 用 ./mvnw
mvnw.cmd spring-boot:run
# 或先打包再运行
mvnw.cmd clean package -DskipTests
java -jar target/fluxmes-alarm-api-1.0.0.jar
```

服务默认监听 `http://localhost:8080`。数据库连接可用环境变量覆盖：
`DB_URL` / `DB_USER` / `DB_PASSWORD`（默认 `jdbc:postgresql://localhost:5433/fluxmes` / `fluxmes` / `fluxmes`）。

### 3. 启动前端并联调

```bash
cd fluxmes
npm install        # 首次
npm run dev        # http://localhost:5173
```

Vite dev server 已将 `/api` 代理到 `http://localhost:8080`（见 `fluxmes/vite.config.js`），同源免 CORS。
前端服务层 `fluxmes/src/api/alarms.js` 默认走 HTTP；置环境变量 `VITE_USE_MOCK=true` 可回退到 Phase 0 本地 Mock。

## API 契约（OpenAPI）

- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`

| 方法 | 路径 | 说明 | 对应需求/任务 |
| --- | --- | --- | --- |
| GET | `/api/alarms` | 列表 + 生成时间 + 未确认计数 | FR-1/7 · T1/T2 |
| GET | `/api/alarms/unacked-count` | 未确认实时计数（侧栏/顶栏唯一来源） | FR-7 · T2 |
| POST | `/api/alarms/{id}/ack` | 确认，`unacked→acked`，写审计 | FR-3/10 · T3 |
| POST | `/api/alarms/{id}/recover` | 恢复，`→recovered`，`?auto=true` 系统自动恢复，写审计 | FR-4 · T4 |
| GET | `/api/alarms/stream` | SSE 长连接，推送 created/acked/recovered 事件 | NFR-1 · T5 |
| GET | `/api/alarms/trend?hours=8` | 按小时按级别堆叠趋势 | FR-5 · T1 |
| GET | `/api/alarms/top-sources?days=7&limit=5` | 高频报警源排行 | FR-6 · T1 |
| GET | `/api/alarms/stats` | 今日/活跃/平均响应/确认率 KPI | 页面 KPI |

### 确认示例

```bash
curl -X POST http://localhost:8080/api/alarms/A-260828-017/ack \
  -H "Content-Type: application/json" \
  -d '{"operator":"陈志远"}'
```

返回更新后的报警对象，`audit` 追加一条 `ACK`（who/when/before/after）。
- 报警不存在 → `404`
- 非法状态流转（非未确认再确认）→ `409`（状态机守卫，避免 SLA 误计 / 乐观更新回滚，风险 R2/R3）

## 状态机

```
unacked → acked → recovered → closed
unacked → recovered
acked   → closed
```

`AlarmStatus.canTransitionTo` 集中维护合法流转；恢复即停止 SLA 计时。

## 合规落点（constitution）

- **C3 审计**：`alarm_audit` 记录 CREATE/ACK/…，仅追加、不可篡改。
- **C4 权限**：`operator` 为 RBAC 解析入口（原型阶段前端显式传入，生产由服务端会话注入）。
- **C6 留存**：PostgreSQL 持久化，按设备/级别/时间可检索；冷/热分层与 ≥3 年归档为 Phase 1 后续（T9）。

## 恢复与实时推送（T4/T5）

- **恢复**：`POST /api/alarms/{id}/recover`，状态机进入 `recovered`、记录 `recoveredAt` 并追加 `RECOVER` 审计；
  `?auto=true` 表示系统自动恢复（操作人记为「系统」），否则人工标记。恢复即停止 SLA 计时（风险 R3）。
  前端报警列表在「已确认」行提供「恢复」按钮，走乐观更新 + 失败回滚。
- **SSE 实时流**：`GET /api/alarms/stream`（`text/event-stream`）。任何 `created/acked/recovered` 变更都会推送
  `event: alarm`，前端 `useAlarmRealtime()`（在 `App.jsx` 全局挂载）收到即失效回查相关查询，新报警与计数即时刷新，
  远快于 30s 轮询（NFR-1）。`EventSource` 断线自动重连。
- **模拟 SCADA/OPC-UA 适配器**：`AlarmSimulator` 周期性产生新报警、持久化并经 SSE 推送，用于演示实时接入。
  生产替换为真实 OPC-UA 订阅即可，边界（`AlarmService.create` → `AlarmNotifier.publish`）不变。开关与节奏：

  ```yaml
  fluxmes:
    alarm:
      simulator:
        enabled: true        # 生产置 false
        initial-delay-ms: 15000
        interval-ms: 25000
  ```

## 实现进展

- 已完成：**T1** 数据服务化 · **T2** 未确认实时计数 · **T3** 确认持久化+审计 ·
  **T4** 恢复状态机（端点 + 审计 + 前端交互） · **T5 的推送部分**（SSE + 模拟 SCADA 事件流）。
- 排期后续：
  - T5 真实 SCADA/OPC-UA 订阅接入（替换 `AlarmSimulator`）
  - T6 响应 SLA 计时与升级通知（阈值已在 `application.yml` 预留 `fluxmes.alarm.sla-minutes`）
  - T8 报警↔设备/批次跳转、T9 分层冷存储与检索、T10 声光强提示
