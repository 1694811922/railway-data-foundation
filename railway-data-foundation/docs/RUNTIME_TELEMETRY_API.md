# 共享运行摘要接收与查询接口

> 最新管理员页面合并为“传输监测”。新增只读`GET /api/v1/transfer-monitor/events`及底座V19/需求方V4耗时字段，见[统一监测接口与口径](TRANSFER_MONITOR_GUIDE.md)。下文原事件、追踪、分钟窗口接口继续兼容，普通角色权限未扩大。

更新：2026-10-06。V0.4追加V10迁移，底座接收、去重、事件查询和分钟统计已接入Provider自动采集、异步补报及中文运行监管页面。单Provider真实REST查询与持久摘要/计量已核对，Kafka发送回执由嵌入式Broker验证；没有实际采集的对象仍为未知。详见[交付记录](RUNTIME_TELEMETRY_CHANGELOG.md)。

接口使用统一`{code,message,data}`响应，`code=0`表示成功。以下全部是治理和计量摘要，不接收业务行、令牌、连接密码、过滤值、SQL或自由格式异常文本。

## 1. 服务间摘要上报

`POST /api/v1/telemetry/reports`

必须携带后端运维配置的`X-Provider-Token`。普通需求方调用令牌不能上报；该服务凭据不得放入`VITE_*`变量、前端代码或浏览器存储。

采集节点编号由底座的`PROVIDER_TELEMETRY_NODE_ID`决定，默认`provider-local`；请求不得自行设置`collector_id`。当前只配置一个受信任节点，不是多节点凭据分配方案。

报告字段：

| 字段 | 说明 |
|---|---|
| `report_id` | 小写UUID；不确定结果重试时保持原编号和内容 |
| `collection_point` | `REST_PROVIDER`接口提供侧，或`KAFKA_PRODUCER`消息生产侧 |
| `window_start` / `window_until` | UTC整分钟、恰好一分钟；含起点、不含终点 |
| `window_closed` | 节点是否声明窗口关闭；关闭窗口不得尚未结束 |
| `events` | 0–200条事件；单报告不得重复事件编号或追踪编号 |

事件字段：

| 字段 | 说明 |
|---|---|
| `event_id` / `trace_id` | 小写UUID，分别定位事件和一次操作 |
| `relationship_id` / `capability_id` | 尝试访问的治理编号；格式无效时应为空，不代表获准 |
| `subject_id` / `resource_id` / `policy_id` | 执行侧从可信访问决策获得的编号；无法核对时为空 |
| `relationship_version` / `policy_version` | 当次使用版本，正整数；策略编号和版本必须同时出现 |
| `started_at` / `completed_at` | UTC毫秒精度；完成时刻归属报告窗口，开始不得晚于完成 |
| `outcome` | `SUCCESS`成功、`DENIED`拒绝、`ERROR`异常 |
| `reason_code` | 下述固定原因编号，禁止写入原始异常或用户输入 |
| `http_status` | 实际HTTP状态；成功REST为200，成功消息生产为200或202（现有Provider发送接口返回200） |
| `record_count` | 成功共享记录数，0–100；成功消息生产必须为1 |
| `byte_count` | 成功响应体或事件JSON字节数，0–134217728 |
| `duration_ms` | 操作耗时，0–86400000毫秒 |
| `topic` / `topic_partition` / `topic_offset` | 成功Kafka发送回执；REST必须为空 |

数字字段必须使用JSON整数，不能用数字字符串或小数。时间范围为2000–2099年，不能使用超过毫秒的精度；操作完成时刻最多允许五秒钟的时钟偏差。

成功原因为`COMPLETED`。拒绝状态为400、401或403，原因允许：`IDENTITY_INVALID`、`RELATIONSHIP_UNAVAILABLE`、`SUBJECT_UNAVAILABLE`、`RELATIONSHIP_INACTIVE`、`RELATIONSHIP_EXPIRED`、`POLICY_UNAVAILABLE`、`FIELDS_FORBIDDEN`、`TIME_FORBIDDEN`、`CAPABILITY_UNAVAILABLE`、`INVALID_REQUEST`、`OPERATOR_FORBIDDEN`。

