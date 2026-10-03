# 资源登记、字段维护与发布流程修复记录

日期：2026-10-03。完善V0.1/V0.2已有前端流程，解决真实API请求失败被模拟成功覆盖、字段跨资源混用及扫描抽屉响应串用的问题。V0.3保持暂停。

## 行为变化

| 场景 | 原行为 | 修复后 |
|---|---|---|
| 真实保存/发布/字段编辑失败 | 网络错误可能自动写入mock并提示成功 | 提示失败或结果尚未确认，保留输入，不修改演示存储，不自动重发 |
| 资源详情字段/能力读取失败 | 可能展示通用演示字段或能力 | 真实资源按区域显示错误，可刷新重试；404显示资源不存在 |
| 编辑资源来源信息 | 表单可更改，后端再拒绝 | 来源系统、数据库模式、物理表只读，业务属性可编辑 |
| 资源详情发布 | 需要返回目录操作 | 详情直接发布并显示编号、状态和最新版本；草稿不能登记能力 |
| 演示字段编辑 | 修改全局fields，影响其他资源/扫描 | 按resource_id分别保存，更新对应演示资源版本 |
| 扫描字段请求晚到 | 可用旧字段覆盖新任务并打开抽屉 | 切换任务清空旧字段、关闭抽屉，旧响应丢弃；创建绑定对应任务 |
| 目录创建入口 | 错误跳转到/metadata | 正确进入/scans |

资源业务信息和字段属性沿用现有真实API，技术元数据只读；未增加新后端API、配置项、依赖或数据库迁移。业务行仍留在Source，管理页只展示元数据。列表在后端不可用时仍可显示有标记的演示目录；指定真实资源/扫描任务的读取不回退通用演示结果。演示操作仅适用于浏览器已有的演示来源/资源或全局显式演示模式。

## 文件清单

- 修改`foundation-web/src/views/resource/ResourceListView.vue`、`ResourceDetailView.vue`和`views/metadata/MetadataView.vue`。
- 新建`foundation-web/src/utils/resourceForm.js`；修改`utils/dataHub.js`和`mock/demoData.js`。
- 新建`scripts/resource-demo.test.mjs`、`scripts/resource-live.mjs`；修改`scripts/smoke.mjs`和`package.json`。
- 扩展项目`scripts/cleanup-rest-ui-test.ps1`，清单名仅允许rest-live-fixtures.json与resource-live-fixtures.json，原身份和名称校验保留。
- 更新AGENTS、项目/前端README、SCAN_RESOURCE_API、ROADMAP、IMPLEMENTATION_STATUS、TESTING及本记录。
- 本地`.runtime/resource-live.ps1`、`source-regression.ps1`及日志为验证辅助，不参与应用运行。

## 验证与复现

`npm test`6项通过、`npm run build`成功、`test:smoke`通过。`test:resource-live`对真实后端验证来源只读、晚到响应、错误无mock、响应丢失不重发、字段/资源持久化、版本变化、发布后登记能力。`test:source-live`对两个独立测试库验证12/3字段隔离，原登记、测试、启停保持正常。截图已经人工检查。

先启动已有Foundation和Provider，在前端目录执行：

```powershell
. ../scripts/load-env.ps1 -Path ../.env.local
npm run test:resource-live
powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-rest-ui-test.ps1 -ManifestName resource-live-fixtures.json
```

专项测试限定本机port_source_db模拟库，并生成UI_REST_临时来源、资源、能力及不含密码的清单。测试服务器5177，结束关闭。不能使用生产环境；清理只删除清单中经编号、名称和凭据引用校验的临时元数据。

本轮未修改Java业务代码，因此不重复构建后端或运行原45项回归；实际浏览器使用已经运行的既有API。所有临时元数据已清理，原有Source与20条模拟业务行保留，数据库仍为V6。

## 已知限制

扫描仍同步执行，列表仍沿用现有分页上限；本轮未新增任务队列或资源版本历史。网络失败只代表结果未确认，服务端可能已经提交，需刷新后决定是否再次操作。此修复不引入V0.3身份认证、共享授权或策略管理。
