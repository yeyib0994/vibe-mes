# .specify — Spec Kit 脚手架（部分实现）

本目录是 FluxMES 项目中 **Spec Kit 框架层** 的脚手架。它与 `specs/`（产出物）的关系：

- `specs/`：实际写好的 `constitution.md` / 各模块 `spec.md` `plan.md` `tasks.md`（交付文档）。
- `.specify/`：规范自身——模板、约定、配置。生成 `specs/` 下文档时以本目录的模板为准。

## 本轮已建（核心部分）

| 路径 | 作用 |
| --- | --- |
| `templates/constitution_template.md` | 项目章程模板（全局根约束） |
| `templates/spec_template.md` | 功能规范模板（EARS 需求 + 用户场景 + NFR + 边界 + 验收 + 现状对照） |
| `templates/plan_template.md` | 技术计划模板（架构 + 数据契约 + 关键决策 + 集成 + 合规 + 风险） |
| `templates/tasks_template.md` | 任务清单模板（P0/P1/P2 + 验收 + 进度） |
| `conventions.md` | 项目约定（EARS 写法、优先级图例、命名、合规引用） |
| `settings.json` | 框架配置（目录、必含章节、优先级、分支命名） |

## 暂未实现（后续可补）

- `scripts/`：自动生成 spec/plan/tasks 的脚本（如官方 `speckit` CLI 的命令实现）。当前文档为手工编写，模板仅作格式依据。
- `memory/`：跨会话项目记忆（长期决策、踩坑记录）。可用 `specs/` 文档与 `conventions.md` 替代。

## 使用约定

新建模块时：复制对应 `templates/*.md` 到 `specs/<module-slug>/`，替换 `{{...}}` 占位符并删除指导性注释，保留章节顺序以满足 `settings.json` 的 `requiredSections`。
