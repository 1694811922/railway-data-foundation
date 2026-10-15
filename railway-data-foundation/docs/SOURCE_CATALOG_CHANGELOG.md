# 数据源检索与操作可靠性（2026-10-04）

## 数据源概览补充（2026-10-04）

新增数据源行“查看”入口，在抽屉内集中展示连接基本信息、最近扫描任务及关联资源。继续使用V0.1已有元数据接口，未修改Java、Provider、数据库或迁移；V0.3保持暂停。

| 内容 | 当前行为 |
|---|---|
| 基本信息 | 名称/编号/状态、数据库类型、配置方式、地址/端口/数据库/用户名、加密方式、更新时间和备注；白名单不包含密码、凭据引用、环境引用键 |
| 关联任务与资源 | 原有全局列表逐页读取，按source_id精确筛选；每类最多读取5,000条，关联数量不完整会显示下限和范围说明 |
| 最近记录 | 扫描按开始时间、资源按创建时间排序，各展示最近5项；登记时间不拿最近编辑时间替代 |
| 独立错误与刷新 | 两区分别加载/刷新/重试；失败清空该区，保留另一成功区域，不混入其他来源或演示记录 |
| 处理入口 | 查看指定扫描任务、对应资源详情、按来源筛选的资源目录；仅导航，不自动执行测试、扫描、发布或业务查询 |
| 上下文隔离 | 关闭抽屉、重开其他来源、刷新来源快照或退出时，旧关联响应不会覆盖新结果或重新打开抽屉 |
| 模式 | 来自真实列表的来源只读真实元数据；显式演示来源只读当前浏览器记录，并显示演示提示 |

读取上限适用于每类**全局**列表，不是该来源单独5,000条。相关记录位于读取范围外时可能未列出，因此部分数量只能作为下限。启用状态不代表连接正常，连接测试仍需要在列表手动执行。没有新增关联聚合接口或后台巡检。

新增`foundation-web/src/components/SourceOverviewDrawer.vue`、`src/utils/sourceOverview.js`、`scripts/source-overview.test.mjs`、`scripts/source-overview-live.mjs`；修改SourceView、dataHub、演示专项、package.json及README/接口/路线图/进度/测试说明。

验证：**57项前端测试通过**、`npm run build`成功（保留已有主包体积提示）、`test:source-overview-live`、原`test:source-catalog-live`、`test:smoke`通过。概览专项核对真实读取，再使用451条全局任务/资源、两来源HTTP夹具验证关联隔离、排序与最近5项、三个入口、独立故障/重试、模式拒绝、5,000上限及关闭/退出旧响应隔离；全部GET且未写数据库。显式演示专项新增概览提示和预设关联资源展示检查。测试定位/读取等待条件修正后通过；截图补充等待抽屉过渡结束，确保右侧未截断。

截图已检查：`.runtime/source-overview-real.png`为真实API读取；`source-overview-associated.png`与`source-overview-bounded.png`为HTTP夹具，不代表真实登记记录。复现：启动Foundation，在`foundation-web`运行`npm run test:source-overview-live`，临时端口5187，结束自动关闭。本次无Java修改，不重复Maven或真实写入测试，不需要清理数据库夹具。

## 此前数据源管理交付

本轮完善已有动态接入前端及测试。未修改Java、Provider业务代码、数据库结构或迁移；V0.3继续暂停。

## 使用方式与行为

进入“接入管理 → 数据源管理”，可按名称、编号、地址、端口、数据库名称、备注检索，组合类型与启停状态筛选。空格分隔的关键词需同时匹配；每页显示10/20/50项。“刷新列表”重新读取当前元数据并清除旧连接测试结果。

| 功能 | 当前行为 |
|---|---|
| 列表读取 | 调用已有 `/sources?limit=200&offset=...`，每次最多读取5,000项，超出范围或发现重复编号时明确提示 |
| 读取失败 | 清空旧列表和旧连接测试结果，提示刷新，阻止新增及编辑；默认模式不自动切为演示 |
| 新增与编辑 | 打开时记录真实/演示模式，编辑校验记录仍在当前读取列表且模式一致；新增目前只支持MySQL |
| 保存保护 | 提交期间禁止关闭弹窗、修改输入和重复提交；提交体仅含约定配置字段，不提交凭据引用 |
| 保存失败 | 保留表单输入，不写演示存储；断网或超时提示结果未确认，应刷新确认，不能自动重发 |
| 密码 | 编辑默认空密码保留后端凭据；失败后可留在当前弹窗内重试，取消、成功保存或离开页面时清空，不存localStorage |
| 保存并测试 | 先保存配置，再执行连接测试；已保存但连接测试失败会显示测试失败，不把保存误报为失败 |
| 连接结果 | 展示本次测试时间、产品、版本与耗时；连接失败或请求失败清除旧成功信息，给出安全原因。测试只保留在当前页面内存，刷新清除 |
| 启停与扫描 | 停用来源不能测试或扫描；操作期间阻止重复操作，失败不模拟成功，结果未确认提示检查实际状态 |
| 离开页面 | 旧列表/连接结果不再应用，晚到的扫描结果不自动导航回扫描页 |

