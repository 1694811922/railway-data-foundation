# 共享关系与访问策略接口

2026-10-17范围修正：接口共享关系的创建、编辑及审批推进只支持REST_API，非REST返回400且不写入部分策略/关系。Kafka请使用独立订阅API；存量非REST记录保留读取、历史、退回/撤回等停止操作，不自动迁移。管理员页面统一为“共享授权→REST接口”，访问策略和调用测试位于授权详情。后端原策略只读API保留。见[入口精简说明](SHARING_GOVERNANCE_SIMPLIFICATION.md)。

## 提供方确认补充（2026-10-13）

新有归属申请必须“需求方提交 → 提供方确认 → 管理员最终审批”。新增 `provider_review_mode` 和 `provider_confirmed_at`，确认后保持PENDING并增加版本，历史使用真实登录身份。LEGACY存量与ADMIN_MANAGED无归属管理员流程不补造确认。

REST 动作路径：`POST /api/v1/relationships/{id}/provider-confirm`、`POST /api/v1/relationships/{id}/provider-return`；Kafka 动作路径：`POST /api/v1/kafka-subscriptions/{id}/provider-confirm`、`POST /api/v1/kafka-subscriptions/{id}/provider-return`。请求示例：

```json
{"version":2,"reason":"已核对共享用途和范围","content_acknowledged":true}
```

仅所属提供方且来源归属相符可调用，其他提供方、需求方及管理员403，未登录401。确认必须勾选，退回必须原因；旧版本409，业务状态错误400。确认/版本/历史同事务，退回及重新提交清除旧确认。管理员审批及原实际授权执行增加确认门槛，外部凭据和策略/权限执行机制保持原规则。详见[协议与约束](PROVIDER_CONFIRMATION_DESIGN.md)、[操作与验证](PROVIDER_CONFIRMATION_CHANGELOG.md)。


本文件说明V0.3关系与策略登记、管理员确认和历史记录。Foundation只保存治理元数据。当前REST运行时已接入调用凭据、访问决策与Provider字段/时间限制，见[访问接口](ACCESS_API.md)。`ACTIVE`是访问检查的必要条件，还需有效调用凭据及可用能力；不是匿名访问许可，也不代表Kafka或CDC已执行策略。

## 数据模型

| 表 | 用途 |
|---|---|
| `sharing_subject` | 提供方、需求方业务主体目录（V7） |
| `sharing_relationship` | 双方主体、已发布资源、具体能力、用途、授权有效期及状态（V8） |
| `access_policy` | 每条关系专属的允许字段、时间字段和数据时间范围（V8） |
| `sharing_relationship_event` | 登记、编辑和状态变更的操作人标签、时间、原因与版本（V8） |

关系与策略编号为`REL_`、`POL_`加32位随机标识；编号不是认证凭据。每条关系独立持有一条策略，不跨关系复用。所有时间由接口按带时区ISO 8601传入，精度最多毫秒；数据库按UTC保存，前端按本地时间显示。区间均含起点、不含终点。

## 接口

