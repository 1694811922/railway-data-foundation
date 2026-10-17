# 铁路统一数据底座虚拟接入与共享治理原型系统

2026-10-17最新：资源生命周期已完成（底座V20）：草稿发布/版本保护删除、已发布下架、已下架重新发布或不可逆退役；同事务记录真实操作人及原因、暂停REST及持久Kafka撤权意图。保留原审批，重新发布不会恢复旧授权，管理员须手动启用REST/重新配置Kafka，需求方更新凭据后恢复已有任务。管理员全平台、提供方仅本主体、无归属仍仅管理员管理。246项不同主模块测试（165+36+45）、前端190项及build、16项部署配置测试、真实三角色浏览器及实际Broker撤权/新身份恢复通过。原源20条不变、独立登记与身份清理；本机新包已启动，原私有配置未变，需重新登录。源码部署包已更新，云端仍未实际部署验收；无Git提交推送。旧文档中“发布/删除不要求版本”“退役待开发”由本条替代。见[生命周期使用指南](docs/RESOURCE_LIFECYCLE_GUIDE.md)、[主要文件与验收](docs/RESOURCE_LIFECYCLE_CHANGELOG.md)。

2026-10-16最新：管理员监管已合并为单页单表“传输监测”，REST接口与Kafka消息统一筛选、分页及查看详情；四张统计卡替代独立计量页。新增批次入库事务提交耗时，底座V19、需求方V4，旧记录未知不回填。完整主模块235项不同测试、前端185项及build、真实三角色收发落库/耗时/故障重试/撤权与旧地址兼容通过。本机运行包已更新，原源20条不变，重启后需重新登录。供需双方原记录权限保留，未提交推送；历史多菜单描述由本条替代。见[使用指南](docs/TRANSFER_MONITOR_GUIDE.md)、[文件与验证记录](docs/UNIFIED_TRANSFER_MONITOR_CHANGELOG.md)。

2026-10-16最新：Kafka监测已完整归口管理员，独立监管页包含发送日志、接收过程及订阅消费摘要；提供方改为“数据发送→发送记录”，需求方改为指定任务“任务日志”。底座V18、需求方V3，独立服务技术凭据、同事务监测补报、去重/冲突拒绝/退避隔离及已有真实日志回填已完成。231项不同主模块测试、前端179项及build、16项Linux配置测试、真实三角色收发落库/撤权后事件补报与REST回归通过。本机已更新，原源20条不变，已有4条过程日志补报；重启后需重新登录。原配置与密钥保留，未提交推送；云端运行仍待目标服务器验收。此前“三角色共用独立监测页”由本条替代，以下日期条目为历史。见[本轮交付](docs/KAFKA_MONITOR_ROLE_CHANGELOG.md)、[监测指南](docs/KAFKA_TRANSFER_MONITOR_GUIDE.md)。

2026-10-16：Kafka传输监测已补齐：三角色共用监测页，发送回执/消费摘要及需求方持久过程日志分开呈现；独立需求方V2，落库/重读/位置提交/补报/状态操作可查询。主模块221项不同测试（完整运行加独立需求方及显式角色/注册补验）、前端177/build、真实三角色Kafka收发落库/故障恢复/撤权/浏览器及演示隔离通过。原源20条不变，临时登记清理，本机需求方新包及5173已运行；底座仍V17。旧过程不补造，未确认位置不伪装成功，提供侧采集缺口不宣称全量审计。未提交推送；见[监测指南](docs/KAFKA_TRANSFER_MONITOR_GUIDE.md)、[交付记录](docs/KAFKA_TRANSFER_MONITOR_CHANGELOG.md)。以下日期条目为历史。

2026-10-16：已补充[百度云Ubuntu24.04 Docker部署步骤](docs/BAIDU_CLOUD_DOCKER_DEPLOYMENT.md)及`deploy/linux`配套文件。部署管理12项、Compose官方Schema、Shell及Java健康探测检查和前端构建通过；本机无Docker Engine，镜像构建、容器运行与云端收发验收仍待在目标服务器执行。交付材料不代表已上线，不改现有Windows运行方式。见[部署准备记录](docs/CLOUD_DEPLOYMENT_PREPARATION_CHANGELOG.md)。

2026-10-14最新：Kafka订阅与接收任务联动已完成。已有任务直接查看数据，无任务才检查授权并创建；通用创建表单也先查关联，读取失败不当作无任务。支持撤权后本主体历史入口、重试及旧身份/响应隔离；精确授权筛选的列表和总数同时按主体限定。215项不同主模块测试、前端171项/build、真实订阅入口/撤权历史及原接收下载/REST/Kafka回归通过。本机需求方新包及5173已运行，原源20条不变、临时登记清理；库仍V17/V2/需求方V1，未提交推送。见[联动使用指南](docs/RECEIVING_SUBSCRIPTION_LINK_GUIDE.md)、[交付记录](docs/RECEIVING_SUBSCRIPTION_LINK_CHANGELOG.md)。以下为历史。

