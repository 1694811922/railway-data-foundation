# 统一工作台身份与权限接口

## 提供方确认补充（2026-10-13）

新有归属申请必须“需求方提交 → 提供方确认 → 管理员最终审批”。新增 `provider_review_mode` 和 `provider_confirmed_at`，确认后保持PENDING并增加版本，历史使用真实登录身份。LEGACY存量与ADMIN_MANAGED无归属管理员流程不补造确认。

REST 动作路径：`POST /api/v1/relationships/{id}/provider-confirm`、`POST /api/v1/relationships/{id}/provider-return`；Kafka 动作路径：`POST /api/v1/kafka-subscriptions/{id}/provider-confirm`、`POST /api/v1/kafka-subscriptions/{id}/provider-return`。请求示例：

```json
{"version":2,"reason":"已核对共享用途和范围","content_acknowledged":true}
```

仅所属提供方且来源归属相符可调用，其他提供方、需求方及管理员403，未登录401。确认必须勾选，退回必须原因；旧版本409，业务状态错误400。确认/版本/历史同事务，退回及重新提交清除旧确认。管理员审批及原实际授权执行增加确认门槛，外部凭据和策略/权限执行机制保持原规则。详见[协议与约束](PROVIDER_CONFIRMATION_DESIGN.md)、[操作与验证](PROVIDER_CONFIRMATION_CHANGELOG.md)。


更新：2026-10-10。适用 V14 迁移后的服务。权限矩阵见 [改造方案](ROLE_WORKSPACE_DESIGN.md)，操作见 [三角色使用指南](ROLE_WORKSPACE_GUIDE.md)。

## 登录会话

管理端采用 Spring Security 6.5 会话认证，密码使用带 `{bcrypt}` 标记的 BCrypt 单向哈希，工作因子为12。账号存于 `workspace_account`，普通账号关联一个已有的、启用且角色匹配的 `sharing_subject`。管理员主体为空。

所有响应继续使用 `{code,message,data}`；未登录或会话失效返回 HTTP401/code10101，无权限返回 HTTP403/code10102。登录错误不区分账号不存在、密码错误或主体停用。身份由服务端会话确定，角色、主体编号和历史操作人不能由请求伪造。

| 方法与地址（前缀 `/api/v1`） | 请求/响应 | 权限 |
|---|---|---|
| GET `/auth/csrf` | `data:{token,header_name}`，同时建立会话Cookie | 可未登录访问 |
| POST `/auth/login` | `application/x-www-form-urlencoded`：`username,password`；携带上一步CSRF头 | 校验真实账号 |
| GET `/auth/me` | 当前用户、角色、单位与主体的安全信息 | 已登录 |
| POST `/auth/logout` | 携带CSRF头，销毁会话并删除Cookie | 当前会话 |
| POST `/auth/password` | JSON：`current_password,new_password`；携带当前CSRF头 | 已登录用户修改本人密码 |

登录成功返回 `user_id,username,display_name,role,subject_id,subject_name,department,version,enabled`，不返回密码哈希。登录成功会更换会话编号，CSRF令牌也需要重新读取。会话空闲30分钟失效；Cookie设置HttpOnly、SameSite=Strict。本机HTTP允许非Secure Cookie，HTTPS部署应设置服务端 `FOUNDATION_SESSION_COOKIE_SECURE=true`。

写接口必须携带当前CSRF头；缺失或失效返回401/403，不做跨站宽松授权。Vite `/api` 同源代理供本机使用；生产环境应由反向代理保持前后端同源。浏览器只在内存保存当前身份和CSRF值，不在localStorage/sessionStorage存真实身份、密码或访问令牌。退出、401或身份变更会清空状态并卸载敏感页面。每次请求都会校验账号版本及关联主体状态；停用账号、重置密码或停用主体会使原会话下次请求失效。

## 本人修改登录密码

三个角色均可调用POST `/auth/password`，只能操作服务端认证身份对应账号，不接受用户编号、角色、主体或操作人参数。必须验证当前密码；新密码至少12个Unicode字符、UTF-8不超过72字节，不能与当前密码相同，不截断或自动去除空格。密码使用原BCrypt编码器保存。

当前密码错误、新密码不符合要求或未知请求字段返回400，账号和会话不改变；账号并发变化返回409或会话失效401，不覆盖管理员重置结果。更新SQL校验认证账号版本及启用状态。成功返回 `{code:0,message,data:null}`，设置 `Cache-Control:no-store`，销毁当前会话并删除JSESSIONID；其他旧会话在下次请求时因版本不匹配返回401。必须使用新密码重新登录。

该操作只更新workspace_account的password_hash/version/updated_at，不变更角色、主体、资源、共享审批、REST调用凭据或Kafka订阅身份。前端提供重复确认，失败/关闭/卸载清空密码；修改结果未确认时提示重新登录核对，不自动重试。显式演示模式禁用入口且禁止真实请求。不提供找回密码或修改他人密码，管理员原重置接口保留。

