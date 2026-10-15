# 接口能力登记、验证与REST运行时启停（V0.2）

V14身份补充：能力列表/详情及写入按来源继承归属；需求方仅查看公开已发布资源的已启用/UP能力，不能写入或读取运行配置。Provider读取runtime/kafka-runtime携带服务端X-Provider-Token；外部调用invocation使用本人的Bearer调用令牌加X-Relationship-Id校验有效关系；登录工作台按会话权限读取。禁止匿名读取运行配置或向前端提供运维令牌，见 [认证与权限](WORKSPACE_AUTH_API.md)。

所有路径位于 `/api/v1`，返回 `{code,message,data}`。先从扫描结果创建并发布 Resource，才能登记 Capability。登记只代表访问方式的**元数据**；指定 REST GET 能力可通过独立 [Provider REST](PROVIDER_REST_API.md) 做真实无业务行探测，Kafka Topic 可做[元数据验证](PROVIDER_KAFKA_API.md)。CDC 尚未接入验证。

| 方法与路径 | 作用 |
|---|---|
| `GET /capabilities?limit=50&offset=0` | 能力列表 |
| `GET /resources/{resourceId}/capabilities` | 指定资源下的能力 |
| `GET /capabilities/{id}` | 能力详情 |
| `POST /capabilities` | 新建草稿，返回系统生成的 `CAP_...` ID |
| `PUT /capabilities/{id}` | 编辑草稿；所属资源和类型不可更改 |
| `POST /capabilities/{id}/verify` | REST发送HEAD、Kafka查询Topic元数据；草稿/已验证成功为VERIFIED/UP，失败为DRAFT/DOWN；停用状态验证后仍保持DISABLED |
| `POST /capabilities/{id}/enable` | 现有REST查询能力在VERIFIED或DISABLED且UP时，重新探测成功后变为ENABLED |
| `POST /capabilities/{id}/disable` | 现有REST查询能力从ENABLED变为DISABLED，阻止新的Provider查询 |
| `DELETE /capabilities/{id}` | 删除草稿能力；已验证、启用或停用能力保留历史 |
| `GET /capabilities/{id}/runtime` | Provider使用的控制元数据；返回可用标志及非秘密来源信息，不返回密码或业务行 |
| `GET /capabilities/{id}/invocation` | 现有REST查询能力的只读调用说明：后端配置的提供服务地址、完整路径、请求头与当前可用标志 |

共同必填字段：`capability_name`、`resource_id`、`type`。支持四类：

| 类型 | 必填技术字段 | 约束 |
|---|---|---|
| `REST_API` | `http_method`、`endpoint`、`protocol`、`auth_type` | `GET/POST`；Endpoint 只能是相对路径，例如 `/containers/events`，不能提交外部 URL |
| `KAFKA_TOPIC` | `topic`、`protocol`、`auth_type` | 协议为 `KAFKA`；可附 `message_schema` 元数据 |
| `CDC_STREAM` | `source_table`、`topic`、`protocol` | 来源表须与关联 Resource 的物理表相同；协议为 `DEBEZIUM` |
| `READONLY_QUERY` | `query_template_code` | 只登记模板代码，不接收任意 SQL 文本 |

可选 `description`。初始 `status=DRAFT`、`health_status=UNKNOWN`、`version=1`，均由后端维护。能力名称在同一资源下不得重复。`auth_type` 只是认证方式标签，不能通过 API 提交密码、令牌或 Kafka 凭据；后续真实接入仍须使用服务端环境配置。

REST 示例：

```json
{"capability_name":"集装箱事件查询接口","resource_id":"RES_实际ID","type":"REST_API","http_method":"GET","endpoint":"/containers/events","protocol":"HTTP","auth_type":"NONE","description":"按箱号查询事件"}
```

REST验证主机只取服务端`PROVIDER_REST_BASE_URL`，只支持`REST_API`、`GET`、`auth_type=NONE`、`endpoint=/containers/events`，协议须与地址一致。来源比较使用主机、端口、数据库及物理表，兼容动态Source和旧环境引用；无需两者使用相同的环境前缀或用户名。主机按文本忽略大小写比较，`localhost`与`127.0.0.1`仍视为不同配置。资源必须已发布、Source必须启用。

HEAD成功须返回204、Provider标识、`X-Railway-Runtime: rest-capability-v1`和对应来源的主机/端口/数据库/表响应头。Foundation不读取业务行；旧版未接入启停的Provider不能通过本次验证。Kafka仍只查询服务端配置的指定Topic元数据，不消费消息；Kafka、CDC、只读模板的运行时启停尚未实现，调用启用返回明确业务错误。

## REST状态与运行时控制

| 当前状态 | 允许的操作与结果 |
|---|---|
| DRAFT | 可编辑、验证；验证通过后为VERIFIED |
| VERIFIED | 可重新验证；UP且重新HEAD通过后可启用 |
| ENABLED | 能力就绪；新Provider GET还需通过身份和共享策略检查，可停用，不允许直接编辑或重新验证 |
| DISABLED | 可验证但仍保持停用；UP且启用前探测成功后可重新启用 |

启用前探测失败不会变为ENABLED，会记录DOWN并提示恢复服务后重新验证；重复启用已启用能力、重复停用已停用能力返回原记录。编辑、验证和启停使用版本条件更新，冲突时提示刷新重试。

