# 2026-10-09 发送入口变更

2026-10-13最新：提供方数据发送工作台已完成。真实登录/主体归属/CSRF核验后，通过独立Provider通道查询源记录、单条及最多50条勾选发送；显示真实回执、取消后续项和待确认结果，不自动重试。197项不同主模块测试、前端157/build、多角色/注册及真实浏览器发送3条并累计落库4条、故障恢复/撤权回归通过；原源20行不变，临时登记清理。本机服务已更新，旧会话需重新登录；库仍V17/V2/需求方V1，无CDC/后台生产/通用映射，不提交推送。见[发送使用指南](PROVIDER_PRODUCTION_GUIDE.md)和[交付记录](PROVIDER_PRODUCTION_CHANGELOG.md)。以下为历史。

`POST /containers/events/{id}/publish` 现在同时要求运维 `X-Provider-Token` 与消息主题能力 `X-Capability-Id`。先验证运维身份，再调用底座 `/api/v1/capabilities/{id}/kafka-runtime` 检查已启用、来源绑定、Topic 与认证方式，随后读取 Source 并发送到 Broker。缺少编号400；未启用/错配403；底座故障503；Broker发送故障503，不返回虚假回执。已通过检查的在途发送可能完成，失败或超时不证明未写入。

支持后端独立 SASL 配置（远程 SASL_SSL，环回测试可 SASL_PLAINTEXT），详情见 [Kafka 运行控制](KAFKA_RUNTIME_CHANGELOG.md)。此运维发送接口不是需求方订阅接口；停用不撤销 Broker 订阅 ACL 或历史消息。以下为历史阶段记录，旧的仅携带运维令牌示例必须补充能力编号。

# Provider Kafka 事件生产与 Topic 验证（V0.2）

2026-10-09补充：已新增独立[Kafka订阅与Broker权限验证](KAFKA_SHARING_DESIGN.md)，13项真实临时Broker测试通过。该示例与下文现有未认证Provider生产配置分开运行，不代表本生产接口已接入共享授权或Kafka能力启停；共享粒度与执行联动待后续。

Provider 使用只读账号从 Source 的 `container_event` 按 ID 读取一条事件，再直接向 Kafka 发送 JSON。Foundation 不接收、存储或消费该事件；它只查询登记的 Topic 是否存在、分区是否有 Leader。

运行前先启动一个可达的 Kafka Broker，并预先创建 `port.container.event.v1` Topic。两个服务使用同一个 Broker 和 Topic，环境变量如下；本机开发可写入未跟踪的 `.env.local`，启动脚本会导入。现有本地启动脚本**不会**安装或启动 Kafka；不配置时 REST 查询仍可用，Kafka 发布返回 503，Topic 验证返回明确配置错误。

```text
PROVIDER_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:19092
PROVIDER_KAFKA_TOPIC=port.container.event.v1
FOUNDATION_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:19092
FOUNDATION_KAFKA_TOPIC=port.container.event.v1
```

| 方法与路径 | 作用 |
|---|---|
| `POST http://127.0.0.1:18081/containers/events/{id}/publish` | 运维调用须携带X-Provider-Token和X-Capability-Id；Provider通过底座发送准入后读取指定Source事件并发送到固定Topic，返回发送回执，不返回业务行 |
| `POST http://127.0.0.1:18080/api/v1/capabilities/{id}/verify` | 对 `KAFKA_TOPIC` 查询 Topic 元数据；成功为 `VERIFIED/UP`，不可达或 Topic 不存在为 `DRAFT/DOWN` |

Kafka能力需关联已发布的container_event资源，protocol=KAFKA、auth_type=NONE或SASL、topic与配置一致；来源按地址、端口、数据库和表比较，兼容动态登记。Broker和Topic只取服务端配置；SASL认证参数由后端配置。Topic验证只说明元数据可达；真实发送还需Provider具备生产权限。提供侧运行时启停已开放，Broker消费访问仍没有接入共享ACL控制；V0.3将手动publish入口限制为运维服务凭据调用，普通需求方REST令牌不能触发生产。REST共享策略不代表Topic消费已获授权。

需要先从源库或允许返回id字段的受控REST查询取得事件编号。20条事件来自Source模拟迁移。发布时同时携带`X-Capability-Id`（已启用消息能力编号）及`X-Provider-Token`，令牌值由运维环境`PROVIDER_CONTROL_TOKEN`提供，不写入前端或Git。重复调用会再次发送同一事件；Producer保证单次发送幂等，该手动接口不提供跨请求去重。

完整本机测试 `scripts/test-local.ps1` 使用嵌入式 Kafka，不需要 Docker 或外部 Broker；测试实际消费一条发布的消息以核对内容。生产代码中没有 Foundation Kafka Consumer。

## 2026-10-09 Kafka生产记录关联

`GET /api/v1/capabilities/{id}/kafka-runtime` 新增 `resource_id`。提供侧校验后从底座响应取得该编号与能力编号，再登记生产摘要；不采信调用方的资源或需求方身份头。成功记录可按资源/能力查询，关系、需求方和策略仍为空。缺失/非法资源编号返回503并拒绝发送。两服务应一同更新；旧底座响应缺少该字段时新版Provider默认拒绝。

真实SCRAM Broker、两服务与前端链路已验证，详见 [完整链路交付](KAFKA_COMPLETE_CHAIN_CHANGELOG.md)。生产计量不代表消费侧交付或处理完成。
