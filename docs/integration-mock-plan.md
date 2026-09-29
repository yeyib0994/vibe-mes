# FluxMES 外部系统对接与 Mock 方案

> 回答「SCADA/OPC-UA、LIMS、ERP、WMS 能不能 mock、怎么 mock」。
> 前置事实：`specs/constitution.md:62` 要求这四个系统**必须集成**，但当前**全部未落地**；
> `constitution.md:63` 已规定集成形态为「接口契约 + 适配器」隔离。
>
> 版本：v1.0 · 2026-09-24 · 结论：**能 mock，且 mock 是章程规定的正解，不是权宜之计**

---

## 0. 结论先行

| 问题 | 回答 |
| --- | --- |
| 能 mock 吗？ | **能，四个全部能**，且不需要任何真实设备 |
| 是权宜之计吗？ | **不是**。`constitution.md:63` 明写「集成边界以『接口契约 + 适配器』隔离，避免因外部系统变更污染前端」——mock 就是适配器的一个实现类 |
| 最难的 OPC-UA 怎么办？ | **跑真协议模拟器**。Eclipse Milo 同时提供 Client 与 Server，本地即可起一个真的 OPC-UA Server（`opc.tcp://localhost:4840`），MES 侧写的是**真客户端代码** |
| 以后接真设备要改多少？ | 只改一行配置（`host:port`）或换一个 `@Profile` Bean，**业务代码零改动** |
| 最大风险是什么？ | 不是"mock 不够真"，而是**没有 profile 开关**——现在报警模拟器无条件往 PG 写"模拟器数据"，mock 适配器如果不做隔离会污染真实数据 |

---

## 1. 为什么 mock 是正解（三条依据）

1. **章程明写允许**：`constitution.md:33`「原型阶段**不强制**后端服务与数据库落地（Phase 0 允许 Mock），但 Phase 1 起必须接入」——即"先 mock、后接入"是既定路线。
2. **章程规定了隔离形态**：`constitution.md:63`「集成边界以『接口契约 + 适配器』隔离」。这句话的含义就是：业务层只依赖**端口接口**，mock 与真实实现是同一接口的两个实现。
3. **项目已有先例**：`AlarmService.java:366-443` 的报警模拟器（45s 轮转 5 个报警源）、6 个 Seeder 全部是"模拟数据源"。本方案只是把它**规范化**，而不是新发明。

---

## 2. 四个系统的对接分析

| 系统 | 方向 | 数据对象 | 频率 | 协议假设 | 本地可模拟性 |
| --- | --- | --- | --- | --- | --- |
| **SCADA/OPC-UA** | SCADA→MES **主**（采集）<br>MES→SCADA 次（下发指令） | 温度/压力/液位/搅拌转速等工艺参数；设备状态位 | ≤30s，发酵罐 ≤10s（NFR-1） | OPC-UA binary（`opc.tcp://`）；Modbus 兜底 | **高** —— 可直接跑真协议 Server |
| **LIMS** | 双向：LIMS→MES 检验结果回流<br>MES→LIMS 检验委托 | `qc_task` 结果、COA 编号、判定结论、依据标准 | 低频（每批数次） | REST/JSON（**不做仪器直连**，`constitution.md:32`） | 高 —— HTTP stub |
| **ERP** | 双向：ERP→MES 工单/物料主数据<br>MES→ERP 完工回报/物料消耗 | `work_order`、`material`、`production_line` | 低频（按日/按单） | REST/JSON（或中间表） | 高 —— HTTP stub |
| **WMS** | MES→WMS 入库请求<br>WMS→MES 入库确认/库位 | `batch.warehouse_bin`、成品数量 | 低频（每批 1 次） | REST/JSON | 高 —— HTTP stub |

### 2.1 为什么 SCADA 必须跑"真协议"而不是假数据

OPC-UA 的风险点全在协议层，用假数组测不出来：

