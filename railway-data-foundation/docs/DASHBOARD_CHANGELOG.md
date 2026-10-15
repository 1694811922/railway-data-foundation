# 首页统计与展示修复（2026-10-03）

## 首页待处理事项补充（2026-10-04）

首页增加“待处理事项”，让管理员从统计概览直接找到需要人工处理的元数据记录。仅复用已有首页四类列表读取，不新增后端任务、审批、巡检、工作流或数据库表；V0.3继续暂停。

| 分类 | 判定口径 | 入口 |
|---|---|---|
| 待发布资源 | 资源状态为DRAFT | 对应资源详情，人工补全信息和字段后发布 |
| 待验证能力 | 能力状态为DRAFT | 所属资源的能力列表，查看/编辑并按当前支持范围主动验证 |
| 失败扫描 | 扫描任务状态为FAILED | 通过已有任务编号入口查看失败原因，修复来源后主动发起新扫描 |

每类支持名称、编号、关联来源/资源及处理提示的多关键词检索，10/20/50项分页，按最近记录时间倒序。表格显示区域最高420px，较多记录在表内滚动。筛选或分类变化重置页码；刷新后状态不再匹配的记录移出待处理列表。

相关接口失败清空该分类，数量显示“—”；其他已读取分类仍可查看。范围未完整时标记“≥”及范围说明，不将已读取记录当成全量。能力处理提示复用现有适配器规则；CDC等未接入类型不能被描述为可真实验证。缺少所属资源编号的能力不提供有效处理链接。能力入口只筛选所属资源，不自动打开指定能力或执行任何操作。

修改`foundation-web/src/views/dashboard/DashboardView.vue`，新增`src/utils/dashboardWork.js`、`scripts/dashboard-work.test.mjs`、`scripts/dashboard-work-live.mjs`；新增命令`test:dashboard-work-live`及更新README、路线图、进度和测试记录。

验证：**52项前端测试通过**、`npm run build`成功（保留原有主包体积提示）、`test:dashboard-work-live`、原`test:dashboard-live`、`test:smoke`通过。新专项先核对实际接口，后使用25草稿资源/24草稿能力/12失败扫描HTTP夹具验证检索分页排序、三种入口、独立故障/恢复、发布移除、5,000上限、模式拒绝和退出后的旧响应隔离，全部GET且未写数据库。本次没有修改Java，不重复Maven或真实写入测试。

截图已检查：`.runtime/dashboard-work-real.png`为实际接口读取（当前三类均为0，如实显示空态），`dashboard-work-items.png`与`dashboard-work-bounded.png`为HTTP夹具，不能当成开发库新增数据。复现：启动Foundation，在`foundation-web`运行`npm run test:dashboard-work-live`，临时端口5186，结束关闭浏览器和Vite。

## 此前首页统计交付

本轮属于现有演示前端完善，V0.3 继续暂停。未修改 Java、Provider、数据库结构或 Flyway 迁移，仍为 V6。

原首页仅统计列表首批记录：数据源最多 200 条，资源、任务和能力默认 50 条；失败时可能保留旧值或显示 0，“今日扫描”使用 UTC 日期。现在通过已有 `limit/offset` 列表接口分页读取，每页最多 200 条，每类最多统计 5,000 条。

| 内容 | 当前行为 |
|---|---|
| 六张统计卡 | 数据源登记数、已发布资源数、能力登记数、当前可用能力、今日扫描任务、异常能力 |
| 当前可用能力 | 已启用、最近验证正常、关联资源已发布、关联数据源已启用；不是实时探测或共享授权检查 |
| 今日扫描 | 后端 UTC 时间转换为北京时间后比较启动日期 |
| 接口失败 | 相关卡片显示“—”，不保留失败模块旧值；可用能力所依赖的模块失败时也显示“—” |
| 不完整读取 | 达到上限或分页出现重复编号时标注原因；显示“≥”，明确是数量下限 |
| 最近登记资源 | 按创建时间排序显示最近 5 条，避免把最近编辑当作最近登记 |
| 类型分布 | REST 接口、Kafka 消息主题、CDC 变化流、只读查询的中文名称 |
| 流程导航 | 接入步骤和统计卡可点击进入已有页面 |
| 演示模式 | 只有显式配置 `VITE_USE_MOCK=true` 时首页使用演示记录；真实统计失败不混入演示数据 |

统计只读取元数据，不发送 HEAD、不访问 Source 业务行。各列表分别读取，并非同一数据库事务快照，数据持续变化时应刷新确认。健康状态表示最近一次验证结果。大规模聚合接口、后台巡检及治理授权不属于本轮。

## 修改位置

- `foundation-web/src/utils/dashboard.js`：分页读取、错误隔离、指标口径、资源排序。
- `foundation-web/src/utils/time.js`：UTC 解析与北京时间日期计算。
- `foundation-web/src/utils/dataHub.js`：首页真实/演示数据入口。
- `foundation-web/src/api/` 下四个列表方法：接受分页参数，兼容原有默认调用。
- `foundation-web/src/views/dashboard/DashboardView.vue`：统计卡、状态提示、资源表、类型分布和流程导航。
- `foundation-web/scripts/dashboard.test.mjs`、`dashboard-live.mjs`：统计边界与浏览器专项。
- `foundation-web/package.json`：新增 `test:dashboard-live` 命令。

## 实际验证

- `npm test`：12 项通过，0 失败，其中新增 6 项首页统计测试。
- `npm run build`：成功；仍存在原有主包超过 500 kB 的提示，没有构建错误。
- `npm run test:smoke`：完整演示流程通过。
- `npm run test:dashboard-live`：核对首页六项数字与真实 Foundation API；用浏览器 HTTP 响应夹具验证 451 条资源/能力跨页、205 条任务、第二页失败不显示旧数、刷新恢复，以及 5,001 条来源触发上限提示。全程没有数据库写入。

浏览器专项首次因测试未经过演示入口超时；改为通过“进入平台”后完整专项通过。未修改页面路由保护。

已检查包含完整导航栏的真实首页及故障截图，生成于前端被 Git 忽略的 `.runtime/dashboard-real.png`、`dashboard-pagination.png`、`dashboard-failure.png`、`dashboard-bounded.png`。分页/故障/上限截图使用 HTTP 响应夹具，不是实际开发数据库记录；真实首页截图来自现有 API。当次开发库读取为 2 个数据源、1 个已发布资源、0 项已注册能力，页面如实显示，没有补造示例登记。

复现：先启动 Foundation，在 `foundation-web` 执行 `npm run test:dashboard-live`。专项在 5178 启动临时 Vite 服务，完成后关闭测试浏览器和服务。本轮没有 Java 或数据库修改，没有重复执行后端测试。