2026-10-14最新：需求方接收数据下载与导出已完成。单条原始JSON/文本/二进制、完整消息JSON、筛选JSONL及取消下载；仅本主体可用，文件准备后重验登录，固定快照最多10000条/50MiB，错误/截断不报成功，文件直接由需求方服务提供。213项主模块测试、前端165/build及真实浏览器文件核对/取消/撤权后历史下载/原授权回归通过；原源20条不变，临时登记清理、服务临时文件0，新需求方包及5173已运行。库仍V17/V2/独立V1，无Excel映射/后台导出/CDC，未提交推送。见[下载指南](docs/RECEIVING_DOWNLOAD_GUIDE.md)、[交付](docs/RECEIVING_DOWNLOAD_CHANGELOG.md)。以下为历史。

2026-10-14最新：需求方新增只读接收进度面板，按本主体授权核验实际Kafka保留范围、消费组位置和数据库检查点，明确无可用消息/未建立/待接收/追平/越界，未知不补0；不消费或改任务。205项主模块测试、前端161/build及真实三角色/注册/发送落库/只读进度/旧响应隔离/撤权回归通过，原源20条不变、临时登记清理。需求方新包与5173前端已启动。库仍V17/V2/独立V1，无CDC/后台生产/计费，未提交推送。见[进度使用指南](docs/RECEIVING_PROGRESS_GUIDE.md)、[交付](docs/RECEIVING_PROGRESS_CHANGELOG.md)。以下为历史。

2026-10-13最新：提供方数据发送工作台已完成。真实登录/主体归属/CSRF核验后，通过独立Provider通道查询源记录、单条及最多50条勾选发送；显示真实回执、取消后续项和待确认结果，不自动重试。197项不同主模块测试、前端157/build、多角色/注册及真实浏览器发送3条并累计落库4条、故障恢复/撤权回归通过；原源20行不变，临时登记清理。本机服务已更新，旧会话需重新登录；库仍V17/V2/需求方V1，无CDC/后台生产/通用映射，不提交推送。见[发送使用指南](docs/PROVIDER_PRODUCTION_GUIDE.md)和[交付记录](docs/PROVIDER_PRODUCTION_CHANGELOG.md)。以下为历史。

2026-10-13：按用户明确授权新增独立需求方真实数据接收服务（18082）及需求方数据接收工作台。Kafka消息直接落入独立 demand_receiver_db，底座不接收业务消息。持续任务、事务后提交位置、幂等落库、重启恢复、主体隔离、凭据加密及技术补报已实现。主模块190项不同测试、前端149/build、真实三角色/注册及实际Kafka两条落库/故障恢复/撤权/浏览器通过；原源20行不变，临时登记清理。底座仍V17，需求方独立V1，无CDC/业务映射/高可用，不提交推送。本条为最新，以下为历史。见[本轮交付](docs/DEMAND_RECEIVING_CHANGELOG.md)与[使用指南](docs/DEMAND_RECEIVING_GUIDE.md)。

2026-10-13：新增REST/Kafka提供方确认共享内容。需求方提交后，提供方在“共享申请与记录 → REST共享记录 / Kafka订阅记录 → 内容确认”核对、确认或退回，管理员随后最终审批并按原规则启用/配置权限。存量不补造确认，无归属不分配给提供方。完整后端172、前端142/build及真实三角色/实际REST和Kafka回归通过；开发V17、本地服务已更新，重新登录使用。见[本轮交付与截图](docs/PROVIDER_CONFIRMATION_CHANGELOG.md)。未提交推送；以下为历史。

2026-10-12 Kafka模拟流程：新增VS Code“演示：Kafka 模拟数据完整流程”及 run-kafka-simulation.ps1，真实Provider/Kafka/外部消费20条6396字节、位点0–19，审批/授权/消费摘要/撤权与REST兼容通过；默认一条320字节链路另行回归。示例16项、前端132/build和工具边界通过。独立登记清理、原源完整摘要不变，主库仍V16/2个无归属来源；Windows保留测试主题，非稳定部署/压测。见 [模拟指南](docs/KAFKA_SIMULATION_GUIDE.md)、[交付](docs/KAFKA_SIMULATION_CHANGELOG.md)。产品后端未改，无新增迁移/需求方服务/CDC，不提交推送。以下为历史。

2026-10-12：账号注册前后端已完成。登录页进入注册；本机首任管理员自设密码，普通提供方/需求方注册经管理员核定共享主体后才能登录。V16追加迁移，完整后端167、前端132/build及注册/原三角色真实浏览器通过；本机已更新，正式账号0，等待用户自行创建。见[注册交付](docs/REGISTRATION_CHANGELOG.md)、[使用指南](docs/ROLE_WORKSPACE_GUIDE.md)。未提交或推送Git。以下为历史。

