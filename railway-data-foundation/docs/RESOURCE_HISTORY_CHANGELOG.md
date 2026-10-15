# 资源版本历史交付（2026-10-09）

资源详情新增“版本历史”，可查看资源登记、信息修改、字段维护和发布后的完整资源/字段快照，及相邻版本的属性变化。历史只查看，不恢复、应用扫描结构或改变共享权限。

## 使用

1. “资源目录 → 查看 → 版本历史”，查看已留存版本列表。
2. 选择某一版本，查看当时名称、描述、责任单位、安全级别、状态、来源及字段技术/业务属性。
3. “本次变化”显示与相邻已留存版本的原值、新值；字段快照可检索、分页。
4. 现有资源修改、字段维护和发布成功后自动留存新版本，刷新历史即可查看；版本冲突不产生快照。
5. 迁移前资源仅保留启用时的当前状态，标记为基线。更早版本未留存，没有上一版时变化未知。

## 实现位置

| 文件 | 作用 |
|---|---|
| `foundation-service/.../db/migration/V11__resource_version_history.sql` | 新历史表、中文注释及存量当前基线，不改旧迁移/版本/字段 |
| `resource/ResourceHistoryService.java`、`ResourceHistoryController.java` | 同事务快照写入、只读分页及相邻快照读取 |
| `resource/ResourceService.java` | 登记/信息修改/字段维护/发布事务内留存，沿用并发版本与父锁 |
| `ResourceHistoryIntegrationTest.java`、`MySqlBootstrapTest.java` | 真实MySQL的历史、回滚、并发、迁移JSON与V11校验 |
| `foundation-web/src/components/ResourceHistoryPanel.vue`、`views/resource/ResourceDetailView.vue` | 按需中文历史页签、快照/变化、检索分页和读取失败/旧响应处理 |
| `src/utils/resourceHistory.js`、`src/api/resource.js`、`src/utils/dataHub.js` | 响应归属/版本/模式校验、中文属性比较及真实API |
| `src/mock/resourceVersionData.js`、`src/mock/demoData.js` | 独立演示快照，与演示修改一次性保存，不伪造更早历史 |
| `scripts/resource-history.test.mjs`、`scripts/resource-history-live.mjs` | 规则/演示测试及真实浏览器历史验证 |
| `../scripts/cleanup-rest-ui-test.ps1` | 历史专项清单白名单，继续精确身份/引用保护清理 |

## 已执行验证

- 完整后端113项（Foundation90、Provider23），0失败、错误、跳过。新增6项真实MySQL测试：登记→资源修改→字段修改→发布、旧快照冻结与来源重命名、过期拒绝、强制快照保存失败整笔回滚、同版本并发只一个成功、分页/错误/只读、缺失旧版、草稿删除级联、真实V11回填SQL及JSON布尔/时间/字段顺序。
- 前端107项通过，`npm run build`成功，既有主包体积提示保留。
- 新版Foundation实际浏览器验证通过：真实四种操作、旧版名称与字段不变、中文操作/状态/变化、24个版本跨页、字段检索、列表/详情失败清空及重试、版本切换和退出页签的晚到响应隔离、演示基线及只读不改当前状态。截图`foundation-web/.runtime/history-real.png`已视觉检查。
- 原结构比较、真实资源/字段并发编辑、共享访问与自动摘要、演示流程回归通过。结构比较仍只读，Provider授权、追踪及分钟数量/字节保持正常。
- Foundation离线打包并重新启动，Provider保持已有产物。实际开发库迁移V11；迁移前1个现有资源的V2及字段前后逐项核对不变，仅留一个V2基线，没有V1假记录。独立MySQL集成测试还验证现有V7回填。
- 所有临时来源和历史按受保护清单清理，无孤立历史，原`container_event`仍20条业务模拟数据，两服务健康`code=0`。新表7列均有中文注释。

外层日志`.runtime/history-backend.log`、`history-package.log`、`history-start.log`，迁移前核对文件`history-adoption-before.json`；前端日志`.runtime/history-web-tests.log`、`history-build.log`、`history-browser.log`、`history-schema-regression.log`、`history-resource-regression.log`、`history-access-regression.log`、`history-smoke.log`。初轮测试发现夹具参数数量及旧V10表清单断言，修正后完整重跑通过；失败轮次不算通过。

复验：启动本机服务，项目根目录`./scripts/test-local.ps1 -Offline`；前端目录`npm test`、`npm run build`。加载`.env.local`后`npm run test:history-live`，结束在项目根目录`./scripts/cleanup-rest-ui-test.ps1 -ManifestName history-live-fixtures.json`清理登记。普通前端启动仍为`npm install`、`npm run dev`。

## 边界与后续

仅资源登记及字段快照历史，不包含能力或策略的联合版本。没有回退、恢复、结构应用、审批或可信操作人，不宣称永久审计；草稿删除同时清理其历史。已发布资源按原规则保留。后续再设计人工确认结构更新、能力/策略适配与版本恢复规则。没有扩展需求方或CDC，没有Git提交或推送。协议见[接口说明](RESOURCE_HISTORY_API.md)。
