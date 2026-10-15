# 2026-10-13 提供方数据发送工作台交付

提供方已可从同一个前端读取本主体资源绑定的源记录，单条或勾选批量发送到Kafka；需求方继续通过独立接收服务落库查询。前端无需运维密码、Kafka生产账号或数据库密码。

## 主要文件

| 文件 | 作用 |
|---|---|
| `foundation-service/.../capability/ProducerContextController.java` | 登录态生产准入与真实操作身份 |
| `foundation-service/.../security/WorkspaceGate.java` | 提供方专用入口、归属校验；管理员不能冒充提供方 |
| `provider-service/.../ProducerWorkspaceGuard.java` | 固定底座地址核验会话与CSRF，来源/主题一致性检查 |
| `provider-service/.../ProductionController.java` | 直接查询源记录及逐条发送，技术日志记录真实操作人 |
| `provider-service/.../ContainerEventService.java` | 同一只读事务内分页与匹配总数 |
| `provider-service/.../RuntimeTelemetryFilter.java` | 新发送接口接入原生产技术摘要，不采集消息正文 |
| `foundation-web/src/views/production/ProductionView.vue` | 真实查询、筛选分页、详情、单条/批量、取消后续项及结果 |
| `foundation-web/src/api/production.js`、`src/utils/production.js` | 独立提供方请求、回执校验、批量顺序与错误分类 |
| `foundation-web/src/utils/workspace.js`、`src/router/index.js`、能力页面 | 提供方菜单/路由保护及指定能力发送入口 |

API和启动方式见[操作指南](PROVIDER_PRODUCTION_GUIDE.md)。底座未新增数据库表，仍V17；源仍V2；需求方仍独立V1。不改变现有账号密码、主体归属或共享权限，不提交推送Git。

## 本轮验证结果

- 完整后端reactor：Foundation145项，其中两个显式浏览器测试在专项另行启用并通过；Provider36项全部通过；Demand6项可靠性通过，独立数据库专项16项全部通过。去重计数共197项不同主模块测试通过。
- 前端 `npm test`：157项通过；`npm run build`通过。保留现有大于500kB包大小提示，不是构建错误。
- 真实多角色/注册浏览器专项通过。新增提供方生产浏览器读取实际20条源记录，单条1次及批量2次发送全部取得真实Kafka回执。读取故障清空/重试、未确认发送不报成功也通过；注入503只用于UI异常校验，不计真实发送。
- 原生Kafka完整链路通过：初始运维生产1条，加本轮真实页面生产3条，需求方实际落库4条；源内容匹配、退出登录持续接收、服务重启、数据库故障不提前提交位点、恢复无重复、持久摘要补报、主体隔离和CSRF均通过。
- 原REST允许/拒绝、提供方确认、Kafka实际权限撤回及需求方阻塞均通过。原Source20行和完整摘要不变，临时登记、账号、消息与故障约束已清理；Windows测试主题保留，未在线删除。
- 真实生产日志有登录账号编号、账号名称、能力、追踪编号和结果，无业务消息正文或凭据。底座现有技术摘要没有新增操作人列，不冒充已有数据库审计功能。
- 本机Foundation/Provider新包已启动，Kafka/MySQL/需求方继续运行。首次替换运行中jar遇到Windows文件锁；仅停止Foundation/Provider后重新打包成功并启动，未停用或重新初始化数据库和Kafka。底座重启使旧登录会话失效，原密码不变。

## 证据位置

本輪全部真实链路与清理证据：`.runtime/kafka-chain-12681513989252956646/`，含 `production-ui-verification.json`、`receiver-verification.json`、`cleanup.json`、`provider-real-production.png`、`receiver-real-data.png` 和真实操作身份日志。该目录被Git忽略；本文与使用指南在正常 `docs` 目录。

工作区 `.runtime/production-backend-test.log`、`production-demand-test.log`、`production-roles-test.log`、`production-frontend-test.log`、`production-build.log`、`production-chain.log` 保存验证输出。

## 限制

只适用于当前已部署的container_event生产适配器及固定主题。单次批量最多50条，浏览器逐条执行，不是后台生产任务。失败暂停后续项，取消不撤销在途发送；超时不自动重试。Kafka确认不代表需求方落库，重复发送形成不同Kafka位置，需求方不按业务编号去重。没有CDC、持续增量采集、任意源表映射或端到端恰好一次。

测试使用独立主题，没有向用户现有 `port.container.event.v1` 自动发送数据；正式任务数量是否增长仍取决于提供方明确发送。
