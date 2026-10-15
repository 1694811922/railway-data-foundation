# Kafka 能力运行控制交付记录

日期：2026-10-09。V0.5 Kafka 接入阶段的提供侧控制，独立于需求方订阅授权。

## 本轮实现

- 消息主题能力支持真实验证、启用、停用；启用前重新探测指定 Broker 的主题元数据，并核对已发布资源、启用来源及固定提供服务的源表绑定。
- 新增只读 `GET /api/v1/capabilities/{id}/kafka-runtime`，保留 `{code,message,data}`。返回能力编号、类型、状态、是否允许发送、原因、主题、认证方式及非秘密来源元信息。普通 REST `/runtime` 不接受消息能力，防止类型串用。
- 提供服务的运维发送接口要求 `X-Provider-Token` 和 `X-Capability-Id`。每次读取业务行之前重新检查底座发送状态及来源、主题、认证方式；底座不可用、协议错误、能力停用或错配均拒绝。没有允许结果缓存；已通过检查的在途发送可能完成。
- 两服务支持独立 Kafka SASL 配置，PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512；远程连接要求 SASL_SSL，本机测试允许 SASL_PLAINTEXT。无认证 PLAINTEXT 只接受环回地址。拒绝将 REST 调用令牌用作 Broker 密码。私有 CA 可配置后端信任库，未关闭主机名验证。
- 前端“接口能力管理”开放支持类型的启停按钮；详情增加中文“消息发送状态”、失败重试及订阅边界提示，核对返回能力/主题/认证方式和类型，切换或关闭后隔离旧响应。真实失败不模拟成功。

本轮没有新增数据库表或改变 V11；没有修改业务源表、历史消息、资源字段、REST 关系或访问策略。

## 使用方法

1. 由运维准备 Broker 和已存在的主题。配置两服务的 `*_KAFKA_BOOTSTRAP_SERVERS` 与 `*_KAFKA_TOPIC`；主题必须一致。
2. 正式连接选择 `SASL` 能力认证，配置后端的安全协议、SASL机制、独立账号密码和必要信任库，参考 `.env.example`。Foundation 账号仅执行元数据描述，Provider 账号具备指定主题生产及启用幂等生产所需权限，不使用共享管理员账号。
3. 重启两服务，在前端将来源 `container_event` 注册成已发布资源，再登记消息主题能力，点击“验证 → 启用”。未配置 Broker 会明确失败，不自动启动 Broker 或创建主题。
4. 运维客户端对 `POST /containers/events/{id}/publish` 同时提供服务间令牌和对应能力编号。调用成功返回实际主题、分区和位点；停用能力后新的请求被拒绝。
5. 查看详情“消息发送状态”。验证成功只表示底座元数据探测成功，不证明 Provider 写权限、订阅权限或持续健康；发送结果以 Broker 实际回执为准。生产失败或超时不等于消息一定未写入，不自动重试。

## 验证

- 完整后端 127 项通过：Foundation 97，Provider 30。新增安全配置、临时 SASL KRaft Broker 的元数据认证/拒绝和生产回执测试，原 MySQL、REST、共享授权及监管测试保留通过。
- 实际 Broker 用于成功认证、错误密码、缺少描述/写入权限、目标主题不存在及实际发送回执；测试 Broker 使用环回临时监听、随机凭据与隔离技术样例，结束自动清理专属目录。匿名控制器超级用户仅用于此测试，不能复制到正式部署。
- Provider 集成测试使用真实测试 Source、真实 Broker 和受控底座响应，验证状态/来源/类型/主题/认证错配、底座不可用、坏响应或缺少编号时不读取源行；原 Kafka 生产摘要仍只保存无业务值的计量信息。
- 前端111项测试、`npm run build`与新Kafka详情/失败重试/旧响应浏览器专项通过；原REST真实启停及能力工作流浏览器回归通过。测试登记已清理；库V11、原Source20条模拟事件与两服务health code=0已实际核对。本机没有长期运行的 Broker，浏览器成功启停采用拦截响应检查界面；真实 API 用于错配拒绝、状态读取与重试，不能据此宣称本机 Broker 已联通。

## 本轮文件

后端：`capability/KafkaClientSettings.java`、`KafkaCapabilityVerifier.java`、`CapabilityService.java`、`CapabilityController.java`；提供侧 `KafkaClientSettings.java`、`KafkaEventPublisher.java`、`CapabilityRuntimeGuard.java`、`ContainerEventController.java`；对应安全与 Broker 测试、现有扫描/提供服务集成测试。

前端：`api/capability.js`、`utils/dataHub.js`、`utils/capabilityWorkflow.js`、`utils/capabilityForm.js`、`views/capability/CapabilityView.vue`；`scripts/kafka-runtime.test.mjs`、`kafka-runtime-live.mjs`、原能力测试及启动命令。环境样例与接口、状态、使用文档同步。

## 仍待开发

需求方整主题还是字段/数据时间隔离主题的共享粒度尚未获得明确确认；不自动转换 REST 策略。独立申请/确认、Broker订阅凭据与 ACL 执行/撤销、到期处理、共享关系联动、消费侧计量和 CDC 均未完成。

能力停用仅阻止提供服务新的受控发送，不移除 Broker 消费权限、不删除主题或已有消息，也不能阻止其他具有 Broker 写权限的外部程序。底座不消费或代理 Kafka 明细。现有“UP”是最近一次探测的结果，不是后台持续监测。

## 复验入口

从项目目录运行 `scripts/test-local.ps1 -Offline`；进入 `foundation-web` 运行 `npm test` 和 `npm run build`。

浏览器专项需要先启动本地服务并向终端导入项目 `.env.local`（不要复制密码到命令行或文档），依次运行 `npm run test:capability-live`、`npm run test:kafka-runtime-live`。前者创建受保护的临时来源/资源供后者关联；后者自行删除自己的草稿能力。最后在项目目录运行 `scripts/cleanup-sharing-ui-test.ps1` 清理已记录的临时REST元数据。另运行 `npm run test:capability-workflow-live` 检查原工作流与响应隔离。

Kafka浏览器成功启停属于明确拦截的界面测试；不能代替实际Broker验收。实际认证、元数据探测、生产回执及权限拒绝由完整后端中的 `KafkaVerifierBrokerTest`、`KafkaPublisherBrokerTest` 与 `ProviderIntegrationTest` 验证；尚没有在本机常驻Broker上完成两服务全链路演示。
