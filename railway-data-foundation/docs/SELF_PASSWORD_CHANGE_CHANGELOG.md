# 本人登录密码修改交付

日期：2026-10-10。三角色工作台后续小阶段，复用V14账号、Spring Security会话和BCrypt；不新增数据库表或迁移，不改变共享审批、外部REST凭据或Kafka身份。

## 使用

管理员、提供方、需求方均可在顶部点击“修改密码”。输入当前密码、新密码及确认新密码；新密码至少12个Unicode字符，UTF-8最多72字节，不能与当前密码相同。成功后回到登录页，必须使用新密码登录。其他浏览器旧会话在下次请求时失效。

失败、关闭及组件卸载都会清空密码。真实失败不显示演示成功；网络中断时结果可能未确认，应重新登录核对，不自动重复提交。演示模式禁用入口并在API层拒绝真实改密。忘记密码仍由管理员重置，没有自助找回。

## 后端与前端文件

| 文件 | 作用 |
|---|---|
| `security/AuthController.java` | POST `/api/v1/auth/password`，脱敏请求对象；成功销毁会话、清除Cookie、no-store响应 |
| `security/AccountService.java` | 从真实身份确定本人账号，核验当前密码及新密码、BCrypt保存、版本并发保护 |
| `security/WorkspaceGate.java` | 三个已登录角色均可使用本人改密；不开放账号管理权限 |
| `src/components/PasswordChange.vue` | 共用中文弹窗、二次确认、提交锁定、失败及关闭清空，成功重新登录 |
| `src/utils/passwordChange.js` | 前端按Unicode字符及UTF-8字节校验，不截断密码 |
| `src/api/auth.js`、`src/layout/MainLayout.vue` | 真实改密请求、顶部入口、身份变化时卸载弹窗 |
| `WorkspaceRoleIntegrationTest.java` | 三角色、双会话、旧密码拒绝、新密码登录、CSRF/伪造编号与外部凭据隔离 |
| `scripts/password-change.test.mjs`、`scripts/role-workspaces-live.mjs` | 字符/字节/确认校验及真实浏览器改密流程 |

接口只接受 `current_password,new_password`，身份、目标账号和主体从会话确定；传入他方用户编号等未知字段返回400。当前密码错误/新密码不合规返回400且不修改账号。未登录401、CSRF错误403；并发版本变化409或已失效会话401，不覆盖管理员重置结果。

成功只更新本人workspace_account.password_hash/version/updated_at，账号名、角色、主体、资源及外部凭据不变；使用原BCrypt12单向哈希，不回显密码/哈希。管理会话、REST调用令牌和Kafka订阅身份仍是独立机制。

## 验证

- 完整后端153项（Foundation123、Provider30），0失败/错误/跳过；工作区 `.runtime/password-backend-final.log`。包括原扫描/资源/REST授权/Kafka执行回归及新增3项改密集成测试。
- 前端124项通过，npm run build成功；`.runtime/password-web-final.log`、`password-build-final.log`。
- 真实Edge三角色流程通过，并额外验证错误当前密码、取消/失败清空、本人改密、所有旧会话失效、新密码重新登录和无浏览器密码持久存储；`.runtime/password-browser-final.log`、`role-browser.log`。
- 独立演示原扫描→资源→能力流程通过；`.runtime/password-demo-final.log`。
- API直接验证三个角色均只能修改本人；匿名、CSRF缺失、伪造目标编号、短密码、相同密码、超过72字节中文及不足12字符的表情密码均拒绝。错误请求不改变账号记录。
- 在独立测试账号的REST凭据和Kafka申请已存在时改密，核对这些外部授权记录完整保持不变。没有将管理登录密码当作访问凭据。

测试沿用foundation_test的独立随机账号/主体/来源并清理，不修改原container_event业务数据。没有Git提交或推送。提供方确认、归属转移、自助找回/绑定变更、SSO、消费计量及CDC仍未在本阶段实现。