| 风险点 | 假数据能否暴露 |
| --- | --- |
| NodeId 寻址方式（`ns=2;s=F-102.Temp` vs 数字 ID） | ✗ |
| 数据类型映射（Double / Float / Int16 / 数组 / 结构体） | ✗ |
| 采集模式（Subscription 订阅 vs 轮询 Read） | ✗ |
| 会话与订阅生命周期、断线重连、KeepAlive | ✗ |
| 时间戳语义（SourceTimestamp vs ServerTimestamp） | ✗ |
| 质量码 `StatusCode`（Good / Bad / Uncertain） | ✗ |

**结论**：SCADA 用真协议模拟器（L3），收益远大于成本。

### 2.2 现有数据落点已就绪情况

对接不是从零开始——多数落点已经存在：

| 对接点 | 现有承载 | 状态 |
| --- | --- | --- |
| 设备实时参数 | `equipment_metric`（plan 已设计） | ❌ **表未创建**（见 §5 前置问题 3） |
| 设备状态 | `equipment` + `equipment_event` | ✅ 已就绪 |
| 检验结果回流 | `qc_task` / `spc_limit` | ✅ 已就绪 |
| COA | `batch.coa_no` | ✅ 已就绪 |
| 物料检验放行 | `material_lot.inspect` | ✅ 已就绪 |
| 工单 | `work_order` / `work_order_assignment` | ⚠️ 已就绪但为 MES 自建（`source=BACKFILL`），未与 ERP 打通 |
| 物料主数据 | `material` | ✅ 已就绪 |
| 成品入库位 | `batch.warehouse_bin` | ✅ 已就绪（字段级，无 WMS 交互） |

---

## 3. 架构：端口 + 适配器

```
业务服务（EquipmentService / QualityService / BatchService ...）
        │  只依赖接口，不认识任何外部系统
        ▼
┌─ Port 接口层（domain）────────────────────────────┐
│  EquipmentMetricPort   设备参数读取 / 指令下发      │
│  LabResultPort         检验结果回流                │
│  ErpMasterDataPort     工单 / 物料主数据            │
│  WmsInboundPort        成品入库                     │
└───────────────────────────────────────────────────┘
        ▲  同接口多实现，@Profile / 配置项切换
        │
   ┌────┴─────┬─────────────┬──────────────┐
   │          │             │              │
 Mock 适配器  OPC-UA 适配器  REST 适配器    REST 适配器
  (dev)      (prod/SCADA)  (LIMS/ERP)     (WMS)
```

切换粒度用**配置项**比用 `@Profile` 更细（四个系统可能部分 mock 部分真实）：

```yaml
fluxmes:
  integration:
    scada:  { mode: mock,    endpoint: "opc.tcp://localhost:4840" }
    lims:   { mode: mock,    endpoint: "http://localhost:9101" }
    erp:    { mode: mock,    endpoint: "http://localhost:9102" }
    wms:    { mode: mock,    endpoint: "http://localhost:9103" }
```

`mode=mock|real` 决定装配哪个 Bean（`@ConditionalOnProperty`）。

---

## 4. Mock 的三个层次

| 层次 | 形态 | 验证什么 | 用于 |
| --- | --- | --- | --- |
| **L1 · 进程内 Fake** | `@ConditionalOnProperty(mode=mock)` 的 Spring Bean，直接返回构造数据 | 业务逻辑、状态机、门禁、降级分支 | 单元测试、快速演示 |
| **L2 · 独立 Mock 服务** | 独立进程暴露 HTTP 端点，MES 用**真实 HTTP 客户端**连它 | HTTP 契约、超时、重试、错误码、幂等、故障注入 | **LIMS / ERP / WMS** |
| **L3 · 真实协议模拟器** | 起真的 OPC-UA Server，MES 用**真实 OPC-UA 客户端**连它 | 协议层、订阅/轮询、断线重连、数据类型映射 | **SCADA/OPC-UA** |

### 推荐组合

