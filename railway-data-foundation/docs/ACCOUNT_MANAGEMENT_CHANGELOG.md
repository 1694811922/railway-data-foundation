# 管理员账号工作台完善

日期：2026-10-10。范围是既有三角色账号管理的可用性完善，不扩展业务审批流程。数据库仍V14，没有追加迁移、默认账号或公共密码。

## 改动及主要文件

| 文件 | 作用 |
|---|---|
| foundation-service/.../security/AccountService.java | 管理员账号目录和主体候选真实分页/总数；只读可重复读事务；列表只选安全列；统一Unicode密码规则 |
| foundation-service/.../security/AccountController.java | 追加catalog和subject-options只读接口；旧列表兼容 |
| foundation-web/src/api/account.js | 独立账号请求封装，显式演示禁止真实操作 |
| foundation-web/src/utils/account.js | 登记/密码校验、独立读取序号与旧响应隔离 |
| foundation-web/src/views/subject/AccountView.vue | 检索筛选、分页、远程主体候选和加载更多；新建/启停/重置、错误重试、敏感表单清空与提交保护 |
| scripts/initialize-admin.ps1 | 初始密码提示与Unicode字符计数同步后端，无密码回显或落盘 |
| foundation-service/.../WorkspaceRoleIntegrationTest.java | 超过200条记录、总数筛选、字面通配符、权限、密码边界、版本冲突与清理 |
| foundation-web/scripts/account.test.mjs | Unicode/字节边界与读取失败、旧响应、关闭失效测试 |
| foundation-web/scripts/role-workspaces-live.mjs | 真实多角色浏览器增加账号分页、主体加载、新建、启停、取消清空、重置和冲突验证 |
| foundation-web/scripts/smoke.mjs | 显式演示账号页面禁止真实读写回归 |

## 使用方式与权限

管理员在“共享治理 → 用户账号”检索和维护。提供方、需求方没有这个入口，直接请求也返回403；未登录401。新接口`/api/v1/accounts/catalog`及`/subject-options`返回统一响应中的`{items,total}`。两次查询共用相同筛选及同一读取快照；账号、主体均可继续翻页，没有前200项截断。

主体候选只返回启用且角色匹配的主体编号、名称和单位。普通账号创建时由后端重新验证主体，不能凭旧下拉结果绕过停用限制。账号角色及绑定仍不可变，存量来源归属不调整。历史账号不重写密码；新建、重置及本人改密统一要求至少12个Unicode字符、UTF-8最多72字节。

页面读取失败清空旧列表，支持重试；快速换页/切换角色/关闭弹窗或退出页面时旧读取不能覆盖新状态。写入期间锁定提交和关闭操作。取消、失败、退出清空密码；409后只允许关闭刷新，未知结果不允许直接重发。不把密码写入浏览器存储。当前管理员通过顶部入口修改本人密码，避免误停用自己。

## 验证记录

完整后端156项（Foundation126、Provider30，失败/错误/跳过均0）、前端127项、npm run build及真实三角色浏览器、显式演示回归通过；本机Foundation已重新打包更新，Provider与MySQL复用。专项已验证205账号分页及筛选、201主体候选、Unicode密码限制、普通角色403/未登录401、旧列表兼容。真实浏览器已验证30条记录翻页、主体第二页、新建、启停、取消清空、密码重置与旧会话失效、版本冲突拒绝；503仅作为读取失败用例，成功操作均直接请求真实测试后端。浏览器翻页发现请求开始清零总数导致页码跳回，已修复并通过回归。构建仍有既有Element Plus主包超过500kB的非阻断警告。

测试只使用独立测试库及随机账号/主体，自动清理；没有修改原container_event业务数据。原外部REST和Kafka认证机制不变，本轮不把账号重置当作外部凭据撤销；真实Broker完整链路未重复运行，既有证据见ROLE_WORKSPACE_CHANGELOG.md。

## 保留限制

没有账号删除、绑定变更、自助找回、单点登录、提供方确认或来源归属转移；没有扩展消费计量、CDC、多集群或需求方业务服务。分页事务保证单次响应内部一致，不保证不同请求间并发新增账号后永久保持同一分页快照。没有提交或推送Git。