V0.3查询Provider同时需要`Authorization: Bearer 调用令牌`、`X-Relationship-Id`及`X-Capability-Id`。Provider每次GET向Foundation的`access/check`请求访问决策，联合身份、关系、策略及能力状态检查，不缓存允许结果，再核对自身来源并执行字段/时间限制。拒绝或控制故障时不读取业务行。HEAD及进程health继续不需要调用凭据。

runtime响应仍为能力控制元数据`capability_id/status/enabled/reason/source_host/source_port/database_name/physical_table`，不单独提供身份授权。不可用时来源信息为空。能力编号用于选择访问方式；V0.3访问决策另行检查需求方凭据与共享条件。停用影响新的检查请求，已通过检查的在途请求可能完成；健康状态是最近验证结果，尚未后台巡检。完整示例见[Provider REST](PROVIDER_REST_API.md)与[访问接口](ACCESS_API.md)。

## 调用说明与登记帮助

能力前端已补中文操作流程与状态含义，禁用按钮显示状态/适配器原因，并在操作函数中拒绝不支持请求。草稿编辑绑定当前编号、版本、状态、所属资源、类型及模式；保存锁定输入、只提交约定业务字段。切换资源入口或离开页面后旧保存/启停响应不刷新新页面；刷新列表同步已打开的详情状态，调用说明校验编号与模式。前端不是身份权限控制，最终状态与冲突仍由现有后端校验。系统说明同步实际支持范围，见[能力流程交付记录](CAPABILITY_WORKFLOW_CHANGELOG.md)。

能力管理通过已有能力和资源分页接口读取，每类最多5,000项；前端提供组合检索、10/20/50项显示分页和范围提示，未增加搜索接口。注册只选择已发布资源。默认模式下读取失败清空对应区域，不自动切成演示；仅显式VITE_USE_MOCK=true使用演示数据，真实模式不会因演示编号切换。真实保存失败保留输入、不自动重发，网络中断或超时提示刷新确认；提交期间防止重复保存，同编号旧调用说明不能覆盖新请求。实现与验证见[能力管理交付记录](CAPABILITY_CATALOG_CHANGELOG.md)。

接口能力详情通过`invocation`读取提供服务地址；与REST验证复用服务端`PROVIDER_REST_BASE_URL`配置和地址校验，不从前端指定目标主机。读取说明不发送HEAD、不读取业务行、不改变能力状态。草稿和停用能力也可查看说明，但`enabled=false`。仅支持现有`auth_type=NONE`原始适配器的`GET /containers/events`，共享查询仍要求Bearer令牌；其他能力类型返回明确错误，未知编号返回404。

响应保留`capability_id/http_method/provider_base_url/endpoint/request_url/header_name/header_value/enabled/reason`，追加`required_headers`（三个必带头）及`authorization_note`。`enabled`只表示能力就绪，不是当前调用者授权结果。例如`request_url=http://127.0.0.1:18081/containers/events`，能力头值为实际编号，另外两项由调用方提供。无数据库密码、用户名、令牌或业务明细。地址只接受HTTP/HTTPS根地址，不接受用户信息、子路径、查询参数或片段；协议须与能力一致。

前端展示完整地址、请求头及可复制的PowerShell命令。真实说明请求失败显示错误与重试，不回退为模拟调用信息；演示说明集中于`src/mock/demoData.js`，明确不能直接调用真实服务。调用方必须能访问配置地址；当前查询仍在独立Provider发生。

登记表单提供中文说明和“填入本机示例配置”：只填访问方式，不创建接口或消息主题。REST认证选项为无认证/令牌/用户名密码，Kafka为无认证/SASL；选择方式不代表该认证运行时已实现。CDC来源表自动取关联资源，查询模板只允许代码、不接受SQL。技术字段校验位于`src/utils/capabilityForm.js`，服务端仍负责最终验证。

## 2026-10-09 Kafka 提供侧能力启停

消息主题能力新增真实验证与启停，支持 `auth_type=NONE` 的本机 PLAINTEXT 或 `auth_type=SASL` 的后端认证配置。启用前重新检查主题；停用不依赖 Broker 或 Source 正常。启用/验证失败不会模拟成功；重复启用/停用保留既有幂等语义及并发版本条件。

`GET /api/v1/capabilities/{id}/kafka-runtime`：只读发送准入信息，未知编号404。数据包括 `capability_id,type,status,enabled,reason,topic,auth_type,source_host,source_port,database_name,physical_table`。禁止时来源字段为空，不暴露账号、密码或业务明细。REST `/runtime` 对消息能力仍返回禁止。

Provider 每次发送前检查此结果。此接口不授予订阅权限，不改变共享策略。验证和健康状态只反映最近的主题元数据探测，真正生产写入以 Broker 回执为准。配置与限制见 [运行控制交付](KAFKA_RUNTIME_CHANGELOG.md)。

## 2026-10-09 Kafka生产记录关联

`GET /api/v1/capabilities/{id}/kafka-runtime` 新增 `resource_id`。提供侧校验后从底座响应取得该编号与能力编号，再登记生产摘要；不采信调用方的资源或需求方身份头。成功记录可按资源/能力查询，关系、需求方和策略仍为空。缺失/非法资源编号返回503并拒绝发送。两服务应一同更新；旧底座响应缺少该字段时新版Provider默认拒绝。

真实SCRAM Broker、两服务与前端链路已验证，详见 [完整链路交付](KAFKA_COMPLETE_CHAIN_CHANGELOG.md)。生产计量不代表消费侧交付或处理完成。
