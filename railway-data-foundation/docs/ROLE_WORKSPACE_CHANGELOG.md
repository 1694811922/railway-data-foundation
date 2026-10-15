# 三角色统一工作台交付记录

日期：2026-10-10。检查依据为当前源码、Controller/SQL、V1–V13迁移、前端路由及实际运行情况，随后追加V14；没有仅依据历史完成标记。改造方案和接口权限表见 [方案](ROLE_WORKSPACE_DESIGN.md)，登录合约见 [接口](WORKSPACE_AUTH_API.md)，启动及演示见 [使用指南](ROLE_WORKSPACE_GUIDE.md)。

## 完成内容

保留单个foundation-web，共用布局、公共组件、Axios及路由；真实登录后展示管理员、数据提供方或数据需求方首页/菜单，顶部显示用户、角色和单位。新增安全账号管理，普通账号绑定已有sharing_subject；没有角色切换入口或复制三套工程。

服务端使用Spring Security会话登录、BCrypt12密码哈希、CSRF和退出处理；未登录401，越权403。每次请求重新核对账号版本和主体状态，停用/重置后旧会话失效。前端身份与CSRF仅保存在内存，401/退出/身份变化清空状态并卸载含凭据页面，晚到响应不能覆盖新身份。账号创建/改密表单关闭和卸载清空密码。

归属只在来源保存，扫描→来源、资源→来源、能力→资源继承。提供方新登记来源自动归属登录主体，不能接管无归属存量，也不能写后台环境配置引用。管理员新增来源可显式选提供方或留空。来源已有归属不可在编辑中转移。资源/能力响应按来源派生归属，不增加重复且易失配的归属列。

服务端按角色限制所有现有接口组，普通角色未知入口默认拒绝；列表SQL和分页总数在数据库中同步按主体过滤，详情/字段/扫描/版本/比较/写入及请求体关联编号校验归属。提供方仅查看本主体分享记录及本来源运行摘要；需求方只能处理自己的申请/凭据，公开目录只含公开已发布且来源启用资源，以及已启用/UP能力，不暴露来源连接账号或后台运行配置。当前没有导出接口。

需求方可提交自己的REST和Kafka整主题申请，不能确认/退回/启用/配置/重试授权。提供方确认环节现有后端不存在，维持只读共享记录；最终审批及Broker配置仍由管理员完成。新共享、凭据、Kafka历史及资源快照记录真实登录账号/用户编号，旧操作人和旧快照原样保留。

原REST调用凭据与Kafka订阅身份独立于工作台登录。Provider用原服务间令牌取得运行决策；外部REST调用说明现在使用调用方Bearer加关系编号，不需要管理员Cookie或Provider运维令牌。Provider采集状态改为运维认证，管理页面从Foundation管理员接口读取固定路径白名单摘要；没有业务数据代理。

## 主要文件

| 文件/目录 | 作用 |
|---|---|
| `foundation-service/.../security/` | SecurityConfiguration认证与CSRF、AccountService/Controller账号、AuthController会话、WorkspaceGate角色/编号检查、Actor查询范围与历史身份、InitialAdministrator首次初始化 |
| `db/migration/V14__workspace_identity_ownership.sql` | 追加账号表、来源提供方外键、真实操作人字段，中文注释；不自动分配旧来源、不重写历史 |
| Source/Scan/Resource/Capability/Subject/Relationship/Subscription/CallerCredential/RuntimeTelemetry服务 | 列表/总数范围、归属校验、申请主体绑定及真实操作人 |
| `telemetry/CollectorStatusController.java` | 管理员读取提供侧采集状态，不向浏览器交付运维凭据 |
| Provider CapabilityRuntimeGuard、RuntimeTelemetryStatusController | 保持服务间认证、保护运维状态 |
| `foundation-web/src/utils/sessionState.js,workspace.js,request.js` | 内存身份、角色菜单/操作、CSRF与真实/演示隔离、旧响应隔离 |
| `src/api/auth.js`、`router/index.js`、`layout/MainLayout.vue`、LoginView | 真实会话登录、恢复/退出、共用布局及路由权限 |
| WorkspaceHome/DemandHome、WorkspaceResourceDetail/PublicResourceDetail、AccountView | 角色首页、需求方只读安全详情、管理员账号管理 |
| Source/ResourceList/Relationship/Subscription/Credential/Metric页面 | 保留原管理功能，提供方归属、需求方本人申请/一次性凭据和只读记录 |
| `examples/rest-demand/client.mjs` | 已有外部客户端使用本人的调用令牌取得调用说明 |
| `scripts/initialize-admin.ps1` | 安全输入首次管理员密码，进程临时环境传入，结束清理，不写通用密码 |
| AuthenticatedApiTest、WorkspaceRoleIntegrationTest、role-workspaces-live.mjs | 现有集成测试迁移真实认证，独立多主体越权/分页/真实浏览器验证 |
| KafkaFoundationLiveTest及两项Kafka浏览器脚本 | 真实登录、需求方领取/停止、独立Broker消费撤权、两服务REST兼容回归 |