异常通常为5xx，原因允许：`SOURCE_UNAVAILABLE`、`CONTROL_UNAVAILABLE`、`SERIALIZATION_ERROR`、`INTERNAL_ERROR`、`KAFKA_UNAVAILABLE`。响应已提交后发生序列化或输出失败时，允许实际HTTP状态仍为200、结果为`ERROR`且原因为`SERIALIZATION_ERROR`，不能把部分响应计为成功共享。拒绝或异常的记录数、共享字节数均必须为零；不能登记成功消息位点。

REST成功摘要必须包含完整授权定位编号和版本。Kafka成功摘要须提供主题、分区和位点，主题需与底座`FOUNDATION_KAFKA_TOPIC`一致（未配置时使用`port.container.event.v1`）；执行侧后续应与`PROVIDER_KAFKA_TOPIC`配置保持一致。该接口不会查询当前源表或重新执行授权，也不会将历史成功改为当前状态，因此受信任采集端必须只提供自己真实观察且核对过的摘要。

返回`data`包含：`report_id`、`accepted_events`、`duplicate_events`、`replayed`、`received_at`。

- 同一报告重试：内容相同则返回首次回执，`replayed=true`，不修改首次接收时间或重新累计。回执中的数量是首次报告处理数量，不是本次新增数量。
- 新报告重送旧事件：事件内容相同则计为重复事件，统计只计算一次。
- 报告编号或事件编号相同但内容不同：拒绝整个事务，新报告和其他新事件全部回滚。
- 同一节点同一采集位置复用追踪编号、却改用另一事件编号：拒绝。
- 报告中的事件顺序不参与重试冲突判断。并发重试已经使用真实MySQL验证。

首次接收时间和历史编号不可变；重试不覆盖原报告。当前没有自动过期或删除接口，正式保留期与归档方式待确定。

## 2. 调用事件与追踪查询

| 接口 | 用途 |
|---|---|
| `GET /api/v1/runtime-events` | 分页查询调用或消息生产摘要 |
| `GET /api/v1/runtime-events/{eventId}` | 单事件详情 |
| `GET /api/v1/runtime-traces/{traceId}` | 同一追踪编号下的摘要，按开始时间排序 |

列表支持：`resourceId`、`capabilityId`、`relationshipId`、`subjectId`、`outcome`、`traceId`、`collectionPoint`、`from`、`until`、`limit`、`offset`。时间按完成时刻筛选，含起点、不含终点。`limit`默认为50、最大200；`offset`最大1000000。

列表`data`为`{total,rows,limit,offset}`。事件以完成时间倒序展示，编号作为稳定排序的第二项。详情返回摘要字段，不包含内部内容校验摘要、凭据或业务结果。不存在的事件或追踪返回404。

当前管理查询沿用本机原型管理接口，尚未接入服务端管理员登录；不要将前端演示登录当作管理权限控制。追踪只保存已接收到的采集摘要，不宣称完整的Source到独立Demand调用链已经形成。

## 3. 分钟计量

`GET /api/v1/metrics/windows`

必须指定`collectionPoint`，不能填`ALL`混加调用数和消息数。可筛选`resourceId`、`capabilityId`、`relationshipId`、`subjectId`。`from`、`until`需为UTC整分钟，最多一天；默认查询最近15个已结束分钟。

每分钟返回一行，即使没有上报。状态为：

| 状态 | 含义 | 数量显示 |
|---|---|---|
| `MISSING` | 全局窗口未收到报告 | `null`，未知 |
| `PARTIAL` | 收到报告，但没有窗口关闭声明 | 有事件则为已观察数量；仅空报告则为`null` |
| `CLOSED` | 收到节点的窗口关闭声明 | 全局关闭空窗口可以显示0；仍不证明采集没有丢失 |
| `UNKNOWN` | 筛选的资源、关系、能力或主体没有对应事件 | `null`，不能用全局空报告推断该对象为零 |

返回字段：

- `window_start`、`window_until`、`collection_point`、`state`、`unit`。
- `attempts`：已观察操作数，包括拒绝和异常。
- REST的`calls`：所有已观察查询调用；`messages=null`、`unit=CALL`。
- Kafka的`messages`：成功消息生产数；`calls=null`、`unit=MESSAGE`。
- `successes`、`denied`、`errors`、`records`、`bytes`、`average_duration_ms`。
- `frequency_per_second`：该采集位置对应的调用数或成功消息数除以60，不跨位置求和。
- `reports`、`late_reports`、`maximum_report_delay_ms`：节点该分钟的报告情况；晚于窗口末端接收即为延迟。按对象筛选时这些仍是节点窗口整体报告信息。

