# Provider REST：集装箱作业事件

Provider独立运行，默认监听`127.0.0.1:18081`。使用`PROVIDER_SOURCE_DB_URL/USER/PASSWORD`直连Source；本机启动脚本建立`provider_reader`只读账号，随机凭据在被忽略的`.env.local`。REST查询先按能力编号向`PROVIDER_FOUNDATION_BASE_URL`读取运行状态，再读取Source。Provider不连接`foundation_db`，业务行只返回给调用者。

同一 Provider 也支持从 Source 向 Kafka 生产指定事件，见 [Provider Kafka](PROVIDER_KAFKA_API.md)。

前端“接口能力 → 查看 → 调用方式”显示真实提供服务地址、完整接口路径、必带请求头及可复制的PowerShell命令，来自底座只读`GET /api/v1/capabilities/{id}/invocation`。此说明复用服务端`PROVIDER_REST_BASE_URL`，不发送探测、不读取业务行，也不会自动启用能力；先验证并启用后再调用。详见[调用说明接口](CAPABILITY_API.md)。

| 方法与路径 | 作用 |
|---|---|
| `GET /api/v1/provider/health` | Provider 进程存活，不代表 Source 可读 |
| `HEAD /containers/events` | 无需能力编号；检查Source表的SELECT权限与可达性，成功204及Provider/运行时版本/来源标识，不返回业务行 |
| `GET /containers/events` | 必须携带已启用能力的X-Capability-Id请求头；通过状态检查后直查container_event，返回统一响应 |

GET 支持可选 `containerNo`、`trainNo`、`eventType`、`from`、`to`、`limit`、`offset`。时间采用 ISO 本地日期时间，`from` 含、`to` 不含；`limit` 为 1–100（默认 20），`offset` 为 0–100000。按 `event_time DESC,id DESC` 稳定排序。查询目标表和可过滤字段固定，参数使用 JDBC 绑定，不接收自定义 SQL 或物理表名。

本机示例：

```powershell
# 先在前端接口能力管理中登记、验证、启用，再从详情复制实际编号。
$capId = 'CAP_替换为实际编号'
Invoke-RestMethod 'http://127.0.0.1:18081/containers/events?trainNo=X8021&eventType=LOAD&limit=10' `
  -Headers @{ 'X-Capability-Id' = $capId }
```

未携带有效能力编号返回400；未启用/已停用/来源不匹配或来源停用返回403；底座控制状态不可用返回503；Source查询失败也返回安全的503。拒绝的请求不会查询业务行。`PROVIDER_FOUNDATION_BASE_URL`默认`http://127.0.0.1:18080`，本机启动脚本按实际Foundation端口设置；只接受运维配置的HTTP/HTTPS主机，不接受客户端目标地址或重定向。连接超时2秒，请求超时3秒，每次GET检查状态。

HEAD返回`X-Railway-Provider: container-event-v1`、`X-Railway-Runtime: rest-capability-v1`及`X-Railway-Source-Host/Port/Database/Table`。这些是非秘密元数据，供Foundation核对真实服务的来源。HEAD不依赖能力启用状态，因而停用后仍可验证连接。动态登记的Source如指向同一主机、端口、数据库和container_event表也可登记此能力；Provider仍只服务其配置的一个来源，不自动生成其他数据库的业务接口。

现有20条事件来自`sql/source/V2__demo_events.sql`，是明确标识的模拟数据。业务查询访问Provider的18081端口；Foundation的runtime接口只传控制元数据。能力编号用于选择能力，V0.3需求方身份、权限与共享授权尚未实现。停用影响新的检查请求，已经通过检查的请求可能完成；健康状态为最近一次验证结果。当前逐次同步检查，Foundation不可用时查询会拒绝，尚未提供缓存或离线授权机制。
