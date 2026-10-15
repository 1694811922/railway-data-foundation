# V0.3 调用身份与共享访问接口

V14权限补充：管理端已实现真实会话登录、三角色/主体范围和CSRF；访问校验仍用原Provider服务间凭据及需求方Bearer调用令牌，与管理端Cookie独立。普通账号不能审批或使用他方凭据。外部调用说明现需Bearer加关系编号，见 [认证接口](WORKSPACE_AUTH_API.md)；下文带日期说明保留历史。

2026-10-09补充：[V0.5外部REST接入规范](DEMAND_REST_INTEGRATION.md)提供独立可执行调用方示例，复用本页接口；分页、真实允许/拒绝/暂停/撤销与提供侧自动计量已验证，不新增访问接口或改变授权语义。

## 1. 调用流程

```text
管理员登记需求方、确认并启用共享关系
    ↓
向该需求方签发独立调用令牌
    ↓
调用方携带令牌、关系编号和能力编号请求Provider
    ↓
Provider向Foundation请求治理决策
    ↓
拒绝：不查询业务行；允许：按字段与时间范围直接读取Source
```

共享主体编号不是可信身份。调用令牌绑定需求方，调用时不接受自报主体编号。数据库连接密码、需求方调用令牌、Provider服务间凭据是三种不同用途的凭据。

## 2. 调用凭据管理

Foundation统一返回`{code,message,data}`，成功`code=0`。

| 方法与路径 | 用途 |
|---|---|
| `GET /api/v1/caller-credentials` | 分页读取凭据元数据；参数`subjectId`可选，`limit`为1–200，`offset`为0–1000000 |
| `GET /api/v1/caller-credentials/{id}` | 查看名称、需求方、状态、到期时间和版本，不返回令牌或摘要 |
| `GET /api/v1/caller-credentials/{id}/events` | 按版本读取签发、撤销历史 |
| `POST /api/v1/subjects/{id}/credentials` | 向已启用需求方签发令牌，仅返回本次签发结果 |
| `POST /api/v1/caller-credentials/{id}/revoke` | 携带当前版本及原因撤销；撤销不可恢复 |

签发请求示例：

```json
{
  "credential_name": "科研分析调用凭据",
  "expires_at": "2026-11-01T00:00:00Z",
  "reason": "授权科研分析使用"
}
```

日期须晚于签发时间，最多毫秒精度。名称必填、最多120字，在同一需求方下唯一；原因必填、最多1000字。仅`DEMAND/ENABLED`主体可签发。

成功响应的`data`包含`credential`元数据及一次性`token`。令牌以`rdf_`开头，包含256位随机材料；数据库只存SHA-256摘要。签发响应设置`Cache-Control: no-store`。关闭前端签发弹窗后令牌清除，后续不能通过详情查询；丢失时撤销原凭据并重新签发。请求结果不确定时先刷新目录检查，不能自动重发。

撤销请求：

```json
{"version":1,"reason":"结束授权使用"}
```

过期令牌即使元数据状态仍显示`ENABLED`，运行时也不可用。凭据变更与历史同一事务保存；被凭据引用的主体不能物理删除，保留历史标识。

## 3. 内部访问决策

`POST /api/v1/access/check`由Provider调用，不承载业务数据。

必带请求头：

```http
X-Provider-Token: 运维配置的服务间凭据
Authorization: Bearer 需求方调用令牌
```

请求体：

```json
{
  "relationship_id": "REL_实际编号",
  "capability_id": "CAP_实际编号",
  "requested_fields": ["container_no", "event_time"],
  "filter_fields": [],
  "from": "2026-09-20T00:10:00Z",
  "to": "2026-09-20T00:20:00Z"
}
```

只接受关系、能力、字段名称和时间条件，不接受业务行、过滤参数值、自报主体或自定义SQL。

检查条件：

- 调用凭据有效、未撤销、未到期，其需求方已启用。
- 关系属于该需求方及能力，双方已启用，关系已确认且为`ACTIVE`，处于授权有效期内。
- 资源、能力、来源状态有效，能力属于共享资源，Provider来源一致。
- 策略字段仍存在，时间字段仍由人工标记为日期或时间类型。
- 请求返回字段及筛选字段均属于允许集合；数据时间范围位于策略范围内。

省略`requested_fields`使用策略允许字段；显式空数组、重复字段或通配符拒绝。时间起止可分别省略，省略部分使用策略对应边界；最终范围必须合法且不超出策略。采用含起点、不含终点的范围。

