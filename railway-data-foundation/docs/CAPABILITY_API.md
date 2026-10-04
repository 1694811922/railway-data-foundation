# 接口能力登记、验证与REST运行时启停（V0.2）

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
| ENABLED | 新Provider GET请求可读取数据；可停用，不允许直接编辑或重新验证 |
| DISABLED | 可验证但仍保持停用；UP且启用前探测成功后可重新启用 |

启用前探测失败不会变为ENABLED，会记录DOWN并提示恢复服务后重新验证；重复启用已启用能力、重复停用已停用能力返回原记录。编辑、验证和启停使用版本条件更新，冲突时提示刷新重试。

查询Provider需携带请求头`X-Capability-Id: CAP_实际编号`。Provider每次GET向Foundation的runtime接口读取控制元数据，不缓存允许结果，再核对其自身JDBC地址；未启用、Source停用、资源未发布或来源不匹配时返回403，底座状态接口不可达/非法响应时返回503。HEAD不需要能力编号，继续用于无业务行的健康检查；Provider进程health也不需要该编号。

runtime响应仅有`capability_id/status/enabled/reason/source_host/source_port/database_name/physical_table`。不可用时来源信息为空。能力编号用于选择访问能力，当前不校验需求方身份或共享授权；V0.3仍暂停。停用影响检查后发起的新请求，已经通过检查的请求可能完成。健康状态记录最近一次验证结果，尚未后台巡检。完整调用示例见[Provider REST](PROVIDER_REST_API.md)。

## 调用说明与登记帮助

能力管理通过已有能力和资源分页接口读取，每类最多5,000项；前端提供组合检索、10/20/50项显示分页和范围提示，未增加搜索接口。注册只选择已发布资源。默认模式下读取失败清空对应区域，不自动切成演示；演示配置或已存在的演示资源入口统一使用演示数据。真实保存失败保留输入、不自动重发，网络中断或超时提示刷新确认；提交期间防止重复保存，同编号旧调用说明不能覆盖新请求。实现与验证见[能力管理交付记录](CAPABILITY_CATALOG_CHANGELOG.md)。

接口能力详情通过`invocation`读取提供服务地址；与REST验证复用服务端`PROVIDER_REST_BASE_URL`配置和地址校验，不从前端指定目标主机。读取说明不发送HEAD、不读取业务行、不改变能力状态。草稿和停用能力也可查看说明，但`enabled=false`，不代表可以调用。仅支持现有无认证`GET /containers/events`；其他能力类型返回明确错误，未知编号返回404。

响应字段为`capability_id/http_method/provider_base_url/endpoint/request_url/header_name/header_value/enabled/reason`。例如`provider_base_url=http://127.0.0.1:18081/`，`request_url=http://127.0.0.1:18081/containers/events`，请求头名为`X-Capability-Id`，值为实际能力编号。无数据库密码、用户名、凭据或业务明细。地址只接受HTTP/HTTPS根地址，不接受用户信息、子路径、查询参数或片段；协议须与能力一致。

前端展示完整地址、请求头及可复制的PowerShell命令。真实说明请求失败显示错误与重试，不回退为模拟调用信息；演示说明集中于`src/mock/demoData.js`，明确不能直接调用真实服务。调用方必须能访问配置地址；当前查询仍在独立Provider发生。

登记表单提供中文说明和“填入本机示例配置”：只填访问方式，不创建接口或消息主题。REST认证选项为无认证/令牌/用户名密码，Kafka为无认证/SASL；选择方式不代表该认证运行时已实现。CDC来源表自动取关联资源，查询模板只允许代码、不接受SQL。技术字段校验位于`src/utils/capabilityForm.js`，服务端仍负责最终验证。
