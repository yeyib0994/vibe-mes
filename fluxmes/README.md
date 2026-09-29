# FluxMES · 流程制造执行系统（高保真原型）

基于 Vercel 视觉风格的深色主题 MES 多页面原型，面向流程制造（化工/食品，示例数据为柠檬酸发酵产线）场景。

## 技术栈

React 18 + Vite 5 + Tailwind CSS 3，组件层为 shadcn/ui 风格自研实现（`src/components/ui.jsx`），图表使用 Recharts，图标使用 lucide-react，字体为 Geist / Geist Mono。

## 本地运行

```bash
npm install
npm run dev      # 开发模式，默认 http://localhost:5173
npm run build    # 生产构建，输出到 dist/
```

## 页面结构

| 页面 | 入口 | 内容 |
| --- | --- | --- |
| 生产驾驶舱 | `src/pages/Dashboard.jsx` | KPI、产量趋势、罐区状态、在制批次、最新报警 |
| 批次管理 | `src/pages/Batches.jsx` | 批次台账、筛选搜索、可展开的工艺路线与批档案 |
| 质量管理 | `src/pages/Quality.jsx` | SPC 控制图、不合格帕累托、检验任务 |
| 报警中心 | `src/pages/Alarms.jsx` | 报警趋势、高频报警源、报警确认交互 |
| 设备监控 | `src/pages/Equipment.jsx` | 设备集群 KPI、参数趋势、维护到期 |
| 配方管理 | `src/pages/Recipes.jsx` | 配方台账、版本历史、CCP 参数 |
| 追溯查询 | `src/pages/Trace.jsx` | 批次正/逆向追溯链 |

## 后端服务（Phase 1，apps/api-java）

Spring Boot 3 + Java 21，端口 8080，前端经 Vite 代理 `/api` 访问。数据源为 `src/main/resources/fixtures/mes.json`（由脚本从 `src/data/mes.js` 导出），报警支持确认/恢复状态变更与 SSE 实时推送（含 45s 演示模拟器）。

```bash
# 数据源变更后重新导出 fixture
node apps/api-java/scripts/export-fixtures.mjs

# 构建并启动（Maven 位于仓库根 .tools/，首次需联网拉依赖）
.tools\apache-maven-3.9.9\bin\mvn.cmd -f apps/api-java/pom.xml package -DskipTests
java -jar apps/api-java/target/api-java-0.1.0.jar

# 前端默认走真实后端；置 VITE_USE_MOCK=true 回退本地示例数据
```

左侧导航与顶栏在 `src/components/layout.jsx`；全部示例数据集中在 `src/data/mes.js`，替换为真实数据只需改这一个文件。

## 设计令牌

主题采用「种子层 + 派生层」结构，定义在 `src/index.css` 的 `:root`：

- `--seed-bg / --seed-surface / --seed-fg`：背景、表面、前景
- `--seed-primary / --seed-accent`：交互主色、数据强调色
- `--seed-radius / --seed-density`：圆角、内容密度

其余颜色（边框、弱化文本、软底色等）均由种子令牌通过 `color-mix()` 派生，改动种子值即可整体换肤（浅色主题参考：`--seed-bg:#ffffff; --seed-surface:#fafafa; --seed-fg:#171717; --seed-primary:#0070f3; --seed-accent:#0e9f6e`）。
