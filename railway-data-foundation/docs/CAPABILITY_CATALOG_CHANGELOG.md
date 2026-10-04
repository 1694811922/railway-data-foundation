# 接口能力检索与操作可靠性（2026-10-03）

本轮完善已有V0.2能力管理前端，未修改Java、Provider、数据库结构或迁移；V0.3继续暂停。

## 页面行为

| 功能 | 当前实现 |
|---|---|
| 列表与资源选项 | 使用已有 `/capabilities`、`/resources` 分页接口，每页读取200项，避免只显示前50条 |
| 读取范围 | 每类最多5,000项元数据；超过上限或发现重复编号时提示范围不完整 |
| 组合检索 | 关键词、关联资源、能力类型、能力状态、健康状态同时筛选；关键词支持名称、编号、资源、接口路径、主题和模板代码 |
| 显示分页 | 每页10/20/50项，条件改变回到第一页；显示已读取与匹配数量 |
| 注册与编辑 | 仅选择当前模式下的已发布资源；资源读取失败时阻止注册和编辑；草稿能力可编辑 |
| 读取失败 | 能力和资源分别显示错误，失败区域清空旧记录；能力列表失败不借用演示能力，资源失败不借用演示资源 |
| 操作保留 | 真实提交失败不模拟成功、不自动重发；弹窗保留输入，网络中断或超时提示刷新检查实际结果 |
| 提交保护 | 保存期间不能取消或关闭弹窗，函数层阻止重复提交；表单记录打开时的数据来源 |
| 调用说明 | 关闭、重开或刷新说明后，旧请求不能覆盖新说明，包括同一能力编号的请求 |

能力管理只有两种明确模式：默认读取真实接口；配置 `VITE_USE_MOCK=true`，或从当前浏览器中已存在的演示资源进入能力页时，统一读取演示能力与演示资源。后端不可达不会自动让能力管理切成演示。演示能力只允许关联演示已发布资源，演示编辑也校验草稿状态和同一资源下名称重复。

真实REST支持仍限于现有无认证 `GET /containers/events` 的验证和启停。Kafka只支持已有主题元数据验证；CDC和只读查询仅登记，不代表运行时已实现。健康状态是最近一次验证结果，未新增后台巡检。检索只读取元数据，不查询或复制Source业务明细。当前检索条件不会像资源目录一样保存到网址，关联资源入口仍支持原有 `?resource=...&create=1`。

## 文件

- `foundation-web/src/utils/capabilityCatalog.js`：两类列表分页读取、错误隔离、组合过滤、保存范围校验。
- `foundation-web/src/views/capability/CapabilityView.vue`：中文筛选、分页、加载错误、注册范围、提交保护及调用说明请求隔离。
- `foundation-web/src/utils/dataHub.js`、`src/mock/demoData.js`：能力管理模式统一，演示编号归属和模拟写入校验。
- `foundation-web/scripts/capability-catalog.test.mjs`、`capability-catalog-live.mjs`：单元测试与浏览器专项。
- `foundation-web/scripts/capability-live.mjs`：兼容更明确的操作结果未确认提示。
- `foundation-web/package.json`：新增 `test:capability-catalog-live` 命令；相关说明和进度文档同步更新。

## 实际验证

- `npm test`：**20项通过**；新增4项覆盖两类列表跨页读取、失败不混入演示、组合筛选和保存资源范围。
- `npm run build`：成功；保留已有主包超过500kB的提示。
- `npm run test:capability-catalog-live`：核对真实API数量，再用浏览器HTTP响应夹具验证451条能力和451条资源、跨页检索、组合筛选、分页、第451项已发布资源可选、草稿资源不可选、同编号旧调用说明隔离、重复保存拦截、保存失败保留输入和演示存储不变、第二页读取故障及恢复。夹具写入全部被拦截，不写数据库。
- `npm run test:smoke`：完整演示流程通过。
- `npm run test:capability-live`：真实动态登记来源→扫描→发布资源→注册能力→验证→启用→Provider查询200→停用403→再启用200；真实调用地址、请求头、复制命令、调用说明失败/重试和来源停用拒绝均通过。

真实专项的1组临时元数据已按固定清单和编号/名称/凭据引用校验清理。原有数据源2条、临时来源0条、Source业务行20条、迁移V6；Foundation无对应业务明细表。Java未修改，本轮未重复后端Maven测试。

已检查带完整导航栏的能力列表及注册弹窗截图，位于前端被忽略的 `.runtime/capability-catalog-real.png`、`capability-catalog-filtered.png`、`capability-catalog-registration.png`、`capability-catalog-failure.png`。451条数据仅为浏览器HTTP测试夹具，不是新增数据库记录。真实REST截图为 `.runtime/rest-capability-enabled.png`、`rest-capability-detail.png`、`rest-capability-disabled.png`。

复现：启动Foundation，在 `foundation-web` 执行 `npm run test:capability-catalog-live`；使用临时端口5180，结束自动关闭。真实写入专项先启动Foundation和Provider、导入本机环境配置，再执行 `npm run test:capability-live`，最后运行 `../scripts/cleanup-rest-ui-test.ps1`，只能使用本机模拟来源。