2026-10-11 本轮最新：本地Kafka连续两次重启已使用原凭据验证消息、权限与消费进度保留，正常启动不重写账号；首次初始化、错密码/缺权限/缺配置拒绝也通过。完整后端162项含三角色浏览器、前端129项及构建、原生真实共享链路回归通过。历史认证失败根因仍未知，Windows在线删主题问题尚未解决，仍仅用于本机实验。操作见[本地指南](docs/LOCAL_KAFKA_EXPERIMENT_GUIDE.md)，证据及限制见[重启完善交付](docs/KAFKA_RESTART_HARDENING_CHANGELOG.md)。本轮没有业务模块、数据库或前端页面扩展，未提交或推送Git。以下为历史。

2026-10-11 最新：按研究内容1增加无Docker的原生Kafka实验启动与三类传输工具，入口`./scripts/start-local.ps1 -Kafka`和`./scripts/run-interface-lab.ps1`。原生集群真实平台链路、三角色及后端162/前端129/build验证通过；场景工具六行各180条接收成功。**Windows主题删除及重启订阅身份恢复存在已验证失败，稳定部署尚未完成；REST对照是独立夹具，不是业务性能结论。** Linux环境选择待确认。见 [部署与实验指南](docs/LOCAL_KAFKA_EXPERIMENT_GUIDE.md)、[实验计划](docs/INTERFACE_EXPERIMENT_PLAN.md)、[完整结果及失败限制](docs/LOCAL_KAFKA_EXPERIMENT_CHANGELOG.md)。以下为历史。

2026-10-10 最新：Kafka订阅详情新增“消费观察”，展示外部订阅程序自报的消息条次、载荷字节及报告列表；未上报显示未知。一次性领取同时返回独立上报令牌，不保存消息内容，不替代Kafka权限或登录身份。后端按模块162项、前端129项、构建、真实Kafka消费与三角色/演示浏览器验证通过；开发库V15，前后端已恢复。进入“我的Kafka订阅 / Kafka订阅授权 → 查看 → 消费观察”。见 [本轮交付与限制](docs/KAFKA_CONSUMPTION_CHANGELOG.md)、[接口](docs/KAFKA_CONSUMPTION_API.md)、[外部示例](examples/kafka-demand/README.md)。没有常驻Kafka、持久补报或完整消费计费；以下为历史。

2026-10-10 最新补充：管理员“用户账号”支持真实检索、角色/状态筛选和分页，关联主体可检索并加载更多；新建、启停、重置密码及冲突/失败保护已补齐。156项后端、127项前端、构建、真实三角色浏览器及演示通过，V14不变。本机Foundation已更新。见 [账号完善交付](docs/ACCOUNT_MANAGEMENT_CHANGELOG.md)、[使用指南](docs/ROLE_WORKSPACE_GUIDE.md)。以下为历史。

2026-10-10 最新补充：三个角色已支持顶部“修改密码”，须验证当前密码，成功后重新登录，其他旧会话失效；外部REST/Kafka凭据不变。153项后端、124项前端、构建及真实浏览器/演示回归通过，数据库仍V14。见 [改密交付](docs/SELF_PASSWORD_CHANGE_CHANGELOG.md)、[三角色指南](docs/ROLE_WORKSPACE_GUIDE.md)。以下为各阶段历史。

2026-10-10 最新：一个前端、三个真实角色工作台已交付。管理员管理全平台；提供方管理本来源数据并只读相关共享；需求方浏览公开目录、提交本人REST/Kafka申请并领取本人凭据。服务端Spring Security会话/BCrypt/CSRF及列表、总数、详情/写入归属检查，V14追加迁移，存量来源归属保持空。150项后端、122项前端、构建、真实三账号浏览器、外部REST和真实Kafka回归通过；本机两服务已更新。首次管理员密码自行安全初始化，见 [三角色启动与使用](docs/ROLE_WORKSPACE_GUIDE.md)、[接口](docs/WORKSPACE_AUTH_API.md)、[交付及限制](docs/ROLE_WORKSPACE_CHANGELOG.md)。以下日期记录保留历史，以本条为准。

2026-10-10 最新：Kafka整主题订阅实际权限执行已完成。入口“共享治理 → Kafka 订阅授权”，审批后配置专属身份、领取一次性凭据、停止/撤回；到期或治理条件失效异步撤权。141项后端、119项前端、构建、真实SCRAM Broker/两服务/浏览器及实际消费允许与撤权拒绝、原审批/演示回归通过。本地Foundation更新V13，原源20条不变。没有常驻Kafka，执行默认关闭，配置方法见[使用指南](docs/KAFKA_SUBSCRIPTION_EXECUTION.md)，验证及限制见[交付](docs/KAFKA_SUBSCRIPTION_EXECUTION_CHANGELOG.md)。消费计量、CDC与可信管理员登录未完成；以下日期记录为历史。

