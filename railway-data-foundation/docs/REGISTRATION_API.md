# 工作台账号注册接口

注册复用 Spring Security 会话、CSRF、BCrypt 和已有 `sharing_subject`。管理登录账号与 REST 调用令牌、Kafka 身份互不替代。统一响应为 `{code,message,data}`。

## 首次管理员

`GET /api/v1/auth/registration-options` 返回 `bootstrap_available`，不返回账号列表。仅在配置 `FOUNDATION_REGISTRATION_LOCAL_BOOTSTRAP_ENABLED=true`、服务绑定回环地址、请求直接来源为回环地址且没有任何管理员时可用；停用的管理员也算已有管理员。默认关闭；本机新建配置显式开启。远程部署或经过反向代理必须关闭，使用安全初始化脚本。

`POST /api/v1/auth/register-admin` 接收 `username,display_name,password`，服务端固定 ADMIN，不接收角色或主体编号。成功返回无密码的账号信息，不自动登录。后续请求返回409。数据库保护行串行化网页初始化、脚本初始化和账号创建，避免两个首任管理员。

## 普通用户申请

`POST /api/v1/auth/register` 接收：

| 字段 | 含义 |
|---|---|
| username | 3–64位登录账号，字母开头，可含字母、数字、点、下划线和短横线 |
| display_name | 最多120字的显示名称 |
| role | 仅 PROVIDER（提供方）或 DEMAND（需求方） |
| organization_name | 最多120字的申请单位；不是已认证主体归属 |
| password | 至少12个Unicode字符、最多72个UTF-8字节 |

所有POST需先通过 `/auth/csrf` 取得同会话CSRF头，Cookie仍为HttpOnly/SameSite。未知JSON字段（包括自行传入的`subject_id`、操作人）拒绝。成功仅返回 `registration_id,status=PENDING`，待审核用户没有工作台账号，不能登录。不自动登记新主体，不授予数据访问权。

V16追加 `workspace_registration`（申请）及 `workspace_registration_guard`（并发保护）。申请只保存BCrypt哈希；批准时转入既有账号表并清空申请哈希，拒绝时清空哈希。既有表、存量账号/主体/来源不重分配。

## 管理员审核

- `GET /api/v1/registrations?status=PENDING&limit=20&offset=0`：管理员分页，返回 `items,total`；状态还可为APPROVED、REJECTED。普通用户403，未登录401；不输出密码或哈希。
- `POST /api/v1/registrations/{id}/review`：`version,approve,subject_id,reason`。通过必须选择启用且类型与申请角色匹配的已有主体；拒绝必须有原因且不能带主体。审核人取真实登录身份。新建账号及审核记录同事务，重复/过期处理409；未知申请404。

审核通过后使用注册时的原密码登录。申请页面的单位文字不直接授予权限；管理员应线下核实身份，并选定正确主体。管理员既有账号创建、停用和改密流程继续使用原接口。

## 当前限制

无邮箱/短信验证、通知、自助找回密码、匿名查询审核详情或拒绝后的自助重提；拒绝后可换账号重新申请或联系管理员。正式开放公网前需关闭首次管理员网页初始化，并配置HTTPS和入口限流/防滥用。显式演示模式禁止真实注册请求；真实请求失败不回退演示成功。提交结果未知时不得自动重发。