## 管理账号

仅管理员可访问，账号创建后不支持修改账号名、角色或关联主体，也没有普通用户角色切换入口。

| 方法与地址 | 请求 | 响应 |
|---|---|---|
| GET `/accounts?limit=50&offset=0` | limit1–200、offset0–1000000 | 安全账号数组，按创建时间/编号排序 |
| GET `/accounts/catalog` | `keyword,role,enabled,limit=20,offset=0` | `{items,total}`，管理员真实分页 |
| GET `/accounts/subject-options` | `role,keyword,limit=20,offset=0` | `{items,total}`，仅同角色已启用主体的编号、名称、单位 |
| POST `/accounts` | `username,display_name,role,subject_id,password` | 新账号安全信息 |
| POST `/accounts/{id}/status` | `version,enabled` | null；版本变化使旧会话失效 |
| POST `/accounts/{id}/password` | `version,password` | null；旧会话失效 |

`username` 为3–64位、以英文字母开头，允许字母/数字/下划线/点/短横线。显示名称1–120字符。创建、管理员重置和本人修改的密码均至少12个Unicode字符，UTF-8编码不超过72字节；没有默认密码。角色只能为ADMIN、PROVIDER、DEMAND；普通账号只能绑定同类型启用主体。管理员不能停用当前自己的账号。版本冲突返回409，不自动重试。

账号目录查询支持登录账号、用户名称、主体名称和单位的包含检索，忽略大小写，`%`和`_`按普通字符匹配；role可空或ADMIN/PROVIDER/DEMAND，enabled省略或true/false。keyword最多120字符，limit1–200，offset0–1000000，非法参数400。筛选与总数共用SQL条件，同一只读可重复读事务读取总数和本页；按创建时间、编号倒序。旧GET `/accounts`继续返回数组并保留原升序，供既有客户端兼容。列表SQL只选择安全列，不读取密码哈希。

主体候选必须指定PROVIDER或DEMAND角色；仅返回该类型启用主体，可按名称、业务编码或单位搜索，按名称、编号升序。同样有真实分页与总数，不读取连接配置或凭据。分页读取后主体被停用时，创建账号仍在服务端重新核对，不信任候选快照。两项新接口未登录401、普通角色403。

管理员首次初始化由 `scripts/initialize-admin.ps1` 安全提示密码，临时放入子进程环境后立即清除；已有管理员时不覆盖、不重置。脚本不写通用密码或初始化密码到仓库/SQL/环境文件。账号列表不支持删除；绑定主体及已拥有来源的主体禁止被删除，保留关联完整性。

## 数据归属与查询范围

只在 `source_system.provider_subject_id` 保存提供方归属。扫描继承来源，资源继承来源，能力继承资源；资源响应中的 `provider_subject_id` 是查询派生值，不是重复存储字段。

提供方新增来源时服务端填写登录主体；传入别的主体会被拒绝。管理员新增来源可选择启用提供方或留空；已有来源的归属不可通过编辑接管/转移。提供方不能访问无归属记录，不能使用后台环境配置引用。创建资源校验实际来源和登记扫描，创建能力校验资源归属。

需求方目录仅展示安全级别“公开”、已发布、来源启用的资源，能力还需已启用且最近健康状态为UP。公开元数据不代表授权业务访问；需求方不能读取数据源账号、扫描快照、历史版本或运行配置。需求方提交申请时，需求主体必须等于登录主体，提供主体必须等于资源实际归属。无归属来源不能由需求方任意指定提供方申请。

列表SQL和分页总数使用同一范围条件。详情、嵌套字段/历史、写操作及请求体关联编号均检查；即使猜中其他主体的编号也返回403。当前没有导出接口；未来普通角色新增接口必须显式加入权限规则，默认拒绝。新历史保存真实账号和用户编号，旧历史不改写。

## 与外部访问认证的关系

工作台Cookie不能代替REST调用令牌或Kafka订阅身份。

| 技术接口 | 独立校验 |
|---|---|
| POST `/access/check` | 原Provider控制凭据及需求方Bearer校验；无工作台Cookie依赖 |
| POST `/telemetry/reports` | 原Provider控制凭据；摘要不包含业务明细 |
| GET `/capabilities/{id}/runtime`、`kafka-runtime` | 提供服务携带服务端 `X-Provider-Token`；工作台管理员/归属提供方按会话访问 |
| GET `/capabilities/{id}/invocation` | 工作台会话范围，或外部Bearer加`X-Relationship-Id`核对有效启用关系；调用方不持有Provider运维令牌 |
| GET `/runtime-collector` | 仅管理员；底座用服务端运维凭据读取提供侧固定路径的采集状态白名单 |

Provider的采集运维状态不能被普通浏览器直接匿名访问。前端没有Provider管理员令牌或Kafka运维密码。停用管理账号仅使管理会话失效，不自动撤销已有外部访问凭据；外部权限仍按主体/关系/策略、凭据状态和Broker执行机制控制。