通过时`data.allowed=true`，返回明确字段、时间范围、来源绑定、版本与决策时刻；不返回连接密码或业务记录。正常业务拒绝为HTTP200、`allowed=false`；服务间凭据缺失或错误为403，配置无效为503。Provider将拒绝转换为调用方的401或403。

| 决策原因编号 | 含义 |
|---|---|
| `IDENTITY_INVALID` | 调用凭据无效、到期、撤销或需求方不可用 |
| `RELATIONSHIP_UNAVAILABLE` | 关系、能力或需求方不匹配 |
| `SUBJECT_UNAVAILABLE` | 参与主体停用 |
| `RELATIONSHIP_INACTIVE` | 未确认启用、已暂停或已撤回 |
| `RELATIONSHIP_EXPIRED` | 不在授权有效期内或请求检查期间到期 |
| `POLICY_UNAVAILABLE` | 资源字段或策略时间条件不可用 |
| `FIELDS_FORBIDDEN` | 返回字段或筛选字段越权 |
| `TIME_FORBIDDEN` | 请求时间超出策略范围 |
| `CAPABILITY_UNAVAILABLE` | 能力、资源或来源不可用 |

每次请求读取一致的治理状态，不缓存允许结果。关系暂停、撤回或凭据撤销提交后，下一次检查拒绝；已经通过检查的在途请求可能完成。

## 4. Provider查询

`GET http://127.0.0.1:18081/containers/events`

必须同时携带：

```http
Authorization: Bearer 调用令牌
X-Relationship-Id: REL_实际编号
X-Capability-Id: CAP_实际编号
```

| 参数 | 说明 |
|---|---|
| `fields` | 可选，以逗号分隔请求字段；省略时使用策略允许字段 |
| `from`、`to` | 可选，ISO时间且必须带`Z`或时区偏移，最多毫秒精度 |
| `containerNo`、`trainNo`、`eventType` | 固定查询条件，涉及字段也须被策略允许 |
| `limit` | 1–100，默认20 |
| `offset` | 0–100000，默认0 |

源库时间按`PROVIDER_SOURCE_TIME_OFFSET`解释，默认`+08:00`。例如`2026-09-20T00:10:00Z`对应源库`2026-09-20 08:10:00`，返回时间包含偏移。治理有效期按UTC保存，与数据时间范围分开校验。

查询使用固定字段白名单及参数绑定；只投影允许字段，禁止把完整行先返回前端再隐藏列。业务记录通过Provider直接返回调用方，不经过Foundation。缺少有效编号为400，身份无效为401，范围或状态拒绝为403，控制服务故障或决策不完整为503；拒绝时不读取业务行。

## 5. 页面与运行配置

“共享治理 → 调用凭据”：签发、查看历史、撤销。令牌仅留在签发弹窗内存，不自动写剪贴板或浏览器存储。

“共享治理 → 共享调用验证”：选择关系，手动填入令牌，按允许字段或明确请求字段查询；查看HTTP状态及业务结果。可以选择暂停、撤回关系来核对拒绝结果。更改输入、刷新、切换关系或离开页面会清除旧结果；离开页面清除令牌。显式mock模式不发送调用请求或模拟授权成功。

前端`VITE_PROVIDER_BASE_URL=/provider`，开发服务器将其转发至18081；`/api`仍转发至18080。部署时需要分别配置提供服务和底座管理接口的反向代理。

Foundation与Provider共享`PROVIDER_CONTROL_TOKEN`，仅由运维环境提供，本机脚本自动生成并保存在被Git忽略的`.env.local`。不要配置成`VITE_`前端变量。追加V9迁移创建`caller_credential`和`caller_credential_event`，原有迁移不修改。

## 6. 当前范围

- 先支持固定`container_event` REST查询，不自动生成其他来源的业务接口。
- 能力登记中的`auth_type=NONE`描述当前原始适配器配置；V0.3共享查询仍强制要求调用令牌，不能匿名GET业务数据。
- HEAD探测不返回业务行，仍可用于能力验证。
- Kafka生产触发接口仅接受运维服务凭据；普通需求方REST令牌不授予Kafka权限。Topic消费授权和CDC访问控制另行实现。
- 管理接口已在V14增加服务端会话登录、角色/主体权限及CSRF；调用凭据不能替代管理端身份系统。

设计依据见[访问运行时设计](ACCESS_RUNTIME_DESIGN.md)，阶段验收见[V0.3开发计划](V03_DEVELOPMENT_PLAN.md)。