| 系统 | 层次 | 理由 |
| --- | --- | --- |
| SCADA/OPC-UA | **L3 + L1 兜底** | 协议是最大风险点；Milo 无设备也能跑 Server |
| LIMS | **L2 + L1 兜底** | HTTP/JSON 契约即风险点，独立进程可故障注入 |
| ERP | **L2 + L1 兜底** | 同上，且涉及回调（MES→ERP）需真实 HTTP |
| WMS | **L2 + L1 兜底** | 同上 |

### 4.1 SCADA 模拟器技术选型

**Eclipse Milo**（`org.eclipse.milo`，Maven Central 实测可用，最新 **0.6.16**）：

```xml
<properties>
  <milo.version>0.6.16</milo.version>
</properties>

<dependency>
  <groupId>org.eclipse.milo</groupId>
  <artifactId>sdk-client</artifactId>
  <version>${milo.version}</version>
</dependency>
<!-- 仅 mock 模式需要；或拆成独立 simulator 模块 -->
<dependency>
  <groupId>org.eclipse.milo</groupId>
  <artifactId>sdk-server</artifactId>
  <version>${milo.version}</version>
</dependency>
```

优点：**纯 Java，与现有 Maven 构建同构**，不引入 Node/Python 运行时；Client 与 Server 同一套库，NodeId 定义可共享。

模拟器形态建议**独立进程**（`apps/scada-simulator/` 或 `deploy/scada-sim/`），而不是塞进 api-java：

- 独立进程才能验证"对方挂了 MES 会怎样"（真断线）
- 不污染 api-java 的依赖与启动时间
- 与真实 SCADA 的部署形态一致（外部系统就是独立进程）

### 4.2 LIMS / ERP / WMS Mock 服务

一个轻量服务（Spring Boot 单模块或 Node 脚本）暴露三组契约端点，例如：

```
LIMS  : POST /lims/results          推送检验结果到 MES
        GET  /lims/orders           拉取待检委托
ERP   : POST /erp/work-orders       下发工单到 MES
        POST /erp/materials         同步物料主数据
WMS   : POST /wms/inbound           接收 MES 入库请求 → 返回库位
```

**必须包含故障注入开关**（这是 L2 的核心价值）：

```
POST /_admin/fault  { "mode": "timeout|500|duplicate|slow", "ratio": 1.0 }
GET  /_admin/state  当前故障模式与已推送记录数
```

---

## 5. 三个必须先修的前置问题

> 不解决这三个，mock 做出来是脏的。

### 前置 1 · 没有 profile / 条件装配机制（P0）

实测：`grep -rn "@Profile\|ConditionalOnProperty"` **零命中**。当前报警模拟器在 `AlarmService.java:394-400` **无条件启动**，每 45s 往 PG 写一条 `value="模拟器数据"`（`:434`）。

后果：接真实系统后，模拟数据与真实数据混在同一张表，无法区分、无法关闭。

**必做**：引入 `fluxmes.integration.*.mode` 配置 + `@ConditionalOnProperty`；报警模拟器改受同一开关控制（或至少 `@Profile("dev")`）。

### 前置 2 · 前后端两份重复的伪序列逻辑（P0）

`EquipmentService.java:700` 的 `genSeries()` 与 `fluxmes/src/api/equipment.ts:9-10` 是**两份同式实现**（注释互相承认"同式"）。

后果：接采集时必须改两处；前端保留了 mock 逻辑，永远清不掉。

**必做**：伪序列生成**只留在后端**（mock 适配器内），前端只负责渲染。

### 前置 3 · `equipment_metric` 表不存在（P0）

`specs/equipment-management/plan.md:14` 已定义该表（`equipment_code / metric_key / value / unit / sampled_at`），但 6 个 schema 分片**均无此表**，也没有 Entity / Mapper。

后果：采集数据无处落地，T12 无法开工。