2026-10-09 最新：用户已选择整主题订阅授权。本轮完成 Kafka 独立申请、确认、退回、编辑、撤回及审批历史，新增中文“Kafka 订阅授权”页面；V12 两表均有中文注释，真实失败不转演示成功。137项后端、117项前端、构建、真实审批浏览器与原汇报演示回归通过，本地 Foundation 已更新，原 Source20条保留。当前仅审批流程，所有申请仍为 NOT_PROVISIONED，不签发凭据、不执行 Broker ACL，不计为实际订阅授权/撤权闭环。下一步独立 SCRAM 身份与 Broker 权限执行、撤销及到期协调。见[审批交付](docs/KAFKA_SUBSCRIPTION_CHANGELOG.md)；下文日期条目为历史。

2026-10-09 Kafka完整链路及生产定位完成：生产摘要从底座验证结果关联资源/能力，关系/需求方/策略仍为空；新增独立测试库、真实两服务/SCRAM Broker/浏览器联调，不拦截成功。真实核对1条320字节、位点、自动审计及资源计量，停用/来源停用403、底座停机503；未授权订阅仍拒绝。127项后端、13项独立权限、1项显式完整链路、112项前端与构建通过。两开发服务已更新，库V11、原Source20条，独立库测试登记全清理、临时进程/Broker停止；没有常驻Broker或订阅授权/ACL治理/消费采集/CDC。共享粒度仍待明确，不能自动扩展REST策略。见[完整链路交付](docs/KAFKA_COMPLETE_CHAIN_CHANGELOG.md)。下文日期条目为历史。

2026-10-09 Kafka提供侧运行控制完成：支持真实主题验证与发送启停、独立SASL配置、发送前逐次核对能力/来源/主题/认证方式；底座不可用或停用拒绝新请求，现有REST策略不扩权。后端127项（Foundation97、Provider30）、前端112项、构建、Kafka浏览器专项及原REST/工作流回归通过。真实SASL Broker在隔离测试验证，本机常驻Broker未配置；浏览器成功启停采用拦截响应，只计UI验证。两服务已更新，库仍V11。订阅授权/ACL共享联动、消费计量及CDC未完成，共享粒度仍需明确。见[运行控制交付](docs/KAFKA_RUNTIME_CHANGELOG.md)。下文日期条目为历史。

**2026-10-09 Kafka接入基础验证完成：** 新增独立Java订阅示例与真实Broker认证/主题/消费组/撤销权限测试，13项通过。运行 `scripts/test-kafka-demand-example.ps1 -Offline` 可自动验证临时Broker；不需要Docker，不改现有服务或业务源。共享粒度待明确，能力启停、共享关系与Broker联动仍待开发，见[设计与运行](docs/KAFKA_SHARING_DESIGN.md)、[交付及清理例外](docs/KAFKA_BROKER_AUTH_CHANGELOG.md)。

**2026-10-09进入V0.5，第一小阶段完成：** 新增独立外部REST调用方示例，支持授权查询、有限分页、错误及追踪处理，真实核对允许/拒绝/暂停/撤销与提供侧自动计量。16项客户端、113项后端、107项前端、构建及真实监管浏览器回归通过，开发库仍V11。无需新增服务；在VS Code终端运行 `scripts/run-demand-rest-example.ps1`，使用方法见[接入规范](docs/DEMAND_REST_INTEGRATION.md)、[交付](docs/V05_REST_INTEGRATION_CHANGELOG.md)、[后续计划](docs/V05_DEVELOPMENT_PLAN.md)。仅接入示例，Kafka消费治理、CDC与需求方业务服务未实现。

2026-10-09资源版本历史完成：“资源目录 → 查看 → 版本历史”查看登记、信息修改、字段维护和发布快照及相邻版本变化。存量仅留启用时当前基线，不补造更早历史；写入与快照同事务，过期版本不留存。113项后端、107项前端、build及真实历史/结构比较/资源/共享访问/演示回归通过，Foundation已更新、开发库V11、原资源及业务数据保留。无版本恢复或结构应用，见[交付](docs/RESOURCE_HISTORY_CHANGELOG.md)、[接口](docs/RESOURCE_HISTORY_API.md)。以下日期内容为阶段历史。

2026-10-07资源结构比较完成：进入“资源目录 → 查看 → 结构变化”，选择后续成功扫描后点击“开始比较”。基准固定登记扫描，中文展示字段增删/修改、表注释及属性前后值；没有候选可进入扫描工作台。只读取已保存快照，不修改资源、版本、能力或策略。107项后端、101项前端、build及真实比较/资源/共享访问/演示回归通过，Foundation已更新，数据库仍V10；结构更新与版本历史尚未实现。见[使用与交付](docs/SCHEMA_COMPARISON_CHANGELOG.md)、[接口](docs/SCHEMA_COMPARISON_API.md)。下文日期内容保留阶段历史。

