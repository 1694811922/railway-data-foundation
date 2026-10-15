# Kafka 订阅权限执行与使用

## 页面操作

管理员入口：**共享治理 → Kafka 订阅授权**；需求方入口：**我的 Kafka 订阅**；提供方入口：**共享申请与记录 → Kafka 订阅记录**（只读）。接口能力详情也提供按主题查看订阅记录的入口。

1. 接入/扫描业务源，登记并发布资源；登记 SASL 的 Kafka 主题能力，实际验证后启用。当前固定 Provider 支持配置的 `container_event` 与单个主题，不能把任意外部主题认作已支持。
2. 需求方从“资源目录 → 资源详情 → 可申请接口能力 → 申请订阅”进入，或直接新增申请。先选提供方，再选该提供方的已发布资源及 SASL Kafka 能力；需求方主体固定为登录账号关联主体。目录只展示允许公开浏览的资源，内部资源不能靠修改链接访问。更换提供方清空资源和能力，更换资源清空能力，并重新确认整主题范围。管理员可按现有规则管理未归属存量资源的申请，这不会修改数据源归属。
3. 需求方保存、提交整主题申请；新增有来源归属的申请先由提供方在“Kafka订阅记录 → 内容确认”核对用途、有效期和完整消息及历史范围，确认或退回。提供方确认后仍待管理员最终确认；管理员再从“订阅操作”配置权限。只有实际核对成功才显示“已配置”；失败显示“权限未确认”。存量“历史流程”和新增无归属“管理员处理”保留原审批方式，不补造提供方确认，不转移归属。提供方不能替代管理员审批。完整流程见[交付与使用](PROVIDER_CONFIRMATION_CHANGELOG.md)。
4. 需求方领取本主体的一次性订阅凭据，记录地址、安全协议、SCRAM机制、专属用户名/密码、主题、消费组和独立消费摘要令牌。客户端按照返回的精确主题与消费组连接 Broker，不使用 REST 令牌；底座不代理消息。凭据仅本次展示，不保存在浏览器持久存储。
5. “停止订阅”移除身份及权限，保留审批。需要再次订阅时重新配置新身份。审批“撤回申请”也触发异步撤权，但须等权限执行显示“已撤权”才表示完成核对。

提供侧能力启停控制新消息发送；消费侧授权控制读取。Kafka中已有消息是否可读由订阅授权和主题保留策略决定。来源、资源、能力或双方主体停用、到期会异步撤权。已交付的数据无法收回。

登记选项读取失败时显示错误并提供重试，不回退演示数据。错误、失效或错配的目录申请链接不会自动打开一个替代资源的申请表。切换申请入口后晚到的读取响应不会覆盖新选择；正式授权范围始终由服务端身份、归属检查和 Broker 权限确定。

## 服务端配置

没有常驻 Broker 时，默认执行开关关闭，不生成演示权限成功。保持现有 Foundation/Provider 启动方式；还需要真实 Kafka 集群、SASL和授权器，运维账号具有创建/删除 SCRAM、查询/修改 ACL、读取集群编号及目标主题元数据的权限。**订阅管理员必须与探测、生产、订阅身份分开**；不要把测试集群的匿名控制器超级用户配置用于部署。

在后端私有 `.env.local` 中配置，变量示例均为占位值：

```dotenv
KAFKA_SUBSCRIPTION_EXECUTION_ENABLED=true
KAFKA_SUBSCRIPTION_CLUSTER_ID=<实际集群编号>
KAFKA_SUBSCRIPTION_ADMIN_BOOTSTRAP_SERVERS=<集群地址>
KAFKA_SUBSCRIPTION_ADMIN_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SUBSCRIPTION_ADMIN_SASL_MECHANISM=SCRAM-SHA-512
KAFKA_SUBSCRIPTION_ADMIN_USERNAME=<独立权限管理员>
KAFKA_SUBSCRIPTION_ADMIN_PASSWORD=<管理员密码>
KAFKA_SUBSCRIPTION_RECONCILE_MS=5000
```

现有 `FOUNDATION_KAFKA_*` 使用独立元数据探测账号，`PROVIDER_KAFKA_*` 使用生产账号，两者和订阅管理员必须指向同一实际集群、同一精确配置主题。SSL使用对应前缀的信任库配置；参考 `.env.example`。`FOUNDATION_CREDENTIAL_KEY` 是已配置的32字节加密主密钥，不随意更换。管理员密码、主密钥和任何订阅密码不得放进 `VITE_*` 或提交Git。

配置后重启 Foundation/Provider，前端无需重打包密钥。开发环境协议可使用 `SASL_PLAINTEXT`，该协议只认证不加密；正式环境配置 `SASL_SSL`。页面领取的是客户端连接参数，不是权限管理员账号。

独立订阅示例：`examples/kafka-demand` 和 `scripts/run-kafka-demand-example.ps1`。使用终端提示安全录入领取的密码；无需修改底座核心代码，底座不代理业务消息。

## 执行记录与故障处理

| 文件/表 | 用途 |
|---|---|
| `subscription/SubscriptionService.java` | 审批、固定绑定、版本历史、普通执行摘要 |
| `subscription/SubscriptionExecutor.java` | 意图提交、权限执行、一次性领取、失败补偿 |
| `subscription/SubscriptionBroker.java` | 实际集群核对、SCRAM及精确ACL操作，不消费消息 |
| `subscription/SubscriptionReconciler.java` | 有界定时核对、到期/依赖失效撤权、未完成意图恢复 |
| `credential/EncryptedCredentialService.java` | 独立加密上下文绑定申请及本轮身份，防止跨记录复制 |
| `kafka_subscription_execution`（V13） | 专属身份、固定集群、意图、实际状态、待领取密文、脱敏原因与核对时间 |
| `foundation-web/src/views/sharing/SubscriptionView.vue` | 中文审批及执行操作、一次性凭据弹窗、历史 |

数据库和 Broker 不存在共同事务。出现权限未确认时不能假设已撤权，恢复同一集群配置后“重试执行”处理撤权；核对为已撤权后才能重新配置。结果入库失败可能已完成外部操作，刷新核对意图，不盲目重复提交。凭据遗失需停止、确认撤权、重新配置。

协调器只在执行开关启用且 Foundation 存活时工作；默认每5秒最多10条，积压及故障时更慢。服务停机不会使 Kafka 静态权限自动消失，停止控制服务前先完成全部实际撤权。管理端真实登录与三角色权限已实现，见 [角色指南](ROLE_WORKSPACE_GUIDE.md)。V15已支持独立消费观察摘要，见 [摘要接口](KAFKA_CONSUMPTION_API.md)；尚无完整消费计量、无损采集或 CDC。

## 实际联调

在项目目录执行：

```powershell
./scripts/test-local.ps1 -Offline
./scripts/test-kafka-foundation-live.ps1 -Offline -NodeExecutable D:/Dev/node.js/node.exe
cd foundation-web
npm test
npm run build
```

完整链路测试自动启动临时 SCRAM Kafka、隔离测试库中的真实两服务与浏览器。实际提供侧发送、客户端读取、精确组/主题/写入拒绝、撤权、一次性领取、失败恢复与异步条件失效均由真实响应验证，不拦截成功。测试结束停止临时服务/Broker，清理隔离元数据，保留 `.runtime/kafka-chain-*` 的脱敏证据；不修改原有 `container_event` 的20条模拟业务数据。

具体接口见 [KAFKA_SUBSCRIPTION_API.md](KAFKA_SUBSCRIPTION_API.md)。本机默认配置仍没有常驻 Kafka，不将临时集群测试冒充已配置开发环境。
