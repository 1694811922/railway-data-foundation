# 资源目录检索与分页（2026-10-03）

本轮完善现有资源目录，未修改后端接口、Provider、数据库结构或迁移，V0.3继续暂停。

## 使用方式

进入“数据资源 → 资源目录”，填写关键词，按需选择来源系统、资源状态和安全等级，点击“查询”。空格分隔的关键词需要同时匹配；支持资源名称、编号、物理表、来源名称和责任单位，不检索业务明细。“重置”清除条件并回到第一页。

| 功能 | 当前行为 |
|---|---|
| 列表读取 | 使用现有 `/resources?limit=200&offset=...`，避免遗漏默认前50条之外的资源 |
| 检索范围 | 前端最多读取5,000项元数据，在已读取记录中检索；超出上限或检测到分页重复编号时明确标注范围不完整 |
| 条件组合 | 关键词、来源、草稿/已发布状态、安全等级共同筛选 |
| 显示分页 | 每页10/20/50项；筛选或调整每页条数时回到第一页，超出实际页数会修正 |
| 条件保留 | 条件和页码保存到目录网址；刷新、浏览器返回以及详情“返回资源目录”保留条件 |
| 空结果 | 区分尚无资源和没有符合条件的资源 |
| 读取失败 | 分页中途失败不展示部分成功结果，不切成演示；已展示真实目录后刷新失败清除旧记录并提示重试 |
| 初次演示回退 | 仅在尚未得到任何真实页响应、页面从未展示真实目录且错误符合原有回退条件时可展示明确标记的演示目录；全局显式mock配置仍受支持 |
| 来源显示 | 直接采用资源列表关联返回的来源名称，不因单独数据源列表读取失败而阻断整个目录 |

此项是实验规模下的前端元数据检索，不是后台全文搜索。列表分页读取不是同一事务快照，变动时可刷新确认。不会查询、导出或复制Source业务行。

## 修改文件

- `foundation-web/src/utils/resourceCatalog.js`：检索、分页、网址条件校验、读取与演示回退边界；复用上一轮已有的有界分页读取方法。
- `foundation-web/src/utils/dataHub.js`：新增独立的目录读取入口；其他页面的资源列表方法保留。
- `foundation-web/src/views/resource/ResourceListView.vue`：中文筛选表单、结果数量、范围提示、分页、详情入口、读取错误与恢复。
- `foundation-web/src/views/resource/ResourceDetailView.vue`：两处“返回资源目录”保留合法目录条件，不接收任意外部返回地址。
- `foundation-web/scripts/resource-catalog.test.mjs`、`resource-catalog-live.mjs`：单元测试和浏览器专项。
- `foundation-web/package.json`：新增 `test:resource-catalog-live` 命令。
- README、ROADMAP、IMPLEMENTATION_STATUS、TESTING：同步说明与实际验证结果。

## 实际验证

- `npm test`：**16项通过**，新增4项覆盖多字段/组合检索、非法网址参数、筛选后的页码修正以及回退边界。
- `npm run build`：成功；仅保留已有主包超过500kB提示。
- `npm run test:resource-catalog-live`：先核对真实API读取数量，再用HTTP响应夹具测试451条跨页、检索第451条、组合筛选76条、10/20条分页、网址刷新/详情返回、无匹配、第一页/第二页失败、恢复和5,000条上限。该专项没有数据库写入。
- `npm run test:smoke`：完整演示闭环通过。
- `npm run test:resource-live`：真实扫描→创建资源→维护字段→发布→登记能力通过；来源只读、晚到请求隔离、请求失败不mock、已提交但响应丢失不自动重发继续通过。

目录专项首次因测试HTTP拦截模式未覆盖详情子路径而超时，改为匹配资源API及其子路径后通过。随后补充“返回资源目录”按钮条件保留检查并再次通过。未修改后端以迎合测试。

真实资源流程创建的1组临时元数据已按编号/名称/凭据引用清单清理；原有数据源2条、临时来源0条、Source业务行20条、迁移V6，Foundation没有container_event/train_dispatch业务表。读取已有服务日志未发现所检查的本地密码和主密钥。Java未变更，本轮没有重复执行后端Maven测试。

已检查完整导航栏下的目录筛选截图。截图位于前端被忽略的 `.runtime/resource-catalog-real.png`、`resource-catalog-search.png`、`resource-catalog-filtered.png`、`resource-catalog-failure.png`。带451条数据的截图来自浏览器HTTP测试夹具，不是实际数据库新增数据。

复现：启动Foundation后，在 `foundation-web` 执行 `npm run test:resource-catalog-live`。使用临时端口5179，结束自动关闭测试服务和浏览器。真实资源写入专项仍按原 `test:resource-live` 与清理清单步骤执行。
