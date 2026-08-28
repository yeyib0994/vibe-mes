# {{MODULE_TITLE}} · 功能规范（spec）

- **模块**：{{MODULE_SLUG}}
- **对应页面**：{{PAGE_PATH}}
- **依赖**：`specs/constitution.md`
- **版本**：{{VERSION}} · {{DATE}}
- **状态**：{{STATUS}}

## 1. 概述（Overview）

<!-- 一段话：模块定位、面向角色、在一屏/一流程内解决什么判断或操作问题。 -->

## 2. 用户场景（User Scenarios）

<!-- US1..USn，每个场景给角色 + 触发动作 + 期望结果。 -->

- **US1 · {{场景名}}**：{{角色}}在{{情境}}下{{动作}}，期望{{结果}}。

## 3. 功能需求（Functional Requirements，EARS）

<!-- 严格 EARS：`[WHEN <触发>] [IF <条件>] THE SYSTEM SHALL <行为>`。
     工业级缺口条目在句首标注（工业级）。已原型实现的条目标注 ✅。 -->

- **FR-1** WHEN {{触发}}，THE SYSTEM SHALL {{行为}}。
- **FR-2**（工业级）WHEN {{触发}}，THE SYSTEM SHALL {{行为}}。

## 4. 非功能需求（Non-Functional Requirements）

<!-- NFR-1..，性能/实时/主题/可访问性/降级等。 -->

- **NFR-1** {{非功能要求}}。

## 5. 边界与约束（Boundaries & Constraints）

<!-- 只读/只展示、计算口径归属、外部系统边界、原型数据来源说明。 -->

## 6. 成功标准（Acceptance Criteria）

<!-- 可勾选清单，每条对应一个可验收行为。 -->

- [ ] {{验收项}}

## 7. 现状对照（Prototype vs Industrial Gap）

| 能力 | 原型现状 | 工业级缺口 |
| --- | --- | --- |
| {{能力}} | ✅ 已实现 / ❌ 缺失 | {{缺口描述}} |
