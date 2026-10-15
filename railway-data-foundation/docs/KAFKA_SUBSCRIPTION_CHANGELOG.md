# Kafka 订阅整主题审批交付

日期：2026-10-09。用户明确选择独立整主题审批，完整消息及可访问历史；现有 REST 字段/时间策略保持原范围。

## 本轮交付

- V12 两张审批元数据/历史表，全部字段中文注释；无凭据或消息存储。
- 新模块 `subscription/`：草稿登记和编辑、提交、明确确认、退回、撤回；固定双方主体、资源/能力、主题与消费组；版本冲突409、历史同事务、引用保护。
- 前端“共享治理 → Kafka 订阅授权”：中文服务端检索和10/20/50项分页、申请表单、范围勾选、审批操作、详情及历史。错误清空旧结果，重试与离页隔离旧读取，冲突保留输入并停止重复提交。
- 显式演示模式禁用该工作流且不发真实请求；真实失败不转成功。

本阶段只完成**审批流程**。确认后仍显示“Broker 未配置”，不签发 Kafka 密码或授予 ACL；撤回是撤回申请，没有声称已清除外部 Broker 订阅权限。下一阶段继续独立身份、权限执行/撤销与到期联动。

## 验证

完整后端 **137项通过**（Foundation107、Provider30），0失败/错误/跳过。新增10项真实 MySQL 集成测试覆盖明确范围、独立流程、错配和伪造拒绝、停止依赖、主题变化、有效期、并发版本、引用保护、唯一消费组、分页及历史失败事务回滚；核对资源、业务字段、能力和原 REST 策略/关系不变。

前端 **117项测试通过**，`npm run build` 成功；原有 Element Plus 主包大于500kB提示仍存在，不是构建错误。原汇报演示浏览器回归通过。

`test:subscription-live` 使用真实 HTTP 审批接口验证新建、提交、退回、编辑、确认、撤回、历史、分页、读取失败与重试、晚响应及真实/演示隔离。其临时主体、资源和能力为明确的审批测试夹具；`ENABLED/UP` 预置元数据只用于验证审批条件，没有 Broker、实际订阅或成功 ACL 拦截。成功审批响应全部来自真实服务；故障和延迟只注入 GET。不能把该测试当作订阅实际执行验证。

可在 `foundation-web` 终端复验：

```powershell
$env:SUBSCRIPTION_MYSQL_EXE='本机MySQL安装目录/bin/mysql.exe'
npm run test:subscription-live
```

要求本机 Foundation/数据库运行、前端依赖安装、V12迁移完成。脚本使用本机受保护 `client.cnf`，不把密码放命令行；只登记随机编号的测试元数据，结束后按精确绑定清理，不读取或更改 Source 业务行。测试前后原 `port_source_db.container_event` 保留20条示例数据。

## 主要文件与操作

| 文件 | 用途 |
|---|---|
| `foundation-service/.../subscription/` | 新请求、控制器和审批服务 |
| `V12__kafka_subscription_approval.sql` | 追加两表、约束及注释 |
| `SubjectService/CapabilityService` | 删除和主题变更引用保护 |
| `KafkaSubscriptionIntegrationTest` | 审批及不变性验证 |
| `src/views/sharing/SubscriptionView.vue` | 中文审批页面 |
| `src/api/subscription.js`、`src/utils/subscription.js` | 真实API、状态与响应校验、旧响应隔离 |
| `scripts/subscription.test.mjs`、`subscription-live.mjs` | 前端逻辑及真实浏览器验证 |

正常使用：先登记启用双方主体，发布资源，并登记 SASL 的 Kafka 能力；能力验证/启用后可确认申请。进入“Kafka 订阅授权 → 新增订阅申请 → 接受整主题范围 → 保存 → 提交确认 → 再次确认范围”。需要停止申请时选择“撤回申请”。当前没有真实常驻 Broker时，能力验证会如实失败，不能为了演示将正式能力写成健康。

本地 Foundation 已打包更新并迁移 V12，Provider业务逻辑不变。具体参数和返回语义见 [接口说明](KAFKA_SUBSCRIPTION_API.md)。