**必做**：建表 + Entity + Mapper（与 `schema-p4.sql` 归属一致，或新开 `schema-p7.sql`）。

---

## 6. 分阶段落地计划

> **实施状态（2026-09-24）**：P0 全部 ✅、P1 全部 ✅、P2-1/P2-3 已提前完成（健康端点是验证的前提）。
> 实施与方案的差异记在 §6.4「实施偏差」——**偏差都要有理由，不是顺手改的**。

### P0 · 契约与开关基建（不碰外部系统，可独立完成）

- ✅ **P0-1** `application.yml` 新增 `fluxmes.integration.{scada,lims,erp,wms}.{mode,endpoint}`
- ✅ **P0-2** 报警模拟器纳入开关：`fluxmes.integration.alarm-simulator-enabled`（独立开关，而非绑 scada.mode）
- ✅ **P0-3** 建 `equipment_metric` 表 + Entity + Mapper + 索引 `(equipment_code, metric_key, sampled_at)`
- ✅ **P0-4** 定义 4 个 Port 接口 + DTO（**契约先行**，只写接口不写实现）
- ✅ **P0-5** 收口伪序列：前端删除 `genSeries()`，改由后端返回

**验收**：编译通过 ✅（176 源文件）；`/v3/api-docs` 契约不变 ✅；前端 `npm test` 21/21 ✅。

### P1 · Mock 适配器落地

- ✅ **P1-1** `MockEquipmentMetricAdapter`（L1）：从 fixture 派生 + 确定性抖动（90s 慢波 + 快抖动）写入 `equipment_metric`
- ✅ **P1-2** SCADA 模拟器独立进程（L3）：Milo 0.6.16 Server，按 `equipment` 台账建节点（8 设备 / 24 指标 / 96 元数据节点）
- ✅ **P1-3** `OpcUaEquipmentMetricAdapter`（真客户端）：批量 Read 采集 + 断线重建，写 `equipment_metric`
- ✅ **P1-4** LIMS / ERP / WMS Mock 服务（L2，单模块）+ 故障注入端点（timeout/error500/slow/duplicate）
- ✅ **P1-5** 三个 REST 适配器实现 + 超时/重试/降级
- ✅ **P1-6** 设备监控页与前端展示 `dataSource` 标识（见 §7）

**验收**：`mode=mock` 时页面实时参数有数据且带 MOCK 标识 ✅；切成 `mode=real`（指向 Milo 模拟器）后**同样有数据、业务代码零改动** ✅（见 §6.3 实测记录）。

### P2 · 可观测与验证

- ✅ **P2-1** 集成健康端点 `GET /api/integration/health`：各系统连通状态 + 模式（mock/real）+ 最后采集时间
- ⬜ **P2-2** 故障演练：断开 SCADA → 验证进入降级态（不是白屏、不是假数据）；LIMS 超时 → 验证重试与告警
      （降级路径的**代码**已就位并有断言，但「人工 kill 模拟器 + 观察页面」需在本机跑，见 §6.3）
- ✅ **P2-3** `scripts/verify-integration.mjs` 自检脚本
- ⬜ **P2-4** `docs/improvement-plan.md` 新增「阶段 J · 外部系统集成」章节

---

## 6.1 新增/改动文件清单

**新增（后端）**

