# Provider Kafka 事件生产与 Topic 验证（V0.2）

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
| `POST http://127.0.0.1:18081/containers/events/{id}/publish` | Provider 读取指定 Source 事件并发送到固定 Topic；返回 `{code:0,message:"success",data:{topic,partition,offset}}`，不返回业务行 |
| `POST http://127.0.0.1:18080/api/v1/capabilities/{id}/verify` | 对 `KAFKA_TOPIC` 查询 Topic 元数据；成功为 `VERIFIED/UP`，不可达或 Topic 不存在为 `DRAFT/DOWN` |

Kafka能力需关联已发布的container_event资源，protocol=KAFKA、auth_type=NONE、topic与配置一致；来源按地址、端口、数据库和表比较，兼容动态登记。Broker和Topic只取服务端配置；SASL仅登记，不能验证或生产。Topic验证只说明元数据可达。Kafka运行时启停仍未开放，Broker消费访问没有接入ACL控制；手动publish接口保持原有行为，REST能力停用只控制GET查询，不控制该Kafka发布操作。

示例：先按[Provider REST](PROVIDER_REST_API.md)登记并启用REST能力，携带X-Capability-Id调用GET取得实际事件ID，再调用发布接口。20条事件来自Source模拟迁移。重复调用会再次发送同一事件；Producer保证单次发送幂等，该手动接口不提供跨请求去重。

完整本机测试 `scripts/test-local.ps1` 使用嵌入式 Kafka，不需要 Docker 或外部 Broker；测试实际消费一条发布的消息以核对内容。生产代码中没有 Foundation Kafka Consumer。