基础路径`/api/v1`，统一响应`{code,message,data}`。以下列表返回数组，`limit`默认50、范围1–200；`offset`默认0、范围0–1,000,000。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/relationships?limit=50&offset=0` | 按更新时间、编号倒序读取关系 |
| GET | `/relationships/{id}` | 关系详情，包含双方名称、资源/能力名称和策略 |
| POST | `/relationships` | 同一事务创建草稿关系、专属策略与登记历史 |
| PUT | `/relationships/{id}` | 编辑草稿的名称、用途、两个时间区间和允许字段 |
| POST | `/relationships/{id}/submit` | 草稿提交确认 |
| POST | `/relationships/{id}/confirm` | 待确认关系确认通过 |
| POST | `/relationships/{id}/return` | 待确认关系退回草稿 |
| POST | `/relationships/{id}/enable` | 已确认或暂停关系启用登记 |
| POST | `/relationships/{id}/suspend` | 暂停已启用登记 |
| POST | `/relationships/{id}/revoke` | 撤回关系，终态不可再启用 |
| GET | `/relationships/{id}/events` | 按版本升序读取全部关系操作历史 |
| GET | `/access-policies?limit=50&offset=0` | 只读策略目录 |
| GET | `/access-policies/{id}` | 只读策略详情 |

无独立策略写入或关系物理删除接口，避免绕过关系确认与历史记录。创建、更新、状态操作成功均返回完整当前关系。

## 创建请求

```json
{
  "relationship_name": "集装箱事件科研共享",
  "provider_subject_id": "SUB_实际提供方编号",
  "demand_subject_id": "SUB_实际需求方编号",
  "resource_id": "RES_实际资源编号",
  "capability_id": "CAP_该资源下的能力编号",
  "purpose": "铁路物流科研分析",
  "valid_from": "2026-10-01T00:00:00Z",
  "valid_until": "2026-11-01T00:00:00Z",
  "policy": {
    "allowed_fields": ["container_no", "event_time"],
    "time_field": "event_time",
    "data_from": "2026-01-01T00:00:00Z",
    "data_until": "2026-10-01T00:00:00Z"
  },
  "reason": "登记科研共享条件"
}
```

示例编号须替换为实际编号；有效期须按当前时间调整。关系名称必填、最多120字；用途、原因必填、最多1000字。

校验条件：

- 双方必须不同，分别为已启用的提供方和需求方。
- 资源已发布、来源启用；能力确实属于该资源且未退役。
- 允许字段非空、无重复、最多200项，字段必须来自当前资源；不接受`*`。
- 时间字段必须来自该资源，人工标记为时间字段，且类型为DATE、DATETIME或TIMESTAMP。
- 两个区间起点均早于终点；授权到期时间必须晚于当前时间。
- 授权有效期决定“何时可访问”，数据时间范围决定“可访问哪段数据”，两者独立。
- 不接受客户端传入状态、操作者或其他未知属性。

新增关系允许先绑定草稿能力，但启用登记前必须完成能力验证与启用。此阶段只有REST能力允许启用关系登记；Kafka、CDC与只读模板仍缺少执行侧权限机制，不能宣称已支持。

## 更新和状态流转

PUT仅允许`DRAFT`，请求包含名称、用途、两个时间区间、`policy`、当前`version`与`reason`，不再提交或修改双方主体、资源、能力。编辑后策略版本与关系版本各加1；确认记录清空。

所有状态操作请求：

```json
{"version": 1, "reason": "人工核对共享范围后提交"}
```

| 当前状态 | 允许操作 | 结果状态 |
|---|---|---|
| DRAFT 草稿 | submit / revoke | PENDING / REVOKED |
| PENDING 待确认 | confirm / return / revoke | CONFIRMED / DRAFT / REVOKED |
| CONFIRMED 已确认 | enable / revoke | ACTIVE / REVOKED |
| ACTIVE 登记已启用 | suspend / revoke | SUSPENDED / REVOKED |
| SUSPENDED 已暂停 | enable / revoke | ACTIVE / REVOKED |
| REVOKED 已撤回 | 无 | 保留历史 |

启用须有确认时间、授权已经生效且未到期；能力为REST、`ENABLED/UP`，双方、资源、来源、字段均重新校验。退回后可调整并重新确认；确认后策略冻结，变更条件需撤回并重新登记。已到期或引用对象停用的关系仍可暂停或撤回，避免无法收回登记。

每次编辑或状态操作要求当前版本，旧版本拒绝。同一事务保存新状态和操作历史；并发使用相同版本时只允许一个操作成功。当前操作者固定由服务端记录为“原型管理员”，尚未接入服务端管理员身份认证，客户端不能覆盖。

共享关系引用的主体与能力禁止删除；已发布资源原有删除限制继续生效。停用主体、能力和来源不会自动改写关系状态，下一阶段的运行时访问校验须同时检查这些条件。

## 前端入口

- 共享治理 → 共享关系管理：真实登记、草稿编辑、检索、状态操作、策略和历史抽屉。
- 共享治理 → 访问策略：只读检索与详情，修改须进入共享关系草稿。
- 列表有界读取最多5000项，10/20/50项展示分页；范围不足显示提示。
- 两页仅使用真实API；显式演示模式显示说明并停止真实读取与写入，不植入模拟授权。
- 失败保留表单，提交时锁定输入；不自动重发结果未确认的写入，不切为mock成功。

见[V0.3开发计划](V03_DEVELOPMENT_PLAN.md)。下一阶段完成可信身份、默认拒绝决策和Provider执行，才可验收实际共享授权。