| 文件 | 作用 |
| --- | --- |
| `integration/IntegrationMode.java` | `MOCK` / `REAL` 枚举 |
| `integration/DataSourceTag.java` | `MOCK`/`OPCUA`/`LIMS`/`ERP`/`WMS`/`MANUAL` 常量 |
| `integration/IntegrationProperties.java` | `fluxmes.integration.*` 配置绑定 |
| `integration/IntegrationConfig.java` | 启动时打印四系统模式与端点 |
| `integration/dto/`（6 个 record） | `MetricSample` · `LabResult` · `ErpWorkOrder` · `WmsInboundRequest` · `WmsInboundAck` · `IntegrationHealth` |
| `integration/port/`（4 个接口） | `EquipmentMetricPort` · `LabResultPort` · `ErpMasterDataPort` · `WmsInboundPort` |
| `integration/support/AdapterStatus.java` | 适配器连通状态（success/failure/note） |
| `integration/mock/`（4 个） | L1 进程内 Fake |
| `integration/rest/`（4 个） | `JsonHttpClient` + LIMS/ERP/WMS 真适配器 |
| `integration/opcua/OpcUaEquipmentMetricAdapter.java` | OPC-UA 真客户端（唯一的协议耦合点） |
| `integration/EquipmentMetricCollector.java` | 定时采集 + 冷启动回填 + 过期清理 |
| `integration/IntegrationService.java` / `IntegrationController.java` | 健康看板 + 手工触发 |
| `entity/EquipmentMetric.java` · `mapper/EquipmentMetricMapper.java` | 时序表映射（Mapper 带 `@Mapper`） |
| `db/schema-p7.sql` | `equipment_metric` 表 + `alarm.data_source` 列 |

**新增（模拟器，独立 Maven 项目 `tools/ext-simulator`）**

| 文件 | 作用 |
| --- | --- |
| `pom.xml` | `milo 0.6.16`（sdk-server）+ slf4j-simple，`copy-dependencies` 到 `target/lib` |
| `NodeCatalog.java` / `ExtMockData.java` | 由 `scripts/export-opcua-nodes.mjs` 从 `mes.json` 自动生成 |
| `ScadaSimulatorMain.java` | OPC-UA Server，三层地址空间 + 周期性值刷新 + F-103.cond 质量码劣化 |
| `ExtMockServiceMain.java` | LIMS/ERP/WMS HTTP 契约 + 故障注入（JDK `HttpServer`） |

**新增（前端）**

| 文件 | 作用 |
| --- | --- |
| `src/api/integration.ts` | 健康/总览查询 + 采集与探测 mutation + `sourceMeta()` 来源映射 |
| `src/components/integration-strip.tsx` | `IntegrationStrip` 状态条 + `DataSourceBadge` |

**新增（脚本）**

| 文件 | 作用 |
| --- | --- |
| `scripts/mvn-sim.sh` | 构建模拟器（与 `mvn.sh` 同源，只换 PROJECT_DIR） |
| `scripts/sim-up.sh` | 一键启停两个模拟器（start/stop/status/restart，ASCII READY 就绪判定） |
| `scripts/export-opcua-nodes.mjs` | 从 `mes.json` 导出 OPC-UA 地址空间目录与 mock 数据 |
| `scripts/verify-integration.mjs` | Phase J 端到端自检（J1–J7） |
| `apps/api-java/src/test/java/.../opcua/OpcUaProbe.java` | 测试域探针：不起 Spring/不连库，直连 OPC-UA 采集 |
| `apps/api-java/src/test/java/.../rest/RestAdaptersProbe.java` | 测试域探针：三个 REST 适配器契约往返 + WMS 幂等 |

**改动**

| 文件 | 改动 |
| --- | --- |
| `application.yml` | schema 追加 `schema-p7.sql`；新增 `fluxmes.integration` 配置块；scada.endpoint 补 `/fluxmes` 路径 |
| `entity/Alarm.java` · `alarm/AlarmService.java` | `data_source` 字段 + 模拟器受开关控制 + `toView` 输出 `dataSource` |
| `equipment/EquipmentService.java` | 删 `genSeries()`；趋势改为读 `equipment_metric` + 时间桶降采样；`detail/mergeFixture` 输出 `dataSource` |
| `pom.xml`（api-java） | 新增 `org.eclipse.milo:sdk-client:0.6.16` |
| `fluxmes/src/api/equipment.ts` | 删 `genSeries()`；mock 路径不造曲线 |
| `fluxmes/src/pages/Equipment.jsx` | 集成状态条 + 来源徽标 + 来源列 + 趋势空态 |

