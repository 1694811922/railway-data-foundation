# Provider REST：集装箱作业事件

V14兼容补充：Provider的业务查询仍采用原Bearer/关系/能力校验，不依赖管理端Cookie；运行核对携带原服务间X-Provider-Token。GET /api/v1/provider/telemetry仅运维认证可读，前端管理员经Foundation的GET /api/v1/runtime-collector读取白名单摘要。运维令牌不放VITE变量。

2026-10-09外部接入示例已完成：见[接入指南](DEMAND_REST_INTEGRATION.md)。在VS Code终端使用 `scripts/run-demand-rest-example.ps1` 安全输入需求方令牌，独立分页读取当前接口并通过追踪编号核对提供侧审计；不改变本页接口或新增需求方服务。

Provider独立运行，默认监听`127.0.0.1:18081`。使用`PROVIDER_SOURCE_DB_URL/USER/PASSWORD`直连Source；本机启动脚本建立`provider_reader`只读账号，随机凭据在被忽略的`.env.local`。V0.3 REST查询先携带需求方令牌及共享关系向`PROVIDER_FOUNDATION_BASE_URL`请求访问决策，再按允许字段和时间范围读取Source。Provider不连接`foundation_db`，业务行只返回给调用者。

同一 Provider 也支持从 Source 向 Kafka 生产指定事件，见 [Provider Kafka](PROVIDER_KAFKA_API.md)。

前端“接口能力 → 查看 → 调用方式”显示真实提供服务地址、完整接口路径、必带请求头及可复制的PowerShell命令，来自底座只读`GET /api/v1/capabilities/{id}/invocation`。此说明复用服务端`PROVIDER_REST_BASE_URL`，不发送探测、不读取业务行，也不会自动启用能力；先验证并启用后再调用。详见[调用说明接口](CAPABILITY_API.md)。

| 方法与路径 | 作用 |
|---|---|
| `GET /api/v1/provider/health` | Provider 进程存活，不代表 Source 可读 |
| `HEAD /containers/events` | 无需能力编号；检查Source表的SELECT权限与可达性，成功204及Provider/运行时版本/来源标识，不返回业务行 |
| `GET /containers/events` | 同时携带Authorization、X-Relationship-Id、X-Capability-Id；授权通过后按策略直接查询container_event |

GET支持可选`fields`（逗号分隔）、`containerNo`、`trainNo`、`eventType`、`from`、`to`、`limit`、`offset`。字段缺省使用策略允许字段，筛选条件涉及的字段也须获授权。时间采用带`Z`或时区偏移的ISO格式，最多毫秒精度，`from`含、`to`不含；缺省使用策略时间边界。源库时间按`PROVIDER_SOURCE_TIME_OFFSET`解释，默认`+08:00`。`limit`为1–100（默认20），`offset`为0–100000。按策略时间字段降序、id降序稳定排序。参数使用JDBC绑定，不接收自定义SQL或物理表名。

本机示例：

```powershell
# 先验证启用能力，确认启用共享关系，再向其需求方签发调用凭据。
$capId = 'CAP_替换为实际编号'
$relationshipId = 'REL_替换为实际编号'
$callerToken = '替换为签发时获取的调用令牌'
Invoke-RestMethod 'http://127.0.0.1:18081/containers/events?limit=10' `
  -Headers @{ 'X-Capability-Id' = $capId; 'X-Relationship-Id' = $relationshipId; Authorization = "Bearer $callerToken" }
```

能力或关系编号缺失/格式无效返回400；调用凭据无效、到期或撤销返回401；关系未启用、已暂停/撤回、字段或时间越权、能力/来源不可用返回403；底座不可达、决策不完整或Source查询失败返回安全的503。拒绝的请求不会查询业务行。`PROVIDER_FOUNDATION_BASE_URL`默认`http://127.0.0.1:18080`，只接受运维配置的HTTP/HTTPS主机，不接受客户端目标地址或重定向。连接超时2秒，请求超时3秒，每次GET重新检查，无允许决策缓存。两服务共享环境配置`PROVIDER_CONTROL_TOKEN`，不得放入前端变量。

HEAD返回`X-Railway-Provider: container-event-v1`、`X-Railway-Runtime: rest-capability-v1`、`X-Railway-Access: sharing-access-v1`及`X-Railway-Source-Host/Port/Database/Table`。HEAD不返回业务行、不依赖能力或关系启用状态，停用后仍可验证连接。动态登记的Source如指向同一主机、端口、数据库和container_event表也可登记此能力；Provider仍只服务其配置的一个来源，不自动生成其他数据库的业务接口。

现有20条事件来自`sql/source/V2__demo_events.sql`，是模拟业务数据。业务查询访问Provider的18081端口；Foundation只返回治理决策。关系暂停/撤回、凭据撤销提交后拒绝新的检查请求，已经通过检查的请求可能完成。能力健康状态为最近一次验证结果。能力可用不等于已授权；旧的仅携带能力编号的GET不再适用。管理端仍是本机原型，没有服务端管理员登录。完整凭据接口与流程见[访问接口](ACCESS_API.md)。
