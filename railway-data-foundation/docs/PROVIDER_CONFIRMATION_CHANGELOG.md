# 提供方确认共享内容：交付与使用

日期：2026-10-13。覆盖 REST 共享和 Kafka 整主题订阅。

本轮新增了此前缺少的业务环节：**需求方提交 → 提供方确认或退回 → 管理员最终审批 → 原访问启用或权限配置**。不是仅整理已有表单。

## 在前端怎样操作

| 角色 | 页面及操作 |
|---|---|
| 需求方 | “我的共享申请 / 我的 Kafka 订阅”：保存后提交，等待提供方确认；被退回后可编辑、重新提交 |
| 提供方 | “共享申请与记录 → REST 共享记录 / Kafka 订阅记录”：对本单位待审批的新申请点击“内容确认”，选择“确认共享内容”或“退回申请” |
| 管理员 | 原共享管理页面：提供方确认后才出现最终确认动作；保留管理员退回、REST启用及Kafka实际权限配置 |

REST 确认窗口展示需求方、资源、能力、用途、授权期、允许字段和数据时间范围；Kafka 展示用途、授权期和完整消息及可访问历史。确认须勾选核对并填写原因，退回须填写原因。不能编辑已提交内容来绕过确认。

提供方确认成功后，申请仍为待审批，**不会签发访问权限**。列表和详情分别展示提供方确认、审批和运行状态，历史记录真实登录账号。已确认的内容在管理员审批前仍可由提供方退回；任何退回、草稿编辑或重新提交均清除旧确认，之后须再次核对。

本地后端、提供服务和前端已更新并启动，入口 `http://127.0.0.1:5173`。重启会话后需要重新登录，原账号密码不变。只有本主体收到的 `REQUIRED` 待审批申请才有“内容确认”按钮；没有申请时不创建虚假记录来展示按钮。

## 归属、存量和权限

V17 追加迁移，只给现有 REST 关系和 Kafka 订阅各增加确认模式与时间，不新增表，不修改业务源结构。

- **REQUIRED**：新增有明确来源归属的申请必须经所属提供方确认，管理员不能跳过。
- **ADMIN_MANAGED**：新增无归属来源的申请仍仅由管理员管理，不把来源分配给普通提供方。
- **LEGACY**：升级前记录保留原流程、原审批及操作人，不补造确认时间或历史，界面标为“历史流程”。

确认模式由服务端锁定来源后确定，不接受前端指定。未登录 401，其他提供方、需求方或管理员冒充提供方调用确认/退回均为 403。确认、父版本和历史同事务提交；版本冲突为 409，不自动替换版本重发。REST 关系的旧版本冲突也统一为 409，业务状态不允许仍为 400。

原 REST 字段/时间限制与外部调用凭据、Kafka专属身份/精确权限/撤权、一次性凭据规则保留。底座不生产或消费业务消息，不保存业务明细。

## 主要文件

| 文件 | 作用 |
|---|---|
| foundation-service/.../db/migration/V17__provider_sharing_confirmation.sql | 追加字段和中文注释，存量默认原流程 |
| foundation-service/.../sharing/ProviderReviewRequest.java | 当前版本、原因和内容核对请求 |
| foundation-service/.../sharing/RelationshipService.java、RelationshipController.java | REST提供方确认/退回及最终审批门槛 |
| foundation-service/.../subscription/SubscriptionService.java、SubscriptionController.java | Kafka确认/退回、审批及运行有效性门槛 |
| foundation-service/.../security/WorkspaceGate.java | 认证角色、申请主体、资源来源检查，禁止管理员冒充提供方 |
| foundation-service/.../access/AccessService.java | 新流程未完整确认启用时拒绝REST访问 |
| foundation-web/src/utils/providerReview.js、sharing.js、subscription.js、workspace.js | 状态及响应校验、按角色选择真实动作 |
| foundation-web/src/components/ProviderReviewTag.vue | 共用确认状态标签 |
| foundation-web/src/views/sharing/RelationshipView.vue、SubscriptionView.vue | 提供方核对对话框、确认/退回、状态和历史 |
| foundation-service/.../WorkspaceRoleIntegrationTest.java、foundation-web/scripts/role-workspaces-live.mjs | 独立主体真实身份与浏览器流程测试 |
| foundation-web/scripts/provider-review.test.mjs | 新流程、角色隔离、历史兼容及错误响应校验 |
| examples/kafka-demand/.../KafkaFoundationLiveTest.java | 实际Kafka及REST三角色确认/授权/拒绝/撤权，提前保存无秘密测试身份清单便于中断恢复 |

