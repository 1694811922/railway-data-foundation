# 资源结构只读比较交付（2026-10-07）

资源详情新增“结构变化”页签，固定比较登记扫描与人工选择的后续成功扫描。可以查看新增、删除、修改及未变化数量，展开类型、主键、可空、默认值、注释和顺序的原值/新值；表注释变化单独展示。比较不会修改资源字段、业务属性、版本、能力或共享策略。

## 使用

1. 数据源管理中登记并成功扫描来源，从该扫描创建资源。
2. 后续重新扫描同一来源，完成后进入“资源目录 → 查看 → 结构变化”。
3. 选择对比任务，点击“开始比较”。基准始终为登记时的扫描。
4. 使用关键词、变化类型、分页和展开箭头查看差异。
5. 没有候选时进入扫描工作台人工扫描；读取失败可刷新候选或重试比较。

字段改名显示删除和新增。目标表未发现或空扫描无法确认模式时，统计保持未知，不报告全部字段删除。具体接口、返回属性及错误见[接口说明](SCHEMA_COMPARISON_API.md)。

## 主要文件

| 文件 | 作用 |
|---|---|
| `foundation-service/.../resource/SchemaComparisonController.java` | 两个只读接口及分页/任务参数验证 |
| `SchemaComparisonService.java` | 同一可重复读事务读取快照、完整性/范围核对、精确差异比较 |
| `foundation-service/.../SchemaComparisonIntegrationTest.java` | 独立MySQL测试库的真实扫描、临时表修改、只读和并发删除验证 |
| `foundation-web/src/components/SchemaComparisonPanel.vue` | 候选任务、中文统计/差异、展开、筛选分页、重试和旧响应隔离 |
| `src/views/resource/ResourceDetailView.vue` | 按需打开比较页签，资源/版本/模式变化时重建组件 |
| `src/api/resource.js`、`src/utils/dataHub.js` | Axios调用及真实/演示模式隔离 |
| `src/utils/schemaComparison.js` | 响应范围/完整性校验、中文字段属性、筛选及值显示 |
| `src/mock/schemaComparisonData.js` | 独立两份演示技术快照及相同协议响应 |
| `scripts/schema-comparison.test.mjs` | 响应校验、筛选分页、未知统计、演示只读与旧资源验证 |
| `scripts/schema-comparison-live.mjs` | 真实临时表及浏览器专项；分页/错误/旧响应使用只读HTTP夹具 |
| `../scripts/cleanup-rest-ui-test.ps1` | 新增比较专项清单名，继续以精确来源编号/凭据及名称保护清理 |

## 验证结果

- 完整后端107项（Foundation84、Provider23），0失败、错误、跳过。新6项比较集成测试覆盖无变化、真实增删/改名、类型/完整列类型/主键/可空/默认值/注释/顺序、仅表注释变化、估算行数忽略、任务错配/失败/较早/不存在、模式错配、字段计数/顺序残缺、目标表缺失、范围不明、并发删除。
- 真实临时表扫描比较前后，资源/字段业务属性、资源版本、能力、共享关系与策略逐项不变。删除在另一HTTP事务完成后，已建立的只读快照仍能返回完整差异；新请求404。
- 前端101项测试通过，`npm run build`成功。既有主包体积提示保留，不是构建错误。
- 新版服务下真实浏览器专项通过：临时表实际结构修改，新增1、删除1、修改3；未变化扫描、中文过滤、展开、10/20/50项分页、20项候选分页、真实失败清空/重试、切换任务/页签/资源的晚到响应隔离、未知统计、显式演示与旧演示无基准。
- 原`test:resource-live`、`test:access-live`、`test:smoke`通过；核对资源并发编辑与原登记/发布/能力流程，真实授权允许/拒绝/撤销及自动摘要和分钟计量。
- Foundation重新打包启动，Provider保持既有代码与产物。开发库仍V10，没有新增迁移。临时业务表和随机登记已受保护清理，原`port_source_db.container_event`结构未改，仍20条模拟业务行，两服务健康接口`code=0`。

测试日志：外层`.runtime/schema-backend-final.log`、`schema-package.log`、`schema-start.log`；前端`.runtime/schema-web-test.log`、`schema-build.log`、`schema-browser.log`、`schema-resource-regression.log`、`schema-access-regression.log`、`schema-smoke.log`。实际比较截图`foundation-web/.runtime/schema-comparison-real.png`已视觉检查。

复验时先在项目根目录运行`./scripts/test-local.ps1 -Offline`（需要本机MySQL及已配置Maven），再于`foundation-web`运行`npm test`、`npm run build`。真实专项先启动最新版Foundation并加载本机`.env.local`到进程，执行`npm run test:schema-comparison-live`；专项临时表自动清理，登记数据用`./scripts/cleanup-rest-ui-test.ps1 -ManifestName schema-comparison-live-fixtures.json`从项目根目录按清单清理。后端测试和比较浏览器专项使用同一独立测试库，应依次运行。普通使用仍为`npm install`、`npm run dev`。

联调修正了演示“未变化”计数与基准字段数不一致的问题，完整性检查复用现有表的字段数量，不假设任务表存在字段数量列。共用独立测试库的后端测试与比较浏览器夹具按先后执行，避免临时表影响已有固定数量断言。最终完整重跑通过；失败记录不计为验收。

## 后续范围

只读结构比较已完成；人工确认结构更新、资源版本历史、能力适配与策略重新确认仍未实现。未开发后台扫描、需求方服务或CDC。比较不直连源库，不宣称结构或访问能力已经自动同步；无Git提交或推送。