空窗口平均耗时为`null`，而不是虚构的零耗时。真实记录历史不随资源、关系当前状态变化而抹除。

## 4. 采集状态及后端配置

`GET /api/v1/provider/telemetry`是Provider只读管理接口，默认`http://127.0.0.1:18081`，前端通过`/provider`代理请求。沿用本机原型管理接口边界，尚无服务端管理员登录。统一响应内返回：

| 字段 | 说明 |
|---|---|
| `enabled` | 是否配置自动采集 |
| `storage_ready` | 摘要目录初始化/启动恢复已完成；不保证后续磁盘一直可写 |
| `queued_events` / `staged_reports` / `pending_reports` | 等待处理事件、待落盘报告、已持久待补报报告 |
| `quarantined_reports` | 隔离的非法/损坏报告数量 |
| `submitted_events` / `dropped_events` | 当前进程提交与队列压力丢弃计数 |
| `spool_failures` / `delivery_failures` / `rejected_reports` | 存储失败、回执未确认和明确拒绝计数 |
| `delivered_reports` / `last_success_at` | 当前进程确认上报报告数及最近成功时间 |
| `last_failure_code` | 固定异常编号或null，不返回原始异常、凭据或磁盘路径 |

后端配置：`PROVIDER_TELEMETRY_ENABLED`（默认true）、`PROVIDER_TELEMETRY_SPOOL_DIR`（默认`.runtime/local/telemetry`）、`PROVIDER_TELEMETRY_QUEUE_CAPACITY`和`PROVIDER_TELEMETRY_MAX_PENDING_REPORTS`（默认各1000，允许1–10000）。可选`PROVIDER_TELEMETRY_BASE_URL`独立配置摘要接收地址，不改变访问决策使用的`PROVIDER_FOUNDATION_BASE_URL`。配置与服务间凭据仅供后端使用。

仅业务查询和消息生产形成执行事件，HEAD探测、健康检查和采集状态查询不计量。Provider生成`X-Trace-Id`，不采用客户端自报身份/追踪编号。后台每250毫秒处理队列，不确定回执重试原持久报告；启动前和首次不完整分钟不补造零值，完整分钟结束约5秒后关闭。进程崩溃前未落盘可能丢失，详见交付记录中的可靠性边界。

## 5. 当前实现文件与证据

核心文件位于`foundation-service/src/main/java/cn/railway/foundation/telemetry/`：

- `RuntimeTelemetryController.java`：接收、事件、追踪与统计HTTP接口。
- `RuntimeTelemetryService.java`：校验、事务去重、摘要存储、检索和窗口统计。
- `RuntimeReportRequest.java`、`StrictLongDeserializer.java`：输入结构与严格整数校验。
- `src/main/resources/db/migration/V10__sharing_runtime_observations.sql`：两张摘要表，全字段中文注释。
- `src/test/java/cn/railway/foundation/RuntimeTelemetryIntegrationTest.java`：真实MySQL鉴权、隐私边界、回滚、并发去重、窗口和查询验证。

2026-10-05接收阶段完整84项后端测试通过，随后采集代码完整回归增至Foundation72项、Provider23项，合计95项通过，0失败、0错误、0跳过。2026-10-06重新打包启动，真实REST允许/拒绝与自动落库事件、字节及分钟统计核对通过；中文监管页面90项前端测试、构建与真实浏览器专项通过。阶段范围、运行证据及剩余项见交付记录，不将手工摘要或HTTP测试夹具当作实际采集。

## 2026-10-09 Kafka生产记录关联

`GET /api/v1/capabilities/{id}/kafka-runtime` 新增 `resource_id`。提供侧校验后从底座响应取得该编号与能力编号，再登记生产摘要；不采信调用方的资源或需求方身份头。成功记录可按资源/能力查询，关系、需求方和策略仍为空。缺失/非法资源编号返回503并拒绝发送。两服务应一同更新；旧底座响应缺少该字段时新版Provider默认拒绝。

真实SCRAM Broker、两服务与前端链路已验证，详见 [完整链路交付](KAFKA_COMPLETE_CHAIN_CHANGELOG.md)。生产计量不代表消费侧交付或处理完成。
