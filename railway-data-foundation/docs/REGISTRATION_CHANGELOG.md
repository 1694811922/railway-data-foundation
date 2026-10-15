# 工作台注册交付记录

日期：2026-10-12。用户要求注册前后端，基于现有Spring Security会话和账号主体体系实现，未提交或推送Git。

## 已实现

- 登录页新增“没有账号？注册账号”，统一前端 `/register` 中文表单。
- 本机无管理员时，可显式开启首任管理员初始化；要求服务绑定及请求来源为回环地址，已有任何管理员后自动关闭。并发保护覆盖网页、初始化脚本及账号创建；没有默认密码。
- 普通用户仅申请提供方/需求方，提交单位文字而非自行绑定主体。待审核不能登录；管理员在“用户账号 → 注册申请”核定已有启用同角色主体，审核通过才创建账号。拒绝必须有原因。
- BCrypt单向哈希，CSRF继续有效；申请审核后清空申请中的密码哈希。审核和账号创建同事务，真实审核人、版本冲突和角色权限校验。匿名查询/审批不开放，普通账号不能读取审核列表或审批。
- 表单提交保护、未知结果不自动重发、密码关闭/提交后清空、读取失败提示和重试、旧响应隔离。修复无登录身份的初始401误触发跳回登录，已有身份失效仍清空并退出。
- V16追加申请表及并发保护表，中文字段注释；复用workspace_account及sharing_subject，原主体和无归属来源不重新分配。REST/Kafka凭据机制不改。

## 主要文件

| 文件 | 作用 |
|---|---|
| foundation-service/.../security/RegistrationController.java | 注册配置、普通申请、首任管理员及管理员审核接口 |
| foundation-service/.../security/RegistrationService.java | 本机初始化限制、哈希、事务和审核主体检查 |
| foundation-service/.../security/AccountService.java、InitialAdministrator.java | 统一账号登记锁及脚本初始化竞争保护 |
| foundation-service/.../security/SecurityConfiguration.java、WorkspaceGate.java | 最小公开注册路径，保留CSRF和现有权限 |
| foundation-service/.../db/migration/V16__workspace_registration.sql | 追加注册表和保护行 |
| foundation-web/src/views/RegisterView.vue、LoginView.vue | 注册页面及登录入口 |
| foundation-web/src/components/RegistrationReview.vue | 管理员申请列表与审核对话框 |
| foundation-web/src/api/registration.js、utils/registration.js | 注册请求、表单校验和匿名路由判断 |
| foundation-web/src/router/index.js、utils/request.js | 注册路由及匿名401不错误跳转 |
| scripts/start-local.ps1、stop-local.ps1、.env.example | 本机新配置初始化开关及已记录前端进程停止 |

当前本机 `.env.local` 只追加初始化开关，未写登录密码；不提交本机配置。远程部署/反向代理必须关闭此开关。

## 验证结果

- 完整后端137+30=167项，失败/错误/跳过均0；含注册5项、原有三角色真实浏览器。日志：工作区 `.runtime/registration-backend-full.log`。
- 注册HTTP测试覆盖首任创建/关闭/禁用/非回环拒绝、并发首任唯一、待审核禁止登录、伪造角色/主体拒绝、CSRF、重复申请、角色错配主体、管理员审批/拒绝、哈希移交/清空、版本冲突及匿名/普通用户权限。
- 真实浏览器完成首任管理员 → 提供方申请 → 管理员审核绑定 → 提供方登录及禁止管理员接口；演示注册页面不请求真实API。早期失败（公开路径拦截、匿名跳转、测试选择器及请求统计）已修正；以全量最终结果为准，不把失败轮次计为通过。
- `npm test` 132项，失败/跳过0；`npm run build`通过。原有大于500kB包体警告仍存在，不属于构建失败。
- 后端打包通过，本机Foundation/Provider重启，MySQL V16迁移成功；5173注册页面实际可见“初始化管理员”。开发环境账号及注册申请0，等待用户自行注册；测试库账号、申请及主体0。
- 原 `port_source_db.container_event` 仍20行，来源仍2个且均无归属，未改业务数据。

## 如何使用与限制

打开 `http://127.0.0.1:5173/register`，填写管理员账号、名称、自设密码及确认密码，成功后返回登录。普通注册需要管理员核实单位后审核；没有对应主体先登记单位。不支持匿名查看审批详情、通知、自助找回、拒绝后原申请重提。不是公网无审核注册，公开部署前还需HTTPS、入口限流与防滥用。注册不签发外部REST令牌或Kafka身份。

完整接口：[REGISTRATION_API.md](REGISTRATION_API.md)。使用：[ROLE_WORKSPACE_GUIDE.md](ROLE_WORKSPACE_GUIDE.md)。
