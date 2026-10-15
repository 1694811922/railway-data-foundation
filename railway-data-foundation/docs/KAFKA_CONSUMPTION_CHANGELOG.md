# Kafka 订阅消费观察交付记录

日期：2026-10-10。范围为外部订阅程序的可选技术摘要、底座接收与订阅详情展示，不是需求方业务服务或完整消费计量。

## 已实现

1. 一次性领取 Kafka 密码时同时领取独立摘要令牌。令牌仅保存 SHA-256 摘要，前端只在弹窗内存展示；关闭、退出清除，不写浏览器存储。新配置失效旧令牌，原已领取凭据不回显。
2. 新增上报与读取接口，按授权关联主体、资源、能力及专属订阅身份。摘要仅包含报告编号、观察时间、分区位点与载荷字节。未知字段及非法数字拒绝，不存消息 key/value/headers。
3. 同一报告重试去重、内容冲突拒绝；申请父锁协调停止/撤回，执行/主体/来源/能力失效后拒绝新上报。技术令牌不替代 Kafka 或工作台认证。
4. 管理员可读平台摘要；提供方与需求方只能读取涉及本主体的申请。总数也执行归属检查，登录 Cookie 不能代替摘要令牌。
5. 订阅详情“消费观察”显示累计条次、字节与报告列表，10/20/50 项分页、刷新、错误清空与重试、晚到响应隔离。未上报为未知，演示模式不读取真实系统。
6. 独立 Java 订阅示例可选上报，按原始 value 字节计数，只重试同一报告传输，不重读业务数据补偿。脚本分开安全询问两种秘密，只传子进程环境。

## 主要文件

| 文件 | 作用 |
|---|---|
| `foundation-service/.../subscription/ConsumptionReportRequest.java` | 严格技术摘要结构与数量校验 |
| `subscription/ConsumptionService.java` | 随机令牌摘要、授权核对、报告去重、主体分页 |
| `subscription/ConsumptionController.java` | 外部报告及工作台读取接口 |
| `SubscriptionExecutor.java` | 领取事务同时签发摘要令牌、重新配置时失效旧令牌 |
| `security/SecurityConfiguration.java`、`WorkspaceGate.java` | 技术端点与会话认证隔离、普通角色编号范围 |
| `db/migration/V15__kafka_consumption_observations.sql` | 追加报告表及令牌摘要列，全部中文注释 |
| `foundation-web/src/components/KafkaConsumptionPanel.vue` | 共用中文摘要面板 |
| `views/sharing/SubscriptionView.vue`、`api/subscription.js`、`utils/consumption.js` | 详情集成、读取封装与响应检查 |
| `examples/kafka-demand/.../ConsumptionReporter.java`、`KafkaSubscriber.java` | 外部真实消费与可选技术摘要上报 |
| `scripts/run-kafka-demand-example.ps1` | 安全录入两种凭据的终端入口 |

## 验证

| 验证项 | 本轮结果与证据 |
|---|---|
| Foundation 完整测试 | 132 项通过，失败/错误/跳过均为 0；工作区 `.runtime/consumption-backend-accepted.log` |
| Provider 完整测试 | 6 个测试类、30 项通过，失败/错误/跳过均为 0；`.runtime/consumption-provider-accepted.log` |
| 前端单元测试 | `npm test` 129 项全部通过；`.runtime/consumption-web-final.log` |
| 前端生产构建 | `npm run build` 成功；`.runtime/consumption-build-final.log`。已有主包超过 500 kB 的非阻断警告 |
| 独立订阅示例 | 默认测试 16 项通过；显式完整链路默认跳过 1 项，随后单独执行并通过；`.runtime/consumption-example-verified.log`、`consumption-kafka-live-verified.log` |
| 真实三个角色浏览器 | 管理员、提供方、需求方及账号操作回归通过；`.runtime/consumption-role-browser-final.log` |
| 显式演示浏览器 | 登录、首页、扫描、资源、能力流程通过，不回退真实失败；`.runtime/consumption-demo-verified.log` |

后端合计 162 项按两个模块完成验证。首次完整 reactor 运行中 Foundation 132 项通过，但 Provider 的嵌入式 Broker 因测试磁盘空间不足失败；调整测试 Broker 的分区、日志索引及段大小，并等待真实消费组分配后，完整重跑 Provider 30 项通过。没有把首次失败标为通过，也没有修改生产 Broker 配置或放宽权限断言。

真实链路已使用独立 `foundation_kafka_live_test`、随机主体/账号/资源与临时 SCRAM Broker 验证：提供服务读取原有模拟源，实际发布 1 条 320 字节；外部 Java 程序实际读取该消息，上报摘要；需求方浏览器真实显示相同数量和字节。另验证原生产启停/审批/一次性领取/精确组与主题拒绝、撤权及外部 REST 兼容流程，撤权后摘要上报拒绝。没有拦截成功响应。

浏览器故障及旧响应测试仅对两次摘要读取注入 503，后续成功来自真实接口；截图前关闭秘密弹窗。证据为 `.runtime/kafka-chain-1042137260199528555/consumption-real.png`、`consumption-cli.log`、`consumption-browser.log`。新增服务测试使用隔离执行记录夹具，真实权限生效结论来自完整 Broker 链路，不能混用证据。

验证初期本机内存不足导致测试子进程失败；隔离测试服务限制内存与处理器数，测试上下文缓存限制为 2。真实角色测试与演示测试使用独立 Vite 缓存，避免并行开发服务的依赖优化互相覆盖。修正数据库启动测试固定 V14 的旧断言，追加 V15 新表及中文注释检查。

收尾已通过项目现有 `scripts/start-local.ps1 -Offline` 恢复 MySQL、Foundation 和 Provider；18080/18081 健康检查及 5173 页面均为 HTTP 200，未登录摘要读取为 401。开发库 Flyway V15 成功，报告表全部字段有中文注释；原业务源仍 20 条、原 2 个来源均保持未分配主体。开发库没有预设登录账号或报告；`foundation_test` 和 `foundation_kafka_live_test` 的测试账号、报告，以及完整链路订阅登记均已清理。临时 Broker 已关闭，正常开发环境仍没有常驻 Kafka。

## 使用与存量

进入“我的 Kafka 订阅”（管理员为“Kafka 订阅授权”）→ 查看 → 消费观察。摘要接入方法见 [示例指南](../examples/kafka-demand/README.md)；报文及权限见 [接口说明](KAFKA_CONSUMPTION_API.md)。

V15 保留现有数据及历史，未给旧已领取订阅补造令牌。旧订阅需要停止、确认撤权、重新配置并领取，才能启用摘要。没有报告时显示未知。按申请累计保留历史身份报告，独立提供侧统计不混加。

## 未实现

没有持久补报队列、消费业务幂等或位点提交、积压/分钟窗口统计、Broker 独立核验计费、完整分布式追踪或 CDC。不同报告重复读取相同位点仍再次计数；自报数量不证明业务处理完成。失败或进程结束可能丢失未确认报告，停止后延迟报告也会拒绝。V0.5 仍处于分步完善阶段。

本轮不提交或推送 Git。