2026-10-06资源维护补充：资源编辑必须带读取时`version`，字段编辑必须带字段响应的`resource_version`；过期请求返回409且不修改数据。前端保留冲突输入，提供取消并刷新入口。101项后端、93项前端及构建、真实双页面/共享授权和自动采集回归通过；Foundation已更新，数据库V10不变。旧编辑客户端需同步版本参数，详见[并发编辑交付记录](docs/RESOURCE_EDIT_CONCURRENCY_CHANGELOG.md)。

2026-10-06资源登记补充：创建请求必须包含所选扫描的`scan_task_id`，后端核对成功任务、来源、表及字段完整性，固定该快照；详情“扫描来源”可打开对应任务，编辑不能换绑。完整后端98项、前端91项、构建及真实资源/共享访问回归通过，Foundation已更新、数据库V10不变。见[扫描快照绑定](docs/RESOURCE_SCAN_SNAPSHOT_CHANGELOG.md)。

2026-10-04 V0.3访问校验已接入：新增“调用凭据”和“共享调用验证”，REST查询同时要求调用令牌、关系编号与能力编号，执行字段投影和时间限制。开发库迁移V9，两服务已打包重启；完整后端76项测试通过，真实授权及原REST启停浏览器流程通过。后续统计与演示验证见[访问接口](docs/ACCESS_API.md)与[测试记录](docs/TESTING.md)。以下带日期内容保留阶段历史。

当前范围：**V0.1接入与资源闭环 + V0.2能力及固定Provider + V0.3 REST共享授权闭环 + V0.4单Provider运行监管原型 + 中文管理前端**。Provider按可信需求方身份、关系、允许字段及数据时间范围直接读取Source，底座不传输业务行。V0.4实现自动REST/Kafka生产摘要、双层去重、有界异步队列与文件补报、中文审计/追踪/计量；后端95项、前端90项测试与构建通过，2026-10-06真实REST与Foundation落库计量核对，开发库V10。Kafka回执由嵌入式Broker验证，消费侧、CDC、完整分布式追踪及服务端管理员登录仍待实现。详见[运行监管交付](docs/RUNTIME_TELEMETRY_CHANGELOG.md)。历史数据和配套凭据归档于工作区根目录`.legacy-backup/`，新版不会读取。

V0.3第二阶段：共享治理增加“共享关系管理”和“访问策略”，绑定双方主体、资源和能力，配置用途、有效期、明确允许字段与独立数据时间范围，支持确认/退回/启用登记/暂停/撤回及版本历史。仅使用真实API，策略随关系草稿编辑，确认后冻结；追加V8。后端62项、前端72项测试、构建、真实关系浏览器专项及原演示/REST回归通过。见[关系策略接口](docs/SHARING_API.md)与[交付记录](docs/SHARING_CHANGELOG.md)。

## 架构边界

开发需求与阶段验收见 [统一数据底座需求说明书（2026-10-02）](docs/统一数据底座需求说明书_20261002.docx)。当前以[恢复后的V0.3计划](docs/V03_DEVELOPMENT_PLAN.md)为准，历史暂停记录不再作为开发限制。

前端新增“共享治理 → 共享主体管理”：提供方/需求方登记、编辑、启停、检索与分页，默认真实调用`/api/v1/subjects`。Flyway追加V7，登记信息保存于`foundation_db.sharing_subject`；编码、角色不可修改，更新检查版本。不会自动授予权限或创建业务数据，见[共享主体接口](docs/SUBJECT_API.md)。51项后端测试、62项前端测试、构建、真实主体浏览器流程及演示回归通过。

Foundation 只管理元数据、资源、能力、共享关系、策略、计量和审计。业务明细留在 Source；Source → Demand 的数据流不经过 Foundation。Kafka Topic 和 CDC Stream 属于 Capability。外部系统通过 Adapter 接入，业务含义和权限由人工确认。

Foundation 通过 Flyway 管理数据源、扫描结果、资源、字段业务属性及能力登记表；地址、端口、数据库和用户名通过配置表动态登记，密码在独立凭据表中加密保存，主密钥由环境变量提供，不保存明文密码或业务行。旧连接环境变量引用保留兼容。`port_source_db.container_event` 及 20 条明确标识的模拟数据通过独立 Source Flyway 迁移创建。Provider 通过只读账号直接从该 Source 表返回业务行或向 Kafka 发送指定事件；Foundation 只做无业务行的 REST 探测和 Topic 元数据验证。接口见 [数据源接口](docs/SOURCE_API.md)、[扫描与资源接口](docs/SCAN_RESOURCE_API.md)、[能力接口](docs/CAPABILITY_API.md)、[Provider REST](docs/PROVIDER_REST_API.md) 和 [Provider Kafka](docs/PROVIDER_KAFKA_API.md)。