服务端源码 `...` 对应 `src/main/java/cn/railway/foundation/`，测试对应 `src/test/java/cn/railway/foundation/`；SQL对应 `src/main/resources/`。完整协议和约束见 [设计说明](PROVIDER_CONFIRMATION_DESIGN.md)。

## 验证结果

| 检查 | 最终结果 |
|---|---|
| 完整后端 | Foundation142 + Provider30 = **172项**，失败/错误/跳过0，含注册及三角色浏览器 |
| 前端 | **142项**通过，npm run build成功；保留既有包体警告 |
| 演示隔离 | 原前端smoke及独立订阅演示测试通过，订阅演示入口不请求真实API或办理权限 |
| 真实三角色浏览器 | REST退回/重新提交/确认/最终审批、Kafka确认/最终审批、勾选必填、请求失败不伪成功及原账号/目录回归通过 |
| 并发及事务 | 提供方退回与管理员审批仅一个版本提交；历史写入失败回滚确认及版本；越权、过期版本与存量流程验证通过 |
| 实际Kafka | **1条320字节**真实生产和消费；新增归属申请不能跳过提供方，管理员配置后可消费，撤权后拒绝 |
| 实际REST | 新申请提供方确认前/后均拒绝；管理员审批并启用后允许且仅返回授权字段；撤回后拒绝 |
| 数据保护 | 原container_event20行及内容摘要不变，测试中的资源字段/版本/能力保持原值；开发库V17 |
| 清理 | 独立Kafka测试登记已清理、临时服务停止，Broker临时测试身份/权限0；Windows保留测试主题及消息，不删除业务数据 |
| 本地服务 | 前端5173、Foundation18080、Provider18081均健康 |

测试中的来源归属准备只发生于随机的独立测试登记，不用于分配正式存量来源。元数据浏览器夹具不计为Broker验证；实际权限另由真实链路验证。

初轮新增测试的资源重复夹具、关闭执行时的错误码预期、隐藏的Element Plus复选框定位及启动版本断言已修复；以最终通过记录为准。

一次真实联调被中断，没有成功/清理标记，**不计通过**。随后核对原服务已停止，恢复本地服务；按已识别的精确测试命名空间撤销三条临时SCRAM身份和权限，并在隔离库无来源/主体/订阅时删除唯一测试管理员。测试主题保留。重新完整联调通过，后续测试在任何副作用前保存不含密码的 `test-manifest.json`。

工作区根 `.runtime/` 保留 `provider-confirmation-backend-final.log`、`provider-confirmation-web-final.log`、`provider-confirmation-build-final.log`、`provider-confirmation-chain-final.log`、`provider-confirmation-start.log`、`provider-confirmation-demo-smoke.log`、`provider-confirmation-demo-isolation.log` 和浏览器日志 `role-browser.log`。成功实际链路证据在项目 `.runtime/kafka-chain-9726310907437723224/` 的 `verification.json`（含provider_confirmation_flow=true）与 `cleanup.json`；被中断目录为 `.runtime/kafka-chain-1882191585926162134/`。

![提供方Kafka确认窗口（独立真实验收账号）](../../.runtime/role-evidence/provider-kafka-review.png)

## 尚未实现

审批通知、多个提供方会签、结构变化应用及版本恢复、CDC、可靠业务消费和完整消费计费、稳定Linux部署仍在后续。本轮没有把Kafka业务生产/消费搬到底座，也没有提交或推送Git。
