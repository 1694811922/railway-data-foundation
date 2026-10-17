# 铁路统一数据底座虚拟接入与共享治理原型系统

2026-10-17：管理员共享治理已收敛为“共享授权（REST接口/Kafka订阅）”和“REST调用凭据”，主体/账号移至系统管理，策略及调用测试位于授权详情。本机已更新，请使用原账号重新登录。253项不同后端、前端194项/build及真实收发回归通过；未提交推送。见[操作说明](railway-data-foundation/docs/SHARING_GOVERNANCE_SIMPLIFICATION.md)。

2026-10-17：资源下架、重新发布和退役已完成，本机服务已更新，重新登录后在资源目录或详情使用。下架保留审批但暂停共享，重新发布需管理员手动恢复。246项不同后端测试、前端190项/build及真实Kafka链路通过。见[使用指南](railway-data-foundation/docs/RESOURCE_LIFECYCLE_GUIDE.md)。未提交推送Git，未部署云端。

2026-10-12：注册前后端已完成。本机打开 http://127.0.0.1:5173/register 自设首任管理员账号和密码；普通注册由管理员核定共享主体后登录。完整167项后端、132项前端及构建、真实浏览器通过。见 [注册使用指南](railway-data-foundation/docs/ROLE_WORKSPACE_GUIDE.md) 和 [交付记录](railway-data-foundation/docs/REGISTRATION_CHANGELOG.md)。未提交推送Git。以下为历史。

2026-10-11 本轮最新：Kafka正常启动改为只读核对，连续两次重启的原凭据、消息、权限与消费进度验证通过；首次初始化及配置异常拒绝通过。完整后端162项、前端129项及构建、真实Kafka共享与三角色浏览器回归通过。历史认证失败根因未知，Windows主题删除仍受限，稳定部署未完成。使用见[本地实验指南](railway-data-foundation/docs/LOCAL_KAFKA_EXPERIMENT_GUIDE.md)，结果见[重启完善记录](railway-data-foundation/docs/KAFKA_RESTART_HARDENING_CHANGELOG.md)。以下为历史。

2026-10-11 最新：按0924任务内容1新增原生Kafka本地实验启动、真实共享链路联调及持续/突发/间歇传输实验工具。后端162个不同测试、前端129项与构建、三角色浏览器及原生完整链路通过；三场景各180条均接收，无错误或重复。Windows在线主题删除及重启订阅身份恢复验收失败，稳定部署尚未完成，Linux环境待确定。操作见[本地实验指南](railway-data-foundation/docs/LOCAL_KAFKA_EXPERIMENT_GUIDE.md)，正式研究待办见[接口实验计划](railway-data-foundation/docs/INTERFACE_EXPERIMENT_PLAN.md)。没有提交或推送Git。以下为历史。

2026-10-10 最新：统一前端三个角色工作台及Kafka消费观察已实现，开发库V15。订阅详情可查看外部自报条次、载荷字节和报告；未上报未知，不保存消息内容。后端按模块162项、前端129项及构建、真实Kafka/多角色/演示验证通过。启动与安全账号初始化见 [三角色指南](railway-data-foundation/docs/ROLE_WORKSPACE_GUIDE.md)，本轮内容及限制见 [消费观察交付](railway-data-foundation/docs/KAFKA_CONSUMPTION_CHANGELOG.md)。以下为历史。

2026-10-10：Kafka整主题订阅执行已交付，支持专属身份、精确读取权限、一次性凭据及停止/到期撤权。141项后端、119项前端、构建及真实Broker/两服务/浏览器验证通过。开发库V13；本机无常驻Kafka、执行默认关闭，配置和操作见[订阅执行指南](railway-data-foundation/docs/KAFKA_SUBSCRIPTION_EXECUTION.md)，消费计量与CDC仍待后续。

当前工程位于 [railway-data-foundation/](railway-data-foundation/README.md)。使用Java 17、Spring Boot 3、MyBatis Plus和MySQL 8；已完成动态数据源接入、扫描与资源发布、字段维护、能力登记、REST验证与启停、Kafka生产及元数据验证，并提供Vue 3中文前端。V0.3新增共享主体、关系/策略确认、调用凭据与REST访问校验；Provider执行允许字段和时间范围，底座不传输业务行。见[访问接口](railway-data-foundation/docs/ACCESS_API.md)与[开发计划](railway-data-foundation/docs/V03_DEVELOPMENT_PLAN.md)。

- [启动与开发说明](railway-data-foundation/README.md)
- [实现进度](railway-data-foundation/docs/IMPLEMENTATION_STATUS.md)
- [测试记录](railway-data-foundation/docs/TESTING.md)
- [模块说明与使用指南](railway-data-foundation/docs/SYSTEM_MODULE_GUIDE.md)
- [资源结构只读比较：使用与交付](railway-data-foundation/docs/SCHEMA_COMPARISON_CHANGELOG.md)
- [资源版本历史：使用与交付](railway-data-foundation/docs/RESOURCE_HISTORY_CHANGELOG.md)
- [V0.4运行摘要接口](railway-data-foundation/docs/RUNTIME_TELEMETRY_API.md)：单Provider自动采集、补报及中文审计/追踪/计量已联调。
- [运行监管交付记录](railway-data-foundation/docs/RUNTIME_TELEMETRY_CHANGELOG.md)：真实采集、测试证据及后续范围。

旧版 Java 21 / JPA / H2 工程、前端、脚本、文档与缓存已清理。旧 H2 数据及其配套凭据归档在 `.legacy-backup/`，仅用于保留历史数据，新版不会加载；该目录已被 Git 忽略。`.runtime/m2/` 为新版仍使用的 Maven 依赖缓存。