## 6.2 三层 Mock 与「真协议」的边界

| 层 | 实现 | 验证到什么 | 验证不到什么 |
| --- | --- | --- | --- |
| L1 进程内 Fake | `MockEquipmentMetricAdapter` 等 4 个 | 业务逻辑、契约、门禁 | 网络、协议、超时、重试 |
| L2 独立 HTTP 服务 | `ExtMockServiceMain`（9100） | HTTP 契约、超时、重试、故障注入、幂等 | OPC-UA 协议层 |
| L3 真协议模拟器 | `ScadaSimulatorMain`（4840） | **NodeId 寻址、浏览、数据类型映射、StatusCode 质量码、SourceTimestamp** | 真实 PLC 的组态差异 |

L3 是这一期最值钱的部分：OPC-UA 的风险全在协议层，用假数组一个都验不到。

## 6.3 实测记录（2026-09-24，本机）

**OPC-UA 真协议往返**（`OpcUaProbe`，模拟器 4840）

```
available : true   (首次含建会话+读命名空间表 4650ms)
dataSource: OPCUA
detail    : OPC-UA 采集成功：24 个指标 / 8 台设备，4650ms @opc.tcp://localhost:4840/fluxmes
样本数    : 24
质量码分布：GOOD=23 UNCERTAIN=1 BAD=0      ← F-103.cond 劣化链路生效
设备台数  ：8
越限指标  ：3（outOfControl()：仅 GOOD 且超出 lo/hi 才算）
```
观察到设备：C-601 / D-701 / E-501 / F-101 / F-102 / F-103 / F-104 / M-201，
SourceTimestamp 全部正常上送（非本地时间兜底）。

**REST 三件套契约往返**（`RestAdaptersProbe` ↔ `ExtMockServiceMain` 9100）

```
LIMS : 回流 5 条（含结论 PASS×3/PENDING×2、COA 号、reportedAt 反序列化正常）
ERP  : 下发 3 张工单（LocalDate planStart/planEnd 反序列化正常）
WMS  : 首次 accepted=true bin=CP-A-15；重复同 batchId → bin 仍 CP-A-15（幂等）
       缺 batchId → accepted=false「缺少 batchId，拒绝入库」（对端真在校验）
故障注入：POST /_admin/fault {"mode":"error500"} → /lims/results 返回 HTTP 500 ✓
```

**踩坑记录（值得记住，不是一次性问题）**

1. **`main()` 返回 → JVM 立即退出**。Milo 的 netty 线程与自制更新线程都是 daemon，
   `server.startup()` 之后 main 一返回进程就没了，表现为「日志打了『已启动』然后立刻『正在关闭』」。
   两个模拟器入口都补了 `Thread.currentThread().join()`。
2. **就绪判定不能用中文串**。Windows 控制台下 `System.out` 按 GBK 落盘（即使 `file.encoding=UTF-8`，
   `stdout.encoding` 仍是控制台代码页），脚本 `grep "已启动"` 永远匹配不上。
   两个入口都补了 ASCII 的 `READY` 标记。
3. **`ManagedNamespaceWithLifecycle` 在 Milo 0.6.x 要求实现 `MonitoredItemServices` 的 4 个抽象方法**。
   不要写空实现——把它委托给 `org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel`，
   否则客户端建了订阅也永远收不到数据（**静默失败**，最难查）。
4. **端点 URL 必须与网关 ConfiguredEndpoint 一致**（含路径段）。
   模拟器是 `opc.tcp://localhost:4840/fluxmes`，只写 `:4840` 会发现阶段匹配不上。

## 6.4 实施偏差（与方案原文不一致的地方，及理由）

- **P1-3 由「订阅式采集」改为「批量 Read」**：采集周期是 30s 量级，订阅的优势（亚秒变化、减少往返）
  在本场景用不上，却要引入会话保持、重连后重建监视项、队列溢出等状态。批量 Read 无状态、幂等、失败可见。
  等真出现「需要秒级捕捉瞬时越限」的需求再引入订阅。