## 动态接入外部 MySQL

首页已增加“待处理事项”，可检索待发布资源、待验证草稿能力和失败扫描，并进入已有管理页面人工处理。使用现有列表元数据，不新增后台工作流或自动执行操作。最新52项前端测试、构建、首页统计/待处理浏览器专项和演示流程通过，见[首页交付补充记录](docs/DASHBOARD_CHANGELOG.md#首页待处理事项补充2026-10-04)。

进入前端“数据源管理 → 新增数据源”，填写名称、数据库地址、端口、数据库名称、只读用户名、密码及加密连接方式，然后“保存并测试连接 → 开始扫描 → 查看字段”。连接元信息保存到 `source_system`，密码通过统一 CredentialService 加密存入 `source_credential`；无需修改 Java、新增 DataSource Bean 或重新启动服务。编辑密码留空会保留原凭据；停用会阻止新的测试和扫描。

点击数据源行“查看”可集中查看连接基本信息、最近扫描和关联资源，跳转指定扫描任务、资源详情或来源资源目录。最新57项前端测试、构建、概览/原数据源管理浏览器专项和演示流程通过；概览仅读取元数据，未改后端或数据库。见[数据源概览记录](docs/SOURCE_CATALOG_CHANGELOG.md#数据源概览补充2026-10-04)。

Foundation 自身仍使用固定 Spring DataSource，外部数据库由 SourceConnectionFactory 按需建立并关闭 JDBC 连接。扫描仅访问 `information_schema`。本机脚本首次自动生成 `FOUNDATION_CREDENTIAL_KEY` 并保存在被 Git 忽略的 `.env.local`，重启复用；备份数据库时必须同时备份主密钥。仅支持 MySQL，旧环境引用仍可运行，详见 [动态接入接口与凭据说明](docs/SOURCE_API.md)。

## 目录

```text
railway-data-foundation/
├── pom.xml                      Maven 聚合工程，Java 17
├── foundation-service/          Spring Boot 3、API、Flyway、测试
├── provider-service/            独立的 Source 直连 REST 服务
├── demand-service/              预留，尚未实现
├── examples/rest-demand/        外部REST调用方示例，非独立业务服务
├── examples/kafka-demand/       独立Kafka订阅与Broker权限验证，未接入共享关系
├── foundation-web/              Vue 3 演示前端，含独立 mock fallback
├── sql/source/                  Source 表结构及模拟数据迁移
├── docs/                        路线图、实现状态、测试记录
├── deploy/                      预留
├── scripts/                     环境导入、Maven 配置
├── docker-compose.yml           两个独立 MySQL + Source 迁移任务
├── .env.example
└── AGENTS.md
```

## 本机开发启动（无需 Docker）

### VS Code 运行按钮

在 VS Code 打开外层 `数据底座` 工作区，按 `Ctrl+Shift+D` 打开“运行和调试”，选择 **启动本地后端（含 MySQL）**，点击绿色三角或按 `F5`。选择 **停止本地服务** 并运行可以停止服务。代码修改后按 `Ctrl+Shift+B`，执行“本地：构建并启动后端”，会先停止、构建测试再启动。

这些配置调用现有 PowerShell 脚本，使用 VS Code 内置 JavaScript 调试器提供的终端启动功能；它们不提供 Java 断点调试。启动命令结束后服务继续在后台运行，关闭终端或结束启动调试会话不会停止服务，请使用上述停止选项。前端可选择 VS Code 的 **启动演示前端** 运行选项。

### 命令行启动

本机已经配置 Java 17 和 MySQL 8，进入本目录后执行：

```powershell
# 后台启动 MySQL、执行迁移、启动 Provider 与 Foundation；重复执行不会重复启动进程
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/start-local.ps1

# 修改 Java 代码后，停止并重新构建启动
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/stop-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/start-local.ps1 -Build -Offline

# 停止并保留数据库
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/stop-local.ps1
```

Foundation Swagger：`http://127.0.0.1:18080/swagger-ui.html`。Provider健康接口：`http://127.0.0.1:18081/api/v1/provider/health`。业务查询需登记、验证并启用REST能力，确认启用共享关系，并签发需求方调用凭据；携带`Authorization`、`X-Relationship-Id`和`X-Capability-Id`访问18081，完整示例见[Provider REST](docs/PROVIDER_REST_API.md)。

### 汇报前端

先按上文启动本地后端，再开一个终端：

```powershell
cd foundation-web
npm install
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。首次点击“进入平台”即可使用演示布局；此入口只负责前端导航，没有后端认证。`VITE_API_BASE_URL=/api/v1` 与 `VITE_USE_MOCK=false` 已写在 [前端环境配置](foundation-web/.env)。Vite 将 `/api` 代理到本地后端 18080。数据源、扫描任务、资源、字段和 Capability 登记均优先使用真实 API；后端不可达时演示页可使用 [集中维护的演示数据](foundation-web/src/mock/demoData.js)，页面会标注“演示数据”；真实数据源的写入、测试和扫描失败不会模拟成功。设置 `VITE_USE_MOCK=true` 可强制使用演示数据。模拟编辑仅保存在当前浏览器的 localStorage，不保存密码。前端新增数据源时向后端提交密码一次，后端加密保存且不在查询接口中回显。

本机用 Navicat 查看底座表时，连接 `127.0.0.1:13306`，数据库为 `foundation_db`，账号 `foundation_app`，密码读取被忽略的 `.env.local` 中 `FOUNDATION_DB_PASSWORD`。V6 已为底座自建表字段补齐中文“注释”；在 Navicat 的“设计表”中查看。`source_system.credential_ref` 是关联 `source_credential` 的凭据编号，不是密码。

推荐演示：首页 → 登记数据源 → 测试连接 → 扫描`container_event` → 创建/发布资源并人工标记时间字段 → 验证启用REST能力 → 登记双方主体 → 明确共享字段与时间范围并确认启用关系 → 签发需求方调用凭据 → 共享调用验证成功 → 越权字段/超范围时间拒绝 → 暂停关系或撤销凭据后拒绝。Provider仍指定来源；Kafka启停和CDC尚未实现，普通REST令牌不授予Kafka权限。业务行直接由Provider返回，底座只传治理元数据。

接口能力登记表单已提供中文字段说明与“填入本机示例配置”。在现有REST查询能力的“查看 → 调用方式”中，可查看后端配置的提供服务地址、完整地址及必带请求头，并复制PowerShell命令到VS Code终端执行；说明加载失败会显示错误与重试，未启用/已停用时不能查询。详见[本轮交付记录](docs/CAPABILITY_GUIDE_CHANGELOG.md)。

接口能力页进一步补充中文操作流程、状态含义及禁用原因，保存输入锁定、旧写入响应隔离、详情状态同步与调用说明归属检查；系统说明已按实际REST启停及四类能力支持范围更新。38项前端测试、构建、流程/目录专项及真实REST回归通过，见[能力流程交付记录](docs/CAPABILITY_WORKFLOW_CHANGELOG.md)。

资源登记和字段维护也已使用真实操作结果：来源表只读，详情可发布草稿，发布后才能登记能力；真实保存、发布与字段编辑失败保留输入并提示刷新，不模拟成功。指定资源字段和扫描结果读取失败显示错误，演示字段按资源隔离。详见[资源流程修复记录](docs/RESOURCE_FLOW_CHANGELOG.md)。

资源创建进一步核对指定扫描任务与候选表，失败可重试、未核对禁止保存；目录中已登记表可直接查看原资源。编辑/发布与保存互斥，旧响应不覆盖新入口。43项前端测试、构建、登记/目录专项与真实资源回归通过，见[资源登记交付记录](docs/RESOURCE_REGISTRATION_CHANGELOG.md)。

资源详情已增加字段与关联能力筛选、10/20/50项分页及区域独立重试。字段保存锁定输入，切换资源后旧读取/保存/发布响应不会覆盖新页面；关联能力管理可直接进入带资源条件的能力目录。33项前端测试、构建、详情浏览器专项及真实资源回归通过，见[资源详情交付记录](docs/RESOURCE_DETAIL_CHANGELOG.md)。

首页已分页统计现有元数据接口，修复首批记录被误当作总数和UTC跨日问题。读取失败显示“—”，超出5,000条上限显示“≥”及范围；可用能力同时检查来源启用与资源发布状态，不代表实时探测。首页仅在显式`VITE_USE_MOCK=true`时使用演示统计。见[首页交付记录](docs/DASHBOARD_CHANGELOG.md)。

资源目录已支持关键词/来源/状态/安全等级组合筛选、10/20/50条分页及刷新/详情返回条件保留。使用已有资源分页接口，每次最多读取5,000项并标记范围；中途读取失败或真实目录刷新失败不切为演示。见[目录交付记录](docs/RESOURCE_CATALOG_CHANGELOG.md)。

本地配置在 `.env.local`（自动生成随机密码，已被 Git 忽略），日志及持久化 MySQL 数据位于 `.runtime/local/`。本地使用一个 MySQL 实例、两个独立数据库，默认端口 13306；Foundation 使用仅限 `foundation_db` 的账号，Source 迁移使用 `source_app`，Provider 使用新建的 `provider_reader` 只读账号。Source 由独立 Flyway 命令迁移。不要删除 `.env.local` 或 `.runtime/local/data/`，它们需要配套保留。

首次在其他机器使用时，先设置 `$env:LOCAL_JAVA_HOME`（JDK 17 安装目录）及 `$env:LOCAL_MYSQL_HOME`（MySQL 8 安装目录），再运行启动脚本。`-Offline` 使用已有 Maven 缓存；缺少依赖时省略此选项。已有 JAR 时默认不重新编译，代码修改后需使用 `-Build`。脚本仅对本次 PowerShell 进程绕过脚本执行策略，不修改系统策略。

如需调整端口，修改 `.env.local` 中 `LOCAL_MYSQL_PORT` 及两个 JDBC URL；应用端口修改 `FOUNDATION_PORT`。本地初始化账号名及随机密码请保持不变。脚本不会安装 Windows 服务，也不使用其他 MySQL 安装的数据目录。

## Docker 启动（后续部署可用）

需要 JDK 17、Maven 3.6.3+、Docker Compose v2。以下命令从本目录运行；`JAVA_HOME` 必须指向 JDK 17，不能使用系统中的 Java 8。

```powershell
Copy-Item .env.example .env
# 编辑 .env，替换所有 CHANGE_ME；只在首次创建时复制，避免覆盖已有配置。
. ./scripts/load-env.ps1
docker compose up -d --wait mysql-foundation mysql-source
docker compose run --rm source-migrate
# 确认上一条迁移命令成功退出后再启动服务。
mvn -pl foundation-service spring-boot:run
```

默认应用端口 18080；MySQL 映射端口分别是 13306 和 13307，避免与旧原型冲突。地址和密码均来自环境变量；`.env` 仅由 Compose 自动读取，直接使用 Maven 前需要运行 `load-env.ps1`。脚本按普通文本解析 `KEY=value`，不执行文件内容；值无需加引号。示例数据库配置仅用于本机开发。

应用启动时自动迁移 `foundation_db`；启动失败会直接暴露配置或迁移问题，不会静默回退 H2。Compose 仅在空数据卷上创建数据库和账号；后续修改密码须同步调整实际数据库账号。

打包：`mvn package`，随后 `java -jar foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar`。需要先导入相同环境变量。

停止基础设施：`docker compose down`，保留数据卷。服务以前台方式运行，Ctrl+C 停止。

## 验证

```powershell
Invoke-RestMethod http://127.0.0.1:18080/api/v1/health | ConvertTo-Json -Depth 4
```

```json
{"code":0,"message":"success","data":{"status":"UP","service":"foundation-service"}}
```

Foundation health 表示应用存活；Provider 的 HEAD `/containers/events` 会实际检查 Source 表读取权限，但不返回业务行。Swagger：`http://127.0.0.1:18080/swagger-ui.html`；OpenAPI：`http://127.0.0.1:18080/v3/api-docs`。

错误格式统一为 `{"code":10001,"message":"请求参数不合法","data":null}`，并使用对应 HTTP 状态码。错误码见 `api/ErrorCode.java`。异常响应不返回原始数据库错误、密码或堆栈。

## 测试

```powershell
mvn test
```

默认执行不依赖数据库的测试，真实 MySQL 集成测试明确跳过。本地完整测试可以运行 `powershell -ExecutionPolicy Bypass -File ./scripts/test-local.ps1 -Offline`，脚本使用独立测试库；不能把默认跳过解释为 MySQL 测试通过。详细覆盖范围见 [SOURCE_API.md](docs/SOURCE_API.md) 和 [测试说明](docs/TESTING.md)。

详细进度见 [IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md)，阶段顺序见 [ROADMAP.md](docs/ROADMAP.md)。V0.1 已完成；V0.2 已实现 Capability 登记、Provider REST 与指定 REST 探测、Provider Kafka 生产及 Topic 元数据验证。Kafka 集成测试使用嵌入式 Broker；本机常驻服务需另行启动 Broker。V0.3 按用户要求暂停。

接口能力前端已增加中文组合检索、分页和已发布资源选择，默认真实模式的读取失败不自动混入演示；提交与调用说明增加重复操作和旧请求保护。20项前端测试、构建、浏览器专项及真实REST启停回归通过。详见[能力管理交付记录](docs/CAPABILITY_CATALOG_CHANGELOG.md)。

元数据扫描工作台支持任务组合筛选、表/字段检索及独立10/20/50项分页、匹配数量、所选任务刷新、故障重试、响应归属与结构校验、旧读写响应隔离。最新48项前端测试、构建、扫描浏览器专项及真实资源/两库扫描回归通过。后端仍采用原有同步扫描接口，V0.3保持暂停。详见[扫描工作台交付记录](docs/SCAN_WORKBENCH_CHANGELOG.md)。

数据源管理已增加中文检索、分页、读取错误及保存/连接测试保护；默认真实读取失败不切演示，失败保留表单且密码不持久化。28项前端测试、构建、浏览器专项及真实两库接入回归通过，数据库仍为V6。详见[数据源管理交付记录](docs/SOURCE_CATALOG_CHANGELOG.md)。