数据源管理仅在 `VITE_USE_MOCK=true` 时使用明确标记的演示模式。演示保存、启停和连接测试检查浏览器中记录归属；密码仍不持久化。其他页面原有数据读取入口保留，本次严格模式用于数据源管理页。检索只涵盖已读取元数据，不读取业务明细，筛选条件暂不保存到网址。类型筛选可展示已有类型标识，不代表新增Oracle/PostgreSQL驱动。

## 文件

- `foundation-web/src/utils/sourceCatalog.js`：分页读取、组合过滤、表单参数与编辑范围校验。
- `foundation-web/src/views/source/SourceView.vue`：中文筛选、分页、读取错误、模式锁定、提交保护、连接错误卡片及页面退出保护。
- `foundation-web/src/utils/dataHub.js`：独立数据源目录入口及演示操作归属校验。
- `foundation-web/scripts/source-catalog.test.mjs`、`source-catalog-live.mjs`：单元测试与浏览器专项。
- `foundation-web/scripts/source-live.mjs`：适配连接失败卡片，登记来源后立即持久保存清理身份。
- `foundation-web/package.json`及相关README、接口/进度/测试说明。

## 实际验证

- `npm test`：**28项通过**，新增4项覆盖跨页/严格读取失败、组合筛选、动态配置校验与空密码编辑、模式和编辑范围。
- `npm run build`：成功；保留已有主包超过500kB提示。
- `npm run test:source-catalog-live`：核对真实API数量，再用HTTP响应夹具验证451条列表、跨页/组合筛选、分页、停用禁扫/禁测试、测试成功→连接失败→请求失败且清除旧成功信息、重复测试/保存拦截、空密码编辑不提交password、提交体不含credential_ref、失败保留输入和浏览器存储不变、取消清空密码、真实启停/扫描失败不mock、首/次页读取失败及恢复、离开页面后扫描响应不自动导航。通过的专项运行全部夹具写入被拦截，没有数据库写入。
- `npm run test:smoke`：完整演示流程通过。
- `npm run test:source-live`：真实登记→保存并测试→空密码编辑→启停→两库扫描通过；`port_source_test.container_event`12字段与`another_source_test.train_dispatch`3字段隔离，密码不回显、不保存到浏览器。

首次专项因本地后端未运行失败，使用已有启动脚本恢复服务；随后HTTP拦截未覆盖`/sources/{id}/test`子路径，访问不存在的测试编号导致验证失败，修正为覆盖API及子路径后通过，未修改后端以迎合测试。最终构建使用更新后的页面退出保护代码；相关浏览器专项同时验证晚到扫描响应。

真实回归的2组临时来源/凭据/扫描元数据已按校验清单清理；最终原有来源2条、临时来源0条、Source业务行20条、V6，Foundation无对应业务明细表。后端未变更，未重复Maven测试或重新打包。启动脚本仅恢复本机服务并验证已有迁移。

已检查完整导航下的筛选与失败卡片截图，位于前端被忽略的 `.runtime/source-catalog-real.png`、`source-catalog-filtered.png`、`source-catalog-search.png`、`source-catalog-connection-failure.png`、`source-catalog-list-failure.png`。451条记录只来自浏览器HTTP测试夹具，不是新增真实数据源。真实回归截图为 `.runtime/source-dialog.png`、`source-list.png`、`source-fields.png`、`source-scans.png`。

复现：启动Foundation后，在 `foundation-web` 执行 `npm run test:source-catalog-live`，临时端口5182，结束自动关闭。真实写入专项继续使用 `test:source-live` 和 `../scripts/cleanup-source-ui-test.ps1`，按前端README配置本机独立测试来源，不用于生产库。