## 验证结果

| 验证 | 结果/证据 |
|---|---|
| 完整后端 | Foundation120 + Provider30 =150通过，0失败/错误/跳过；工作区 `.runtime/role-backend-verified.log` |
| 三角色权限与真实浏览器 | 9项角色集成测试包含真实Edge三账号；登录、菜单、提供方扫描、需求方公开详情及REST/Kafka申请、管理员确认、本人一次性REST凭据、账号停用后的清理均通过；无成功响应拦截；最后追加过期Cookie退出清理也通过，`.runtime/role-browser-expired-logout.log` |
| 前端测试/构建 | npm test122项通过、npm run build成功；`.runtime/role-web-final.log`、`role-build-final.log` |
| 显式演示 | 原扫描→资源→能力流程真实浏览器通过，仅本地演示；`.runtime/role-demo-final.log` |
| 独立Kafka权限 | 13项通过；默认命令跳过1项显式完整链路，该项已单独运行通过；`.runtime/role-kafka-auth-verified.log` |
| 两服务/真实SCRAM Broker/浏览器 | 完整链路1项通过；需求方账号一次性领取，真实读取1条消息、停止后拒绝；精确主题/消费组、独立身份、依赖失效与撤权补偿保留；`.runtime/role-kafka-rest-verified.log` |
| 原REST外部授权 | 同一真实两服务回归：需求方签发自己的令牌；无管理Cookie外部调用说明/业务查询200，允许字段投影、撤销401、暂停403、恢复200、底座停机503；客户端16项通过，`.runtime/role-rest-client-verified.log` |
| 安全首次初始化 | 独立foundation_test运行已打包JAR的initialize-only模式，哈希管理员创建且进程退出，随后清理该账号；`.runtime/role-initializer-verified.log` |
| 本机部署与存量 | Foundation/Provider重新打包启动，health均code0；开发库V14，两个来源归属仍为空、账号0，不替用户设置管理员密码；未登录sources实际401，5173实际200，原port_source_db.container_event仍20条 |

后端原有扫描、资源快照绑定/并发/结构比较/版本历史、能力、REST共享与Kafka执行测试均包含在150项回归。权限测试涵盖提供方A/B直接编号隔离、无归属拒绝、需求方外部主体伪造拒绝、分页总数、私有资源/后台配置拒绝、账号停用/主体停用、CSRF/退出及真实操作人。

测试使用随机主体和账号，在foundation_test及foundation_kafka_live_test隔离元数据中运行；临时登记/主体/账号/授权及临时服务/Broker已清理。没有修改原container_event业务数据。Broker测试专用日志/索引与分区规模缩小以适应本机磁盘，不改变部署配置或权限语义。无Git提交或推送。

## 限制与后续

本轮完成角色与权限改造，不扩展CDC、消费计量、多集群或需求方业务服务。仍未实现提供方确认流程、账号自助找回/改密/绑定变更、存量归属转移、SSO、多角色账号、结构更新/版本恢复。单进程会话重启失效，显式演示只提供独立演示管理员，不模拟真实三角色认证。

工作台账号停用不自动撤销外部REST或Kafka身份；外部授权按已有主体/共享关系/凭据和Broker规则执行。控制服务停机期间静态Broker权限可能保留，撤回/停止须核对“已撤权”，不能只看审批状态。常驻Kafka未配置，默认执行关闭。

历史依赖前端假登录的可选UI脚本不再代表当前权限验收；当前真实多角色入口见使用指南。没有将历史原型操作人追认为真实用户。本轮完成后等待用户验收，不自行进入下一阶段。
