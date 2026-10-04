# 元数据扫描工作台完善（2026-10-04）

本轮仅完善已有V0.1扫描前端、测试和文档。未修改Java、Provider、数据库结构或Flyway迁移；V0.3继续暂停。

## 使用方式

进入“接入管理 → 元数据扫描”，选择已启用的数据源并开始扫描。左侧按任务编号、来源名称、失败原因检索，组合来源和任务状态筛选；右侧展示所选任务的表结构，支持表名、数据库模式、表说明检索。查看字段后可按字段名、类型和注释检索，再从成功扫描结果创建资源。

| 项目 | 当前行为 |
|---|---|
| 任务与数据源列表 | 使用已有limit/offset接口逐页读取，每类最多5,000项，避免遗漏默认前50/200条；范围不完整会提示 |
| 显示分页 | 任务每页10/20/50项；筛选后回到第一页；任务区域独立滚动 |
| 所选任务刷新 | “刷新任务”重新读取任务状态并重新加载表结构；失败/未完成任务清除旧表结果 |
| 指定任务 | 支持原有 `?task=...`；同一页面路由变化会更新所选任务。指定任务不在列表范围内时使用已有任务详情接口单独读取并标注，不偷换成第一条 |
| 表与字段失败 | 对应区域显示错误、清除旧结果，可分别重新加载；字段失败时不能从抽屉创建资源 |
| 请求隔离 | 切换任务、重新加载、关闭或重开字段抽屉后，旧表/字段响应不能覆盖新结果或重新打开抽屉 |
| 发起扫描 | 只使用当前模式下已启用且成功读取的数据源，提交期间阻止重复点击；网络中断或超时提示刷新任务确认，不自动重发 |
| 模式一致 | 默认任务和数据源统一读取真实API，读取失败不自动混入演示；全局显式mock或当前浏览器中已有演示任务入口统一使用演示模式 |

任务筛选不会切换右侧已选任务，右侧始终显示任务编号和状态。筛选条件及显示页码暂不保存到网址；指定任务入口保留。扫描仍是现有同步接口，不新增排队、轮询、取消任务或异步后端；PENDING仅展示已有预留状态。所有检索只涉及结构元数据，估算记录数不是实际共享量。

## 文件

- `foundation-web/src/utils/scanCatalog.js`：分页读取、错误区域隔离、任务/表/字段过滤、扫描来源校验。
- `foundation-web/src/views/metadata/MetadataView.vue`：中文扫描工作台、刷新、路由更新、重试、提交保护及请求隔离。
- `foundation-web/src/utils/dataHub.js`、`src/mock/demoData.js`：统一扫描模式、指定任务读取、演示任务归属判断。
- `foundation-web/scripts/scan-catalog.test.mjs`、`scan-catalog-live.mjs`：单元测试与浏览器专项。
- `foundation-web/scripts/resource-live.mjs`：适配立即显示加载状态的字段抽屉；创建临时来源后立即保存清理清单。
- `scripts/cleanup-rest-ui-test.ps1`：修复Windows PowerShell 5.1把多项JSON清单嵌套成单项数组、误报清理成功的问题；逐项验证编号和引用必须为字符串。
- `foundation-web/package.json`及相关README、接口/进度/测试说明。

## 验证结果

- `npm test`：**24项通过**；新增4项覆盖任务/数据源跨页、错误区域隔离、组合检索和来源启停/模式校验。
- `npm run build`：成功；保留已有主包超过500kB的提示。
- `npm run test:scan-catalog-live`：核对真实任务数量，并用HTTP响应夹具验证451条任务和451条来源、跨页/组合检索、表和字段检索、所选任务状态刷新、旧表和字段响应隔离、字段关闭重开、失败重试、指定任务详情及同页面路由变化、错误编号不替换为第一条、来源停用/读取失败禁扫、重复扫描请求拦截、故障恢复。所有夹具写入被拦截，不写数据库。
- `npm run test:smoke`：完整演示流程通过。
- `npm run test:resource-live`：真实扫描→创建/维护资源与字段→发布→登记能力通过；失败不mock、已提交但响应丢失不自动重发和字段切换隔离继续通过。
- `npm run test:source-live`：真实两库扫描通过，`port_source_test.container_event`的12字段与`another_source_test.train_dispatch`的3字段隔离，原有登记/连接测试/空密码编辑/启停回归通过。

专项脚本首次错误地将原生fetch的`ok`属性当成函数，修正后通过；与真实写入专项同时运行曾引起前后读取数量变化，改为串行复验。原资源专项因字段抽屉立即打开而阻挡切换，改为先关闭加载中的抽屉再切换后通过，仍检查晚到响应不能重新打开抽屉。一次中断进程未执行finally留下临时来源，按明确编号、凭据引用与测试名称恢复清单并清理；脚本现已提前持久化清理身份。

清理复核发现Windows PowerShell 5.1对多项JSON数组的嵌套包装问题：原脚本将两个清单项当成一个数组处理，未删除记录却误报成功。已修复解析及单项类型校验，并实际验证双项清单清理。最终数据库复核：原有数据源2条、临时来源0条、Source业务行20条、迁移V6，Foundation无对应业务明细表；保留原有资源及Source业务表。Java未修改，不重复后端Maven测试。

截图已检查：前端被忽略的 `.runtime/scan-catalog-real.png`、`scan-catalog-workspace.png`、`scan-catalog-fields.png`、`scan-catalog-failure.png`。451条及字段检索截图来自HTTP测试夹具，不是数据库新增数据。

复现：启动Foundation后，在 `foundation-web` 执行 `npm run test:scan-catalog-live`，使用临时端口5181并自动关闭。真实资源专项导入本机环境配置后执行 `npm run test:resource-live`，清理命令为 `../scripts/cleanup-rest-ui-test.ps1 -ManifestName resource-live-fixtures.json`；真实两库专项及清理按前端README执行。只能用于本机模拟/测试来源。
