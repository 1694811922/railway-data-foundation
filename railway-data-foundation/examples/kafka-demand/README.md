# 外部 Kafka 订阅与消费观察示例

Java 17 独立示例，运行在外部调用方。业务消息从提供服务经 Kafka 直接到订阅程序；统一数据底座只接收可选技术摘要。不会新增需求方业务服务。

| 文件 | 作用 |
|---|---|
| `KafkaCallerConfig.java` | 独立 Kafka 认证配置，远程强制 TLS，拒绝使用 REST 令牌 |
| `KafkaSubscriber.java` | 限定时间及消息数的订阅，只显示位点和原始载荷字节，不显示业务内容，不提交消费位点 |
| `ConsumptionReporter.java` | 可选消费摘要上报，使用独立令牌；同一报告最多两次传输尝试，不重新读取消息补偿 |
| `ExactSubscriptionAcl.java` | 隔离权限测试的精确主题/消费组辅助类，不是生产协调器 |
| `KafkaBrokerAuthorizationTest.java` | 临时真实 Broker 的认证、主题/消费组权限及撤销验证 |
| `ConsumptionReporterTest.java` | 上报重试一致性、拒绝/重定向、响应校验和令牌隔离 |
| `KafkaFoundationLiveTest.java` | 显式启动真实底座/提供服务/Broker/浏览器，验证审批、消费、摘要展示和撤权 |

## 使用已经批准的订阅

先按 [订阅执行指南](../../docs/KAFKA_SUBSCRIPTION_EXECUTION.md) 完成申请、管理员审批和权限配置，再领取一次性凭据。必须使用该申请的专属主题、消费组、订阅身份及密码。

在项目目录的 VS Code 终端执行，以下地址及编号为占位，需要替换：

```powershell
./scripts/run-kafka-demand-example.ps1 `
  -Brokers '实际Broker地址:9093' -Topic '获批主题' `
  -Group '获批专属消费组' -Username '获批订阅身份' `
  -Protocol SASL_SSL -Mechanism SCRAM-SHA-512 `
  -FoundationUrl 'https://实际底座地址' -GrantId 'KSG_实际32位编号' `
  -OffsetReset earliest -MaxMessages 10 -Seconds 15 -Offline
```

脚本分别安全询问 Kafka 密码和消费摘要上报令牌，不回显，不写环境文件，不作为命令行参数。一次性领取时请分别保存，二者不可互换。远程底座与 Broker 必须使用加密连接；本机联调用 `http://127.0.0.1:18080` 和 `SASL_PLAINTEXT`。`-OffsetReset latest` 为默认，仅等待新消息；`earliest` 在消费组没有已提交位点时从可用历史开始读取。示例不提交位点，重复运行可能再次读取，摘要按“条次”累计。

省略 `-FoundationUrl` 和 `-GrantId` 则不启用摘要上报，仍可按 Broker 授权订阅，但底座消费量保持未知。填写其中一项时必须同时填写另一项。Java 程序对应进程环境为 `KAFKA_DEMAND_FOUNDATION_URL / KAFKA_DEMAND_GRANT_ID / KAFKA_DEMAND_REPORT_TOKEN`，秘密只传入子进程；不使用 VITE 配置。

收到消息后只上报报告编号、观察时间、分区、位点、载荷字节数。成功提示“消费观察摘要已确认上报”；失败提示未确认并以状态 2 退出，不能据此断言底座未收到。对同一编号重试不会重复统计。停止、过期或撤权后不接受新上报。

## 复验

```powershell
./scripts/test-kafka-demand-example.ps1 -Offline
./scripts/test-kafka-foundation-live.ps1 -Offline -NodeExecutable '你的node.exe绝对路径'
```

第一项无需 MySQL、Docker 或常驻 Broker；默认跳过显式完整链路测试。第二项需要本机 MySQL 与前端依赖，使用独立 `foundation_kafka_live_test` 库、临时两服务及 SCRAM Broker。它只读取原有模拟业务源的一条消息，不改业务表；登记、账号、订阅及报告结束后清理，临时 Broker 自动停止。测试子服务限制内存以适应本机并行浏览器验证。

## 范围

观察报告是订阅客户端主动声明；不等于业务处理成功、位点提交、无损采集、完整追踪或 Broker 可信计费。示例没有持久补报队列、进程恢复或业务处理逻辑。原有提供侧消息生产统计与本功能分开，不混加。正式业务调用方需要自行实现处理、位点提交及恢复；本轮仅完成底座摘要接收和外部示例。接口与旧凭据升级见 [消费观察接口](../../docs/KAFKA_CONSUMPTION_API.md)。
