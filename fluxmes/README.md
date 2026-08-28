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

左侧导航与顶栏在 `src/components/layout.jsx`；全部示例数据集中在 `src/data/mes.js`，替换为真实数据只需改这一个文件。

## 设计令牌

主题采用「种子层 + 派生层」结构，定义在 `src/index.css` 的 `:root`：

- `--seed-bg / --seed-surface / --seed-fg`：背景、表面、前景
- `--seed-primary / --seed-accent`：交互主色、数据强调色
- `--seed-radius / --seed-density`：圆角、内容密度

其余颜色（边框、弱化文本、软底色等）均由种子令牌通过 `color-mix()` 派生，改动种子值即可整体换肤（浅色主题参考：`--seed-bg:#ffffff; --seed-surface:#fafafa; --seed-fg:#171717; --seed-primary:#0070f3; --seed-accent:#0e9f6e`）。