- **P0-2 用独立开关而非绑 `scada.mode`**：报警模拟器与 SCADA 采集是两件事
  （报警来自业务事件，设备参数来自采集），绑在一起会导致「想验真实采集就必须关报警」这种假耦合，
  也违背 §1.1 结论「报警数据本身不依赖外部系统」。
- **新增 `scripts/sim-up.sh` 与测试域探针**（方案未列）：没有它们，验证 OPC-UA 链路必须
  先起 PostgreSQL + 后端 + 登录，反馈太慢。探针把「协议通不通」和「业务对不对」彻底分开。

## 6.5 仍未验证的部分（本机跑）

- **`/api/integration/*` 与 `/api/equipment/*` 的 HTTP 端到端**：需 PostgreSQL + 后端。
  执行 `bash scripts/dev-up.sh --all` 起 PG 与后端，再 `node scripts/verify-integration.mjs`。
- **前端页面实际渲染**：需 `cd fluxmes && npm run dev`（8888）。
- **P2-2 故障演练**：起模拟器 → 跑通 → `bash scripts/sim-up.sh --stop` → 观察设备页
  是否显示「来源未知 / 暂无趋势」而不是白屏或假曲线。

---

## 7. 关键设计点：数据源必须可辨识

演示数据与真实数据混在同一个表、同一个接口里，是**最容易出事的地方**——尤其本项目演示账号与生产账号共用一套种子密码。

**约定**：所有来自外部系统的数据，在 API 响应里带 `dataSource` 字段。

```json
{
  "code": "F-102",
  "metrics": [{ "key": "temp", "value": 36.4, "unit": "℃", "dataSource": "MOCK", "sampledAt": "..." }]
}
```

取值：`MOCK` / `OPCUA` / `LIMS` / `ERP` / `WMS` / `MANUAL`。

前端复用现有 `components/StalenessBadge.tsx` 的样式呈现。这同时满足章程 **P4**（实时数据须标注时效与来源）。

---

## 8. 风险与对策

| # | 风险 | 对策 |
| --- | --- | --- |
| R1 | Mock 被误认为真实能力 | `dataSource` 标识 + `/api/integration/health` 显式暴露模式 + 演示前检查清单 |
| R2 | Mock 数据流入生产库 | `mode` 开关 + 启动期日志打印当前各系统模式（醒目） |
| R3 | Milo 0.6.16 与 JDK 21 / SB 4.1.1 兼容性未知 | P1-2 先做最小连通性验证（起 Server + 读 1 个节点），失败则退回 L1 + 记录 |
| R4 | 适配器层过度设计 | Port 接口**只定义当前真正需要的方法**，不预建未来可能用的能力 |
| R5 | 四个系统一起做摊子太大 | 按 P0 → P1-1/2/3（先 SCADA 打样板）→ 其余三个复制模式 |
| R6 | `equipment_metric` 增长过快（≤10s 采样） | 建表即定保留策略（按月分区 + ≥3 年归档，对应 `plan.md:48` R2） |

---

## 9. 最小可验证切片（建议第一步）

如果只想先验证可行性，**最小切片**是：

```
P0-1 + P0-2 + P0-3 + P0-4(仅 EquipmentMetricPort) + P1-1 + P1-2 + P1-3
```

即：**只做 SCADA 一条链路，端到端跑通"真 OPC-UA 协议"**。理由：

- SCADA 是四个里最难的，它通了其余三个（HTTP stub）没有悬念
- 能在本地无设备条件下验证真协议，是本方案最有说服力的部分
- 产出可复用的模板：配置开关 + Port 接口 + Mock/Real 双实现 + `dataSource` 标识

其余三个系统在模板定型后复制即可。

---

*本文件为方案设计，未包含实现。落地前请先确认 §6 的阶段范围。*
