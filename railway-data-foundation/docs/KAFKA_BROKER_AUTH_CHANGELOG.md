# V0.5 Kafka 基础交付：Broker 权限与最小订阅

日期：2026-10-09。范围是独立接入示例与真实 Broker 执行验证；底座共享关系与 Broker 的联动仍未实现，不标记整个 Kafka 小阶段完成。

## 文件及作用

| 文件 | 作用 |
|---|---|
| `examples/kafka-demand/pom.xml` | Java 17 独立示例，复用Kafka 3.9.1，未加入底座服务聚合 |
| `KafkaCallerConfig.java` | SASL客户端配置、凭据用途隔离、远程TLS与本机认证限制 |
| `ExactSubscriptionAcl.java` | 精确Topic/Group读取ACL增删，仅用作隔离验证基础 |
| `KafkaSubscriber.java` | 有限新消息订阅、仅输出位点/字节；未建立订阅时失败，不伪装成成功零条 |
| `KafkaBrokerAuthorizationTest.java` | 13项真实临时KRaft Broker测试，包含实际订阅可执行进程 |
| `src/test/resources/logback-test.xml` | 测试日志控制；身份错误与越权日志是预期拒绝证据 |
| `scripts/test-kafka-demand-example.ps1` | 不依赖Docker/业务数据库，自动执行临时Broker验证 |
| `scripts/run-kafka-demand-example.ps1` | 编译、安全输入Kafka密码、订阅子进程白名单环境及退出清理 |
| `examples/kafka-demand/README.md`、`docs/KAFKA_SHARING_DESIGN.md` | 接入方法、粒度选择及后续联动要求 |
| AGENTS、README、路线图、实现状态、测试、模块指南及Provider Kafka说明 | 同步完成范围与待实现内容 |

没有改变现有Java服务、数据库迁移、REST策略、前端管理页面、业务Source或原Kafka Topic。示例消息是独立技术测试数据，不是广州港业务事件。

## 验证结果

最终独立Maven **13项通过，0失败、0错误、0跳过**：无ACL默认拒绝、精确Topic/Group允许真实消息、错误密码、越权消费组、越权主题、只读不能生产、已建立连接移除ACL后拒绝、精确重复撤销保留其他消费组ACL、直接分区读取不能绕过主题权限、拒绝REST令牌/非法配置，以及实际CLI允许、CLI拒绝和Broker不可达不伪装成功。

Broker为随机本机端口，SASL/PLAIN仅用于隔离本机验证；生产运行入口支持SCRAM与TLS。测试结束关闭Broker并断言其专属临时目录不存在。当前没有常驻Kafka，控制器匿名超级用户仅属本机临时测试配置，不作为部署配置。

离线编译/打包与运行依赖生成通过；PowerShell启动脚本语法通过，Java订阅可执行程序在真实Broker上的允许/拒绝运行通过。本轮未重复宣称前一轮113项业务后端和107项前端测试为新增Kafka联动证据；业务服务未变，健康检查正常。日志 `.runtime/kafka-acl-example.log`、`.runtime/kafka-example-build.log`。

首轮修复Kafka配置short类型，随后修复Windows运行中删除映射文件导致Broker退出，以及最小子进程环境中的中文编码；最终重跑通过。增加不可达Broker测试后最终13项通过，失败轮次不计验收。

**清理例外：** 失败轮次留下 `C:\Users\凯\AppData\Local\Temp\kafka-563830446967998404`，已核对为本轮12:58创建的临时Broker目录。自动审批检查拒绝清理，返回仅“blocked by policy”；未绕过限制删除。它不参与现有服务运行。最终成功测试的临时目录已自动删除；此旧残留可由用户在资源管理器手动删除。

## 后续所需选择

已向用户询问共享粒度：整主题明确授权，或字段/数据时间隔离Topic；尚未收到答复。Broker ACL不能替代REST字段/时间筛选，不能自行放宽既有策略。下一步按选定粒度开发底座审批、独立Broker身份、能力状态与权限执行/撤销确认；消费侧计量与CDC仍待后续。设计与启动见 [Kafka共享设计](KAFKA_SHARING_DESIGN.md)。
