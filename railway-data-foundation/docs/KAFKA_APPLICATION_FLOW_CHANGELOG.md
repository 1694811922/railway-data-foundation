# Kafka 订阅申请链路完善

日期：2026-10-12。阶段：V0.5 完善与联调。

## 本轮结果

保留统一前端和既有审批、Broker 权限执行机制，完善“资源目录 → 订阅申请”操作链。没有新增后端业务模块、数据库迁移、需求方业务服务或 CDC，未提交或推送 Git。

| 改动 | 行为 |
|---|---|
| 选项联动 | 先选提供方，再选对应已发布资源及该资源下的 SASL 精确 Kafka 主题能力 |
| 清理旧选择 | 更换提供方清空资源和能力，更换资源清空能力，重新确认整主题范围 |
| 需求方身份 | 需求方主体固定为登录账号关联主体，不允许选择其他需求方 |
| 目录申请入口 | 校验精确资源和能力编号；失效、无归属或错配链接报错，不选择替代资源 |
| 失败与重试 | 登记选项失败明确报错、禁用新增并提供重试；真实失败不转演示成功 |
| 响应隔离 | 同一页面切换申请入口后，晚到的读取及操作响应不能覆盖新选择或凭据展示 |
| 三角色页面 | 管理员“Kafka 订阅授权”，提供方“Kafka 订阅记录”，需求方“我的 Kafka 订阅” |
| 中文展示 | 能力类型中文化；需求方入口为“申请订阅”，无 SASL 的主题不可从目录申请 |

前端检查不替代服务端鉴权。源→资源→能力继承归属方式不变；管理员对无归属存量的既有管理规则保留，不修改归属，普通提供方或需求方不能接管。

## 主要文件

| 文件 | 作用 |
|---|---|
| foundation-web/src/utils/subscriptionCatalog.js | 选项归属筛选、身份及绑定校验、目录入口解析；拒绝混入演示数据 |
| foundation-web/src/views/sharing/SubscriptionView.vue | 表单联动、重试入口、角色标题及入口切换响应隔离 |
| foundation-web/src/views/resource/PublicResourceDetail.vue | 中文能力展示及有效 Kafka 申请入口 |
| foundation-web/src/components/StatusTag.vue | 公共能力类型中文标签 |
| foundation-web/scripts/subscription-catalog.test.mjs | 新增 5 项归属、身份、主题及入口测试 |
| foundation-web/scripts/subscription-demo-live.mjs、package.json | npm run test:subscription-demo 验证显式演示不请求真实 API，不办理申请、权限或凭据 |
| foundation-web/scripts/role-workspaces-live.mjs | 真实三角色浏览器流程、失败重试、响应隔离及截图 |
| foundation-service/src/test/java/cn/railway/foundation/WorkspaceRoleIntegrationTest.java | 独立测试库两个提供方和不同认证能力夹具、浏览器验收及清理 |

## 当前工作台与演示顺序

本轮以 `provider · 数据提供方 · 国铁` 的真实登录会话只读核对：已有“广州港模拟业务库”和一次扫描；公开资源 `RES_aa39643ff6164a548deaeda2973b4860` 已发布，物理表 `container_event`、读取时版本 3；Kafka 能力 `CAP_d062236acb234d0a98fb4ba2e7b5df1a` 的主题为 `port.container.event.v1`，SASL 认证，读取时已启用且最近验证正常、版本 9。相关 Kafka 订阅列表尚无申请。

这些是核对时的状态。本轮没有重复扫描、登记或冒用需求方、管理员办理正式授权。输入是项目 SQL 预置模拟数据，不是真实广州港业务数据。

1. 提供方查看“我的数据源 → 元数据扫描 → 我的数据资源 → 我的接口能力”，无需重复创建已有登记。
2. 需求方进入“资源目录 → 资源详情 → 可申请接口能力 → 申请订阅”。仅允许公开浏览的资源才会出现。
3. 填写名称、目的、原因和有效期，明确接受完整消息及可访问历史，保存并提交确认。
4. 管理员确认范围，再配置实际订阅权限。审批通过不代表 Kafka 权限已生效。
5. 需求方领取本主体的一次性凭据，在底座外运行 `examples/kafka-demand`，查看本申请“消费观察”。
6. 停止或撤回后，等待“已撤权”，验证外部后续读取被拒绝。

提供方当前只能查看相关共享记录，没有正式确认环节，不能代替管理员审批。底座不代理或保存消息正文。

## 验证与证据

| 验证 | 结果 |
|---|---|
| 完整后端 | Foundation 137 + Provider 30 = 167；失败、错误、跳过均为 0，含注册与三角色浏览器 |
| 前端 | npm test 137 项通过，npm run build 成功；既有大于 500 KiB 的包体警告保留 |
| 三角色最终浏览器专项 | 提供方只读、按提供方联动、错误链接拒绝、选项失败重试、同一组件旧响应隔离通过 |
| 显式演示 | 原登录、扫描、资源和能力 smoke 通过；独立 test:subscription-demo 验证订阅目录入口无真实 API 请求，申请和刷新禁用，无授权或凭据操作 |
| 原生 Kafka 完整链路 | 实际生产并消费 1 条、320 字节、位点 0；权限限制、撤权和 REST 回归通过 |
| 数据保护与清理 | 原源 20 行且内容摘要不变、资源快照不变，开发库仍 V16；Kafka 独立登记为 0，临时服务停止，临时权限撤销，Windows 测试主题保留 |
| Git | diff --check 通过；未提交或推送 |

浏览器目录测试使用独立元数据夹具，只证明真实身份/API及交互，不把夹具的能力状态计为 Broker 验证。实际生产、订阅和撤权由单独完整链路证明。初轮浏览器夹具因同资源重复能力名称失败，已修复；以最终全量和专项结果为准。

工作区根目录 `.runtime/` 中保留 `kafka-application-backend-final.log`、`kafka-application-web-final.log`、`kafka-application-build-final.log`、`kafka-application-browser-final.log`、`kafka-application-demo-smoke.log`、`kafka-application-demo-isolation.log`、`kafka-application-chain.log`。项目 `.runtime/kafka-chain-6940702336494052114/` 中保留实际 Kafka 的 `verification.json`、`cleanup.json` 和截图。隔离登记已清理，不会伪装成开发工作台的正式申请。

![需求方真实申请表（独立验收账号）](../../.runtime/role-evidence/kafka-demand-application.png)

## 未完成项

提供方确认、外部消费者持久补报和可靠处理/位点提交、完整消费计量、稳定 Linux 部署、CDC、资源结构应用及版本恢复仍未完成。消费观察仍为外部技术摘要自报，不代表业务处理成功或完整计费。Windows 在线删除主题问题仍未解决。
