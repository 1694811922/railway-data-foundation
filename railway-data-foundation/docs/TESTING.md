# 开发测试记录

2026-10-14最新：Kafka订阅与接收任务联动已完成。已有任务直接查看数据，无任务才检查授权并创建；通用创建表单也先查关联，读取失败不当作无任务。支持撤权后本主体历史入口、重试及旧身份/响应隔离；精确授权筛选的列表和总数同时按主体限定。215项不同主模块测试、前端171项/build、真实订阅入口/撤权历史及原接收下载/REST/Kafka回归通过。本机需求方新包及5173已运行，原源20条不变、临时登记清理；库仍V17/V2/需求方V1，未提交推送。见[联动使用指南](RECEIVING_SUBSCRIPTION_LINK_GUIDE.md)、[交付记录](RECEIVING_SUBSCRIPTION_LINK_CHANGELOG.md)。以下为历史。

2026-10-14最新：需求方接收数据下载与导出已完成。单条原始JSON/文本/二进制、完整消息JSON、筛选JSONL及取消下载；仅本主体可用，文件准备后重验登录，固定快照最多10000条/50MiB，错误/截断不报成功，文件直接由需求方服务提供。213项主模块测试、前端165/build及真实浏览器文件核对/取消/撤权后历史下载/原授权回归通过；原源20条不变，临时登记清理、服务临时文件0，新需求方包及5173已运行。库仍V17/V2/独立V1，无Excel映射/后台导出/CDC，未提交推送。见[下载指南](RECEIVING_DOWNLOAD_GUIDE.md)、[交付](RECEIVING_DOWNLOAD_CHANGELOG.md)。以下为历史。

2026-10-14最新：需求方新增只读接收进度面板，按本主体授权核验实际Kafka保留范围、消费组位置和数据库检查点，明确无可用消息/未建立/待接收/追平/越界，未知不补0；不消费或改任务。205项主模块测试、前端161/build及真实三角色/注册/发送落库/只读进度/旧响应隔离/撤权回归通过，原源20条不变、临时登记清理。需求方新包与5173前端已启动。库仍V17/V2/独立V1，无CDC/后台生产/计费，未提交推送。见[进度使用指南](RECEIVING_PROGRESS_GUIDE.md)、[交付](RECEIVING_PROGRESS_CHANGELOG.md)。以下为历史。

2026-10-13最新：提供方数据发送工作台已完成。真实登录/主体归属/CSRF核验后，通过独立Provider通道查询源记录、单条及最多50条勾选发送；显示真实回执、取消后续项和待确认结果，不自动重试。197项不同主模块测试、前端157/build、多角色/注册及真实浏览器发送3条并累计落库4条、故障恢复/撤权回归通过；原源20行不变，临时登记清理。本机服务已更新，旧会话需重新登录；库仍V17/V2/需求方V1，无CDC/后台生产/通用映射，不提交推送。见[发送使用指南](PROVIDER_PRODUCTION_GUIDE.md)和[交付记录](PROVIDER_PRODUCTION_CHANGELOG.md)。以下为历史。

2026-10-13：按用户明确授权新增独立需求方真实数据接收服务（18082）及需求方数据接收工作台。Kafka消息直接落入独立 demand_receiver_db，底座不接收业务消息。持续任务、事务后提交位置、幂等落库、重启恢复、主体隔离、凭据加密及技术补报已实现。主模块190项不同测试、前端149/build、真实三角色/注册及实际Kafka两条落库/故障恢复/撤权/浏览器通过；原源20行不变，临时登记清理。底座仍V17，需求方独立V1，无CDC/业务映射/高可用，不提交推送。本条为最新，以下为历史。见[本轮交付](DEMAND_RECEIVING_CHANGELOG.md)与[使用指南](DEMAND_RECEIVING_GUIDE.md)。

2026-10-13提供方确认最终：完整后端142+30=172，失败/错误/跳过0；npm test142及build通过（既有包体警告）。真实三角色REST退回/重提/确认/最终审批及Kafka确认/审批通过，包含请求失败、身份隔离、历史/版本事务与并发验证；实际Kafka1条320字节、提供方确认后的真实权限配置/读取/撤权，新增REST确认前后拒绝→最终审批启用后允许→撤回拒绝通过。V17、原源20行及摘要不变。本次中断的联调不计通过，精确撤销旧测试三身份/权限及隔离空库唯一测试管理员后重新验证成功；新清单先写无秘密身份，便于中断恢复。最终临时Broker测试身份/ACL0，27主题保留，不计稳定部署完成。证据及初轮修复见[交付](PROVIDER_CONFIRMATION_CHANGELOG.md)。未提交推送；以下为历史。

本轮显式演示隔离专项：在foundation-web运行 `npm run test:subscription-demo`，真实浏览器拦截并拒绝任何真实API请求，核对实际请求数为0；带资源/能力参数的订阅入口不打开申请表，新增/刷新禁用，无权限及凭据操作。结果通过，日志 `.runtime/kafka-application-demo-isolation.log`（工作区根目录）。

2026-10-12 Kafka申请链路最终验证：完整后端Foundation137+Provider30=167，失败/错误/跳过0（含注册与三角色浏览器）；前端npm test137、build通过，既有包体警告保留。三角色最终专项另外回归提供方只读、归属联动、错配链接、选项失败重试及同一页面晚到响应隔离，显式演示smoke通过。原生Kafka链路另行1项通过，实际1条320字节/位点0、撤权及REST回归；原源20行及内容摘要不变，开发V16、隔离登记0，Windows测试主题保留。初轮夹具重复名称失败已修复，以最终结果为准。日志、截图及范围见[KAFKA_APPLICATION_FLOW_CHANGELOG.md](KAFKA_APPLICATION_FLOW_CHANGELOG.md)。未提交推送；下文为历史。

2026-10-12 Kafka模拟流程：新增VS Code“演示：Kafka 模拟数据完整流程”及 run-kafka-simulation.ps1，真实Provider/Kafka/外部消费20条6396字节、位点0–19，审批/授权/消费摘要/撤权与REST兼容通过；默认一条320字节链路另行回归。示例16项、前端132/build和工具边界通过。独立登记清理、原源完整摘要不变，主库仍V16/2个无归属来源；Windows保留测试主题，非稳定部署/压测。见 [模拟指南](KAFKA_SIMULATION_GUIDE.md)、[交付](KAFKA_SIMULATION_CHANGELOG.md)。产品后端未改，无新增迁移/需求方服务/CDC，不提交推送。以下为历史。

2026-10-12注册最终验证：完整reactor Foundation137+Provider30=167，失败/错误/跳过0（含注册5项和三角色真实浏览器）；npm test132，失败/跳过0，build通过（既有包体警告）。真实首任管理员/申请/审批/登录/演示隔离通过；私有日志位于工作区.runtime/registration-backend-full.log、registration-browser.log、registration-web-final.log和registration-build-final.log。开发V16成功，账号/申请0；独立测试账号/申请/主体0，原源20条/2个未归属来源保留。初轮失败已修复，以最终全量为准。见[交付](REGISTRATION_CHANGELOG.md)。以下为历史。

2026-10-11 本轮最新：完整reactor启用三角色浏览器，Foundation132、Provider30，失败/错误/跳过均0；前端129项通过、build成功。独立Kafka默认16通过/完整链路跳过1，另行显式原生完整链路1通过。默认两次重启用原凭据读取偏移0/1/2并依次提交进度，错误密码拒绝、缺覆盖配置及私有文件不变通过；独立原生首次初始化和固定密码/权限/配置异常拒绝通过，必须有完成标记和服务停止证据。初轮提前退出不计通过、元数据段1MiB失败保留，最终8MiB测试重新通过。主集群临时身份/ACL0，测试主题保留16；私有首次启动文件保留、测试Broker停止，隔离库账号/主体/来源及链路登记0，V15/原Source20条/2个无归属来源不变。详见[全部日志和限制](KAFKA_RESTART_HARDENING_CHANGELOG.md)。历史认证失败根因未确认，在线主题删除与稳定部署未完成，下文为历史。

2026-10-11 原生部署/实验工具回归：完整后端Foundation131通过+1显式角色浏览器默认跳过、Provider30通过；随后启用该浏览器1项通过，合计162个不同测试验证。前端129全部通过及build成功，独立示例16通过+默认链路1跳过，原生Broker完整链路1另行通过。真实三角色、工具计算/去重/分位数/CSV单位、参数边界与脚本语法通过。最终三场景六行各180唯一接收，错误/重复0；详见 [原始证据及范围](LOCAL_KAFKA_EXPERIMENT_CHANGELOG.md)。

Windows在线删除主题引发日志目录AccessDenied及Broker退出，订阅SCRAM身份跨重启最新复验失败；稳定部署不计完成。临时身份/ACL0、测试主题保留9项及清单，一项失效目录删除被审批拒绝后改为归档保留；不是完整主题清理。开发库V15、原源20条及2个未归属来源保留，隔离登记核对0。Linux环境待确认，正式Provider压测、业务高峰量级、统一断网恢复对比及接口选择结论仍未实现。

2026-10-10 Kafka消费观察验收：Foundation完整132项、Provider完整30项，失败/错误/跳过均0，按两个模块合计162项；前端npm test 129项通过、npm run build成功。独立订阅示例默认16项通过（完整链路默认跳过1项），另行启用真实链路1项通过；真实三角色浏览器及显式演示回归通过。详见 [验证表及日志](KAFKA_CONSUMPTION_CHANGELOG.md)。首次完整reactor的Provider曾因测试磁盘不足失败，随后仅调整嵌入式Broker测试资源和真实分配等待，完整重跑30项成功；不把失败记录覆盖为成功。测试JVM限制内存/处理器及上下文缓存，角色和演示Vite使用独立缓存。

新增验证包括摘要严格类型/时间/数量/业务字段拒绝，独立令牌和登录Cookie隔离、他方申请403与匿名401，重试/冲突/并发去重、停止/失效拒绝、分页及未知状态；外部程序真实读取1条320字节并上报，需求方浏览器核对。503仅注入读取故障/旧响应，成功来自真实API；原REST与Kafka生产、授权、撤权流程回归通过。开发库V15、新表中文注释完整，Source仍20条、原2个来源无归属；独立测试账号、报告、链路订阅清理。前后端恢复后HTTP200、匿名摘要401。本轮未执行Git提交/推送；未实现持久补报、分钟消费计量或CDC。

2026-10-10 管理员账号完善回归：完整test-local -Offline（RUN_ROLE_BROWSER=true）后端156项，Foundation126、Provider30，失败/错误/跳过0；npm test 127通过，npm run build通过，真实三角色浏览器与显式演示通过。205账号/201主体超过200条分页、总数筛选、字面%/_、匿名401/普通角色403、Unicode密码/版本保护；30条真实UI翻页、主体第二页、新建/启停/重置、503读取清空与重试、旧会话失效。异步旧读取单元验证，测试登记全部清理、V14未改、原源20条保留；Foundation已更新。证据工作区 .runtime/account-backend-final.log、account-web-final.log、account-build-final.log、account-demo-final.log、role-browser.log及role-evidence/accounts.png。真实Broker完整链路本轮未重跑，原证据保留。见 [交付](ACCOUNT_MANAGEMENT_CHANGELOG.md)。

2026-10-10 本人改密验证：完整后端153（Foundation123、Provider30）全通过，前端124、build、真实Edge三角色/错误密码/取消清空/本人改密/旧会话失效/新密码登录与演示通过。直接接口覆盖匿名401、CSRF403、伪造目标与错误输入400，失败不改账号，外部REST凭据/Kafka申请保持不变。V14不变、测试数据清理、Source20条；证据见 [交付](SELF_PASSWORD_CHANGE_CHANGELOG.md)。

2026-10-10 三角色最终回归：完整后端150（Foundation120、Provider30）通过；前端122、build、显式演示浏览器通过。真实Edge多账号验证、编号越权/分页总数/公开范围/CSRF/退出/停用、本人REST/Kafka申请与凭据通过；真实两服务/SCRAM Broker实际消费和停止拒绝、外部REST允许/撤销401/暂停403/控制故障503通过。独立Broker权限13项、REST客户端16项通过。已打包JAR在独立库安全初始化哈希管理员并退出/清理通过；本机V14、health均正常、未登录401、5173为200，旧2来源归属不变、原Source20条，测试登记清理。证据及命令见 [本轮交付](ROLE_WORKSPACE_CHANGELOG.md)、[三角色指南](ROLE_WORKSPACE_GUIDE.md)。默认Kafka测试跳过需显式开启的完整链路1项，该项已单独验证通过；不将跳过计为通过。下文为历史记录。

2026-10-10 Kafka订阅执行：141项后端（Foundation111、Provider30）、119项前端、构建通过；隔离真实SCRAM Broker/两服务/浏览器完整链路1项通过。实际读取、原连接撤权拒绝、其他主题/组/写入拒绝、独立身份不互相影响、一次性领取与不持久化、未来有效期拒绝、残缺ACL补偿、数据库结果失败恢复、集群错配及到期/依赖失效撤权均验证。原审批的分页/失败重试/旧响应/演示隔离与原汇报演示回归通过。开发库V13、订阅表注释完整、源仍20条、隔离元数据0；日志和限制见[KAFKA_SUBSCRIPTION_EXECUTION_CHANGELOG.md](KAFKA_SUBSCRIPTION_EXECUTION_CHANGELOG.md)。以下为历史。

2026-10-09 最新：独立整主题订阅审批已验证，完整后端137项（Foundation107、Provider30），前端117项及构建通过。新增真实浏览器覆盖登记/提交/退回/编辑/确认/撤回、历史、分页、409保留输入、失败重试、旧响应隔离与演示模式不请求真实接口；原汇报演示回归通过。审批成功来自真实服务，前置能力状态是仅用于审批的元数据夹具，没有Broker授权或订阅成功模拟。原Source仍20条，随机测试登记已清理，开发库V12。见[审批交付](KAFKA_SUBSCRIPTION_CHANGELOG.md)。下文日期记录为历史。

2026-10-09 Kafka完整链路及生产定位完成：生产摘要从底座验证结果关联资源/能力，关系/需求方/策略仍为空；新增独立测试库、真实两服务/SCRAM Broker/浏览器联调，不拦截成功。真实核对1条320字节、位点、自动审计及资源计量，停用/来源停用403、底座停机503；未授权订阅仍拒绝。127项后端、13项独立权限、1项显式完整链路、111项前端与构建通过。两开发服务已更新，库V11、原Source20条，独立库测试登记全清理、临时进程/Broker停止；没有常驻Broker或订阅授权/ACL治理/消费采集/CDC。共享粒度仍待明确，不能自动扩展REST策略。见[完整链路交付](KAFKA_COMPLETE_CHAIN_CHANGELOG.md)。下文日期条目为历史。

2026-10-09 Kafka提供侧运行控制完成：支持真实主题验证与发送启停、独立SASL配置、发送前逐次核对能力/来源/主题/认证方式；底座不可用或停用拒绝新请求，现有REST策略不扩权。后端127项（Foundation97、Provider30）、前端111项、构建、Kafka浏览器专项及原REST/工作流回归通过。真实SASL Broker在隔离测试验证，本机常驻Broker未配置；浏览器成功启停采用拦截响应，只计UI验证。两服务已更新，库仍V11。订阅授权/ACL共享联动、消费计量及CDC未完成，共享粒度仍需明确。见[运行控制交付](KAFKA_RUNTIME_CHANGELOG.md)。下文日期条目为历史。

## 2026-10-09 Kafka Broker权限基础

独立`test-kafka-demand-example.ps1 -Offline`最终13项通过，无失败/错误/跳过；真实临时KRaft Broker执行SASL身份、精确Topic/Group读取、越权拒绝、写入拒绝及撤销，实际订阅进程成功/拒绝/不可达处理通过。停止后断言临时目录删除；编译/打包与运行依赖生成通过。未更改业务服务、源库或前端；不计为共享关系联动或消费计量。失败轮次有一处Temp残留清理被自动审批拒绝，详见[交付记录](KAFKA_BROKER_AUTH_CHANGELOG.md)，共享粒度和后续实施见[设计](KAFKA_SHARING_DESIGN.md)。

## V0.5 外部REST接入验证（2026-10-09）

新增客户端16项测试通过，真实接入三页7/7/6条、允许时间范围、越权字段/时间403、暂停403、恢复允许及撤销401；实际CLI只输出安全摘要，PowerShell入口及临时环境恢复通过。11次真实请求的提供侧唯一事件、追踪、成功记录与UTF-8字节及分钟计量核对通过；资源/字段/能力/确认策略不变。完整后端113项、前端107项、构建及真实`test:telemetry-live`通过。清理临时元数据、保留运行审计，业务源20条、V11未改。运行命令和证据见[接入规范](DEMAND_REST_INTEGRATION.md)、[交付](V05_REST_INTEGRATION_CHANGELOG.md)。不计为Kafka消费或需求方采集已完成。

## 2026-10-09 资源版本历史

完整`test-local.ps1 -Offline`：Foundation90、Provider23，共113项，0失败/错误/跳过；新增6项真实MySQL历史集成测试覆盖四种写入、历史不可变、版本冲突、快照失败回滚、并发单一快照、只读与分页、缺失历史、草稿级联和V11真实回填SQL。首次运行发现新夹具参数数量及V10旧表清单断言，已修复完整重跑，失败轮次不计通过。

前端`npm test`107项，`npm run build`成功，主包体积提示保留。真实`test:history-live`最终重跑通过（中文状态标签、24个版本分页、字段检索、四种真实操作、历史不变、读失败/重试、晚到响应及独立演示）；实际截图`foundation-web/.runtime/history-real.png`视觉检查。原`test:schema-comparison-live`、`test:resource-live`、`test:access-live`、`test:smoke`通过，资源并发与共享授权/自动摘要未受影响。

Foundation打包重启（跳过测试只生成产物），Provider保持旧产物；实际开发库V11，迁移前1个资源V2/字段与迁移后逐项一致，只留下V2基线。临时来源及孤立历史0，原Source仍20条；历史新表7列均有中文备注，两服务health code=0。日志外层`.runtime/history-backend.log`、`history-package.log`、`history-start.log`及前端`.runtime/history-*.log`；接口和使用见[交付记录](RESOURCE_HISTORY_CHANGELOG.md)。

## 2026-10-07 资源结构只读比较

最终完整`test-local.ps1 -Offline`：Foundation84项、Provider23项，共107项，0失败/错误/跳过；日志外层`.runtime/schema-backend-final.log`。新6项真实MySQL集成测试覆盖技术属性全部比较、改名、表注释、未知范围、残缺/错配快照及并发删除。临时表在`another_source_test`，不改`container_event`；资源、字段业务属性、版本、能力、关系/策略在比较前后逐项不变。

`npm test`101项通过，`npm run build`成功，既有主包体积警告保留。新版Foundation实际浏览器`test:schema-comparison-live`通过：临时表真实改结构重扫、无变化、中文筛选/展开、10/20/50分页及20项候选、真实失败清空/重试、任务/页签/资源旧响应隔离、未知统计、独立演示和旧演示无基准。大列表和错误/延迟场景使用只读HTTP夹具，不算实际数据库结构变化证据。原`test:resource-live`、`test:access-live`、`test:smoke`通过；真实共享允许/拒绝/撤销、自动事件/追踪与分钟数量字节核对正常。日志见前端`.runtime/schema-*.log`，实际比较截图`schema-comparison-real.png`已视觉核对。

联调曾发现演示未变化计数错误、对已有任务表字段数量的错误假设，以及并行浏览器临时表影响已有共用测试库断言。已修正，并对同库夹具串行执行后完整重跑；失败轮次未计为通过。最后Foundation离线打包重启（打包跳过测试只用于生成产物），Provider保持既有产物。临时登记按编号/凭据/随机名称受保护清理，临时业务表0、临时来源0，广州港源仍20条，数据库V10，两服务health code=0。详见[结构比较交付](SCHEMA_COMPARISON_CHANGELOG.md)。

## 资源与字段并发编辑（2026-10-06）

完整`test-local.ps1 -Offline`：Foundation78项、Provider23项，合计**101项通过，0失败、0错误、0跳过**；资源集成13项，新增3项真实MySQL测试覆盖遗漏/非法/过期版本、无写入和版本不增长、字段不存在不增长、同版本字段及资源/字段同时修改只允许一个成功、发布/删除互斥。日志`.runtime/version-backend.log`。初次运行MySQL未启动，复用既有配置启动后完整执行；未将环境失败计为通过。

前端`npm test` **93项通过**，`npm run build`成功，既有主包体积提示保留；日志`foundation-web/.runtime/version-web-tests.log`及`version-web-build.log`。`test:resource-detail-live`和`test:resource-registration-live`原专项通过，HTTP夹具白名单断言补充版本，夹具写入不作为真实落库证据。`test:smoke`原演示流程通过。

`test:resource-live`实际两个浏览器页面读取同版字段、一个成功保存后另一个409；较新内容保留，过期输入仍在、保存停用、取消刷新能重新编辑。实际资源信息编辑冲突、提交成功响应丢失后的手工再提交不重复写入、所选扫描绑定与原字段维护/发布/能力登记闭环通过。初期新页签上下文/登录及第二编辑者白名单请求错误已修正，完整重跑通过，不改生产登录或放宽请求校验。截图`resource-live-field-conflict.png`、`resource-live-edit-conflict.png`已视觉检查。`test:access-live`实际允许/拒绝/撤销及Provider自动摘要、追踪/分钟计量与返回数量/字节核对通过；日志`version-resource-live.log`、`version-detail-ui.log`、`version-registration-ui.log`、`version-access-live.log`和`version-smoke.log`。

完整测试后Foundation离线打包并启动，Provider保持既有JAR；打包跳过测试只用于生成产物。临时登记按精确编号/随机清单及引用保护清理，原始元数据、业务行与运行摘要保留；核对源库20条、临时来源0条、数据库V10及两服务health code=0。日志`.runtime/version-package.log`、`version-start.log`。没有新迁移或独立Demand/CDC开发，见[并发编辑交付记录](RESOURCE_EDIT_CONCURRENCY_CHANGELOG.md)。

## 资源绑定所选扫描快照（2026-10-06）

完整`test-local.ps1 -Offline`重跑：Foundation75项、Provider23项，合计**98项通过，0失败、0错误、0跳过**。新增3项真实MySQL测试覆盖较新扫描存在时仍复制旧选定快照（用不同字段类型验证）、来源/状态/模式/表/遗漏/无效/删除任务拒绝、字段缺失事务回滚、编辑不可换绑、引用删除保护，以及登记/删除并发。日志工作区`.runtime/snapshot-backend.log`。

首轮完整回归资源10项通过，但既有摘要“未来窗口拒绝”测试在分钟末尾偶发失败：下一分钟开始落入允许5秒时钟偏差。改为由单一当前时间构造两分钟后的非法窗口，再完整重跑通过，生产时间规则未改。初次运行时本机MySQL未启动，复用既有配置启动后执行，不将环境失败或首次测试失败计为通过。

前端`npm test` **91项通过**，`npm run build`成功，既有主包体积提示保留。`test:resource-registration-live`验证正确提交所选扫描编号、不可编辑替换及原故障/旧响应保护；`test:resource-live`实际打开第二次扫描的登记表单，再完成第三次扫描，保存返回的绑定仍为第二次，详情来源与点击跳转一致。原字段编辑、发布、能力登记、网络失败不模拟成功、已提交响应丢失不重发通过。`test:access-live`真实授权允许/拒绝与自动采集回归通过；`test:smoke`原演示流程通过。

Foundation经完整测试后打包并重启，Provider保持既有JAR。打包`-DskipTests`仅生成产物；开发库仍V10，无新迁移或存量重绑。临时测试元数据按来源编号/随机清单与引用保护清理，源库20条、临时来源0条、资源任务/来源错配0条，运行摘要保留。日志前端`.runtime/snapshot-*.log`及工作区`.runtime/snapshot-package.log`、`snapshot-start.log`。详见[交付记录](RESOURCE_SCAN_SNAPSHOT_CHANGELOG.md)。

## V0.4自动采集部署与中文监管联调（2026-10-06）

采集Java代码未改，复用2026-10-05完整95项后端回归（Foundation72、Provider23，0失败/错误/跳过）。停止已记录的两服务后离线`mvn package -DskipTests`成功，重新启动Foundation与Provider健康正常；打包跳过测试不作为测试证据。日志位于工作区`.runtime/v04-collector-package.log`和`v04-collector-start.log`。

- `npm test`90项通过，新增10项监管契约测试验证查询范围、可信归属、非法摘要、分页重复、消息回执、未知/零/下限和统计单位；`npm run build`成功，既有主包体积提示仍保留。日志`foundation-web/.runtime/v04-web-tests.log`、`v04-web-build.log`。
- `test:access-live`通过真实前端签发令牌、直连Provider查询Source模拟数据；允许/越权字段与时间/暂停与撤回/无效及撤销凭据逐次核对自动上报后的唯一事件与追踪、状态、真实成功条数及UTF-8响应字节。窗口数量/字节等于落库事件汇总，未观察到的对象Kafka计量为未知。不是手工上报接口的演示。日志`foundation-web/.runtime/v04-access-telemetry-live.log`。
- `test:telemetry-live`先用实际自动采集事件核对详情治理版本、追踪跳转和真实分钟卡片，再用只读HTTP夹具验证45条服务端分页/筛选、错配事件/追踪拒绝、未知载荷不展示、详情重试、关闭抽屉隔离、未知/零/部分下限、REST次数与成功消息单位、采集状态独立故障及退出后的旧响应。全部GET，无数据库写入。日志`foundation-web/.runtime/v04-telemetry-ui.log`；初期中文关闭按钮/Select定位及Provider代理路径拦截错误修正后完整重跑通过。
- `test:smoke`原完整演示回归通过，日志`foundation-web/.runtime/v04-smoke.log`。
- 只读真实页面截图`foundation-web/.runtime/telemetry-audit-real.png`、`telemetry-trace-real.png`、`telemetry-metrics-real.png`含导航，已视觉检查布局。
- 16组历次随机`UI_SHARE_`清单受保护清理，未删除业务行或运行摘要；源库20条、临时共享测试来源0条、实际运行摘要12条、V10。清理后按原关系仍读到11条历史事件。

Kafka自动生产的实际Broker回执、接收端503不阻断已获准Source查询、队列/磁盘故障及重启补报由此前95项测试覆盖；本次不将HTTP界面夹具称为开发服务的实际故障演练。本机无常驻Kafka消费链路、多节点和完整分布式追踪；不能宣称无损采集。详见[交付记录](RUNTIME_TELEMETRY_CHANGELOG.md)。下文按日期保留历史验收状态。

## 前端说明文字精简（2026-10-05）

清理首页、数据源、扫描、资源目录与详情、能力、主体、关系、策略、凭据、调用验证、系统说明及登录/导航中的重复解释；业务数据、筛选、输入、校验和操作逻辑保持原有行为。删除说明节点并同步清理对应样式，演示模式使用简短标签；保留必要的失败、状态限制、范围不完整和令牌一次性提示。原浏览器测试改为检查演示标识与实际状态标签，不再依赖已删除的长说明。

- `npm test`：80项通过，0失败/跳过；日志`foundation-web/.runtime/ui-cleanup-test.log`。
- `npm run build`：成功；日志`foundation-web/.runtime/ui-cleanup-build.log`。既有主包体积警告仍存在。
- `test:smoke`：登录、首页、数据源弹窗、扫描字段、资源Tabs、能力弹窗及扫描→资源→能力操作通过。
- `test:capability-workflow-live`：真实读取、受支持操作边界、输入锁定、状态刷新、故障及旧响应隔离通过；写入由独立HTTP夹具拦截。
- `test:source-overview-live`：真实来源关联、独立错误与重试、有界读取、关闭切换隔离通过；仅GET。
- 追加只读浏览器核对11个真实API页面和新增数据源弹窗，页面运行错误为0；截图`foundation-web/.runtime/ui-clean-*.png`，已视觉检查数据源弹窗及资源目录布局。
- `git diff --check`通过。本次不修改后端或数据库，不把UI精简计为新业务模块完成。

## V0.4自动采集代码回归（2026-10-05，尚未完成运行环境联调）

完整`test-local.ps1 -Offline`重跑：Foundation72项、Provider23项，合计**95项通过，0失败、0错误、0跳过**，日志`.runtime/v04-provider-backend.log`。新增测试核对真实Source查询的返回数量和UTF-8响应字节、服务端生成的追踪编号、可信治理身份、查询失败摘要、HEAD不计量、嵌入式Kafka实际消息回执、异步落盘、失败重试、重启补报、队列满和存储故障。测试摘要不包含业务值、SQL或凭据。

首轮采集故障测试失败：构造时启动的后台线程与Mockito spy读取的计数不一致。改为注入真实采集器，并按响应追踪编号读取实际持久化报告进行核对，完整重跑通过。当前开发服务仍运行此前JAR；这些测试结果不代表新采集代码已经打包部署，也不代表真实Foundation与Provider自动采集联调或V0.4整体完成。前端本轮未修改、未重复构建。

## V0.4底座摘要接收与查询（2026-10-05）

完整`test-local.ps1 -Offline`使用独立`foundation_test`、`port_source_test`及嵌入式Kafka：Foundation71项、Provider13项，合计**84项通过，0失败、0错误、0跳过**。日志`.runtime/v04-backend.log`，新增`RuntimeTelemetryIntegrationTest`的8项测试覆盖服务间凭据、拒绝自报节点/业务行/令牌字段、严格整数与窗口/版本/原因校验、报告及跨报告事件去重、乱序重试、冲突整事务回滚、并发不同报告与同报告重试、未知/部分/关闭空窗口/延迟、调用与消息独立单位、历史版本、事件与追踪筛选及分页。

首轮真实并发测试暴露重复键共享锁升级死锁，取消不可变历史记录上的多余`FOR UPDATE`后完整重跑通过；不将首次失败算作验收。测试报告与事件按随机编号及节点条件清理，实际复核测试表均为零。数据库迁移测试同时验证V10、重复迁移为零、底座无业务表和全部自建字段注释。

停止占用JAR的本机服务后离线打包成功，再启动两服务；首轮打包因Windows运行中JAR重命名被锁失败，已修复流程。打包使用`-DskipTests`，它只用于产物生成，84项完整测试证据来自此前实际测试而非跳过记录。开发库实际V10，新表35列注释完整，源库20条模拟事件保持不变；运行OpenAPI包含上报、事件、追踪和窗口接口，事件列表为空，15个分钟窗口均为MISSING且数量为null。

本轮未改前端及Provider业务代码，没有重新执行前端测试或自动采集联调。旧前端80项及构建结果仅为原V0.3版本记录。V0.4自动上报、补报、中文页面仍待后续完成，详见[接口说明](RUNTIME_TELEMETRY_API.md)。

## V0.3调用凭据与访问校验（2026-10-04，最新）

完整`test-local.ps1 -Offline`实际执行独立MySQL与嵌入式Kafka测试：Foundation63项、Provider13项，合计**76项通过，0失败、0错误、0跳过**。覆盖随机凭据与摘要、不回显及签发no-store、角色/状态/过期、自报主体拒绝、服务间鉴权、关系流转/状态/有效期、允许字段与筛选字段、时间范围与UTC/+08转换、来源/资源/能力状态、凭据撤销/版本/历史及主体删除保护。Provider实际查询测试核对字段投影和时间窗口；故障测试校验错误/过期/缺失决策、重定向和控制不可用时拒绝并不执行业务查询。

`npm test` **80项通过**，`npm run build`成功，保留既有主包体积提示。真实`test:access-live`签发令牌并从Provider查询源库模拟事件：默认只返回允许的两列，08:10–08:20仅一条事件，越权字段与时间403，关系暂停/撤回403，无效/撤销凭据401；令牌只在内存与签发弹窗，不自动复制或写浏览器存储，切换页面后旧响应不展示。初期UI定位及延迟响应夹具错误修复后完整重跑成功，未将失败当作验收。

原`test:capability-live`改为建立真实共享关系与凭据后验证REST启用/停用/恢复和来源状态；已通过。`test:sharing-live`原确认状态机/策略/历史及故障回归、`test:smoke`原演示链路与显式mock下凭据/调用不执行真实请求、`test:dashboard-work-live`首页待处理统计均通过。能力HEAD验证要求共享运行时标识，缺少标识的旧Provider验证失败且不能启用，完整后端及真实REST专项再次通过。两服务重新打包启动，实际OpenAPI含访问校验/调用凭据接口，开发库实际V9、源库仍20条模拟事件、新表字段备注完整。按15份随机测试清单核对清理；增加凭据清理保护并实测修改凭据名称时拒绝，恢复后成功。详见[交付记录](ACCESS_CHANGELOG.md)。

## V0.3关系与策略第二阶段（2026-10-04，此前）

独立MySQL与嵌入式Kafka完整`test-local.ps1 -Offline`：Foundation55项、Provider7项，合计**62项通过，0失败、0错误、0跳过**。8项新增关系测试覆盖确认/退回/启用/暂停/撤回历史、角色/归属/字段及两类时间范围校验、通配符拒绝、草稿冻结、来源/主体/能力状态、删除引用保护、过期/未来生效、并发版本及分页。V8迁移与全字段注释检查通过。SQL夹具的能力状态用于关系逻辑验证，不代表已验证Provider实际共享授权。

前端`npm test`**72项通过**、`npm run build`成功。`test:sharing-live`由真实接口准备独立临时来源、扫描、发布资源、标记时间字段、验证/启用REST能力与双主体，再由浏览器完成关系登记、退回修改、确认、启用、暂停、恢复、撤回和策略/历史查看；核对关系版本10、策略版本2、10条历史。HTTP故障夹具验证写入不重试、版本冲突、输入锁定、模式拒绝、退出后的旧响应和读取失败清空。真实与夹具操作明确分开，页面无运行异常；截图已检查。

`test:smoke`与真实`test:capability-live`回归通过。新增清理脚本以事前记录的32位随机标识、精确名称及关联条件为保护，清理测试元数据，原数据与20条模拟业务事件保留。开发库已迁移V8并重新打包启动。初次测试夹具编码、原生fetch调用和UI定位错误均已修复后重跑，不使用失败或跳过测试作完成证据。详情见[交付记录](SHARING_CHANGELOG.md)。

共享策略运行时执行尚未实现；上述验证不证明关系撤回后Provider业务查询被拒绝，后续需可信身份与实际字段/时间限制联调。

## V0.3共享主体第一阶段（2026-10-04，此前）

`scripts/test-local.ps1 -Offline`实际运行独立MySQL测试库和嵌入式Kafka：Foundation44项、Provider7项，合计**51项，0失败、0错误、0跳过**。新增6项覆盖持久保存和身份字段不变、重复编码/客户端控制属性拒绝、启停和旧版本拒绝、非法输入/未知属性/缺版本、分页与不存在记录、并发更新恰好一个成功。V7迁移和全部表字段中文注释通过，测试未使用开发业务库。

`npm test` **62项通过**；新增5项覆盖编辑载荷白名单、输入与版本校验、真实/演示响应和当前操作归属、中文组合筛选、独立演示持久保存/重复与旧版本拒绝。`npm run build`成功，保留已有Element Plus主包超过500kB的提示。`test:smoke`完整原演示链路和显式演示主体页通过。

`test:subject-live`先通过实际前端在开发库创建、编辑、停用随机编码的测试主体，核对API持久数据和版本3，再用HTTP夹具覆盖451项跨页、检索/角色筛选、版本冲突、请求结果未确认时不自动重试、读取错误清空/禁止新增、混合模式拒绝、5,000项范围提示、退出页面后的旧读取/写入隔离和提交锁定。夹具写入拦截，浏览器页面无运行异常。

联调发现Vue响应式记录无法直接structuredClone，改为先toRaw再生成操作快照；回归通过。另修正测试夹具的责任单位、Element Plus下拉点击位置和HTTP拦截路径匹配，不将脚本失败记为产品通过。实际截图`foundation-web/.runtime/subject-real.png`已检查。

开发服务已重新打包启动，Foundation/Provider健康正常；V7已应用。测试主体按`subject-live-fixtures.json`随机编码清单执行`cleanup-subject-ui-test.ps1`清理，保留原来源/资源/业务行。真实主体库不自动植入示例组织。当前只完成共享主体基础管理，共享关系、策略和身份授权未交付。

## 数据源概览（最新，2026-10-04）

`npm test` **57项通过**，新增5项覆盖显示字段白名单、来源筛选/排序、异常结构/重复/模式、有界跨页读取与故障隔离、显式演示不访问真实接口。`npm run build`成功，保留已有主包体积提示。

`test:source-overview-live`先核对实际来源关联任务/资源数量，再使用HTTP夹具验证451条全局记录跨页读取与来源隔离、最近5项、密码及凭据引用不显示、资源/任务/来源目录跳转、区域失败/重试、模式错配拒绝、5,000条范围说明、关闭重开及离开页面后的旧响应隔离。全部GET，未查询业务数据或写数据库。首次专项使用错误的资源数量定位标记、随后未等待目录读取完成，修正测试定位及等待条件后通过；截图原先截到抽屉进入过渡中间态，补充等待过渡结束后重跑并检查完整截图。

`test:source-catalog-live`原列表451条及编辑/连接/启停/提交保护回归通过，夹具写入拦截；`test:smoke`新增显式演示概览与关联资源展示检查，完整链路通过。没有修改Java、Provider、数据库，不重复Maven或真实写入测试；V0.3保持暂停。见[概览补充记录](SOURCE_CATALOG_CHANGELOG.md#数据源概览补充2026-10-04)。

## 首页待处理事项（此前，2026-10-04）

`npm test` **52项通过**，新增4项覆盖分类口径、独立错误/范围及模式、现有页面处理入口与能力适配器限制、组合检索/排序/分页。`npm run build`成功，保留已有主包体积提示；表格高度调整后最终构建通过。

`test:dashboard-work-live`先核对真实API三类待处理数量，再用HTTP夹具验证25个草稿资源、24项草稿能力、12项失败任务的分页、组合检索、时间排序、三个已有页面入口、CDC验证支持限制、接口失败时分类清空及其他分类可查、恢复、发布后不再列为草稿、5,000条范围提示、外来演示标记拒绝、退出时忽略旧响应。全部GET，无数据库写入，截图已检查。

原`test:dashboard-live`真实首页六项数字、451条跨页、故障恢复、读取上限通过；`test:smoke`完整演示流程通过。本次仅改前端与文档，未重复真实写入或Maven测试，未修改Java、Provider、数据库及V0.3范围。见[首页补充记录](DASHBOARD_CHANGELOG.md#首页待处理事项补充2026-10-04)。

## 扫描结果分页与响应隔离（此前，2026-10-04）

`npm test` **48项通过**，新增5项覆盖表/字段响应结构、明确归属及模式、重复名称、当前候选表与扫描返回任务校验、大列表分页。`npm run build`成功，保留已有主包超过500kB的提示。

`test:scan-catalog-live`通过：真实读取核对、451条任务/来源、25张表/125字段的分页/搜索/切换重置、筛选后原顺序、错误/重复/混合模式拒绝、扫描返回来源错配拒绝、切换任务与同页面入口及退出时旧成功/失败响应隔离、正常扫描自动加载。HTTP夹具写入全部拦截。首次新增场景因前置来源读取失败后选择被清空，脚本未重新选择来源而超时；补上选择步骤后通过。

`test:smoke`完整演示流程、`test:resource-live`真实扫描→资源/字段维护→发布→登记能力回归通过。`test:source-live`真实两库回归通过，并新增12字段第一页10条/第二页2条检查。1组资源临时元数据与2组来源临时元数据按清单清理。最终原有来源2条、临时来源0条、Source业务行20条、迁移V6，底座无业务明细表。截图已检查；Java未改，不重复Maven；V0.3继续暂停。见[扫描工作台补充记录](SCAN_WORKBENCH_CHANGELOG.md#扫描结果分页与扫描响应隔离补充2026-10-04)。

## 扫描来源核对与资源登记（此前，2026-10-04）

`npm test` **43项通过**，新增5项覆盖扫描入口/任务/来源/候选表及模式、重复查询、创建/编辑上下文、参数边界。`npm run build`成功，保留已有主包体积提示；补充继续登记入口后最终构建通过。

`test:resource-registration-live`确认真实读取后用HTTP夹具验证错误来源/表/缺失任务禁止写入、权威来源名称、失败重试、重复查看、旧核对/保存/发布隔离、输入锁定、防重复保存、失败保留输入、提交白名单、浏览器存储不变、编辑版本及主动继续登记新表；全部夹具写入拦截。原`test:resource-catalog-live`回归通过。

`test:smoke`原先重复登记预设表被阻止，调整为扫描新建演示来源后完整流程通过。`test:resource-live`真实核对→创建/编辑/字段维护→发布→登记能力及响应丢失不重复提交通过。1组临时元数据已清理；最终原有来源2条、临时来源0条、Source业务行20条、V6，底座无业务明细表。截图已检查，Java未改，未重复Maven；V0.3继续暂停。见[资源登记交付记录](RESOURCE_REGISTRATION_CHANGELOG.md)。

## 接口能力操作流程与状态说明（此前，2026-10-04）

`npm test` **38项通过**，新增5项覆盖操作状态/适配器门禁、编辑上下文、提交白名单与参数上限、状态说明语义。首次白名单数量预期误计，修正后通过。`npm run build`成功，保留已有主包体积提示；`test:smoke`通过。

`test:capability-workflow-live`验证真实读取，随后用HTTP夹具覆盖不支持操作不发请求、输入锁定、防重复保存、失败保留输入不写演示存储、切换/退出时旧保存与启停响应隔离、详情快照刷新、错配/失败调用说明、停用验证不自动启用、系统说明支持范围。精确标题选择器修正后通过，全部夹具写入拦截。原`test:capability-catalog-live`回归通过。

`test:capability-live`真实REST200/403/200、说明/复制/重试、来源停用拒绝回归通过。1组临时元数据清理，最终来源2条、临时来源0条、Source业务行20条、V6，底座无业务明细表。截图已检查，未修改Java或重复Maven，V0.3继续暂停。见[能力流程交付记录](CAPABILITY_WORKFLOW_CHANGELOG.md)。

## 资源详情工作台（此前，2026-10-04）

`npm test` **33项通过**，新增5项覆盖字段组合筛选、详情结构/归属/模式、字段提交白名单与参数校验、编辑上下文、分页修正。`npm run build`成功，保留已有主包体积提示；`test:smoke`完整演示流程通过。

`test:resource-detail-live`核对真实字段数量，使用155字段/61能力HTTP夹具验证筛选与分页、慢请求区域独立、错误和单独重试、切换资源的旧读取/保存/发布响应隔离、重复提交拦截、失败保留输入及不写演示存储、字段输入锁定、提交白名单和关联能力导航。全部夹具写入拦截。选择器匹配歧义与夹具跨资源误标记导致前两次失败，修正专项后通过。

`test:resource-live`真实扫描→资源与字段维护→发布→登记能力及响应丢失不自动重发通过。1组临时元数据清理；最终原有来源2条、临时来源0条、Source业务行20条、V6且底座无业务明细表。截图已检查，未修改Java或重复Maven测试，V0.3继续暂停。见[资源详情交付记录](RESOURCE_DETAIL_CHANGELOG.md)。

## 数据源检索与操作可靠性（此前，2026-10-04）

`npm test` **28项通过**，新增4项覆盖数据源跨页、失败不切演示、组合筛选、配置校验与空密码编辑、编辑模式/范围。`npm run build`成功，保留已有主包体积提示；`test:smoke`通过。

`test:source-catalog-live`核对真实数量并用451条HTTP夹具验证检索/分页、停用保护、连接错误清除旧成功信息、重复操作拦截、失败保留输入且不写浏览器存储、密码取消清空、空密码编辑和提交体边界、首/次页故障恢复，以及页面离开后晚到扫描不导航。全部夹具写入拦截。初次后端未运行、后续子路径未拦截分别导致验证失败，恢复服务并修正专项后通过。

`test:source-live`真实登记、连接、空密码编辑、启停及两库12/3字段隔离通过。2组测试临时元数据已清理；原有2条来源、0条临时来源、20条Source业务行、V6，底座无业务明细表。截图已检查，没有修改Java或重复Maven测试，V0.3继续暂停。见[数据源管理交付记录](SOURCE_CATALOG_CHANGELOG.md)。

## 元数据扫描工作台（此前，2026-10-04）

`npm test` **24项通过**，新增4项覆盖任务/来源跨页、错误隔离、组合检索和来源模式校验。`npm run build`成功，保留已有主包体积提示；`test:smoke`通过。`test:scan-catalog-live`验证真实任务数量及451条HTTP夹具的任务/表/字段筛选、状态刷新、旧请求隔离、关闭重开字段抽屉、指定任务读取/路由变化、故障重试和扫描重复提交保护，夹具写入全部拦截。

`test:resource-live`真实扫描→资源/字段维护→发布→登记能力通过，`test:source-live`真实两库12/3字段隔离及原接入回归通过。测试脚本适配加载中抽屉，并提前记录临时来源清理身份；中断测试遗留记录已按明确编号与引用恢复清单并清理。截图已检查，未修改Java或V6迁移，V0.3继续暂停。失败原因、复现及清理步骤见[扫描工作台交付记录](SCAN_WORKBENCH_CHANGELOG.md)。

清理复核修复PowerShell 5.1多项JSON清单嵌套包装导致误报成功的问题，实际双项清单验证通过；数据库最终为原有2条来源、0条临时来源、20条Source业务行、V6且底座无业务明细表。

## 接口能力检索与操作可靠性（此前，2026-10-03）

`npm test` **20项通过**，新增4项覆盖能力与资源跨页、失败区域不混入演示、组合筛选和真实/演示保存范围。`npm run build`成功，保留已有主包体积提示；`test:smoke`通过。

`test:capability-catalog-live`核对真实API数量，并用HTTP响应夹具验证451条能力/资源、筛选与分页、仅选择已发布资源、同编号旧调用说明隔离、重复提交拦截、保存失败保留输入及不写演示存储、资源/能力第二页故障隔离和刷新恢复。夹具写入全部拦截，无数据库写入。列表与弹窗截图已检查。

`test:capability-live`真实REST注册、验证、启用200→停用403→再启用200、真实调用地址/请求头/复制命令、说明失败后重试和Source停用拒绝通过。1组测试临时元数据已按清单清理；原有数据源2条、临时来源0条、Source业务行20条、V6，底座无业务明细表。Java未修改，未重复Maven测试；V0.3继续暂停。详见[能力管理交付记录](CAPABILITY_CATALOG_CHANGELOG.md)。

## 资源目录检索与分页（此前，2026-10-03）

`npm test` **16项通过**，新增4项覆盖多字段/组合检索、网址参数、页码修正、演示回退边界。`npm run build`成功；目录专项`test:resource-catalog-live`核对真实API并用HTTP响应夹具验证451条跨页、组合筛选、分页、网址刷新/详情返回、无结果、首/次页读取失败、恢复及5,000条上限，无数据库写入。首次HTTP拦截模式未覆盖详情子路径导致超时，修正后通过；补充详情返回按钮验证后再次通过。

`test:smoke`完整演示流程、`test:resource-live`真实创建/字段维护/发布/登记能力与失败不mock流程均通过。1组真实测试临时元数据已通过固定清单清理；数据源2条、临时来源0条、Source业务行20条、迁移V6，底座无业务明细表。目录截图已检查。Java没有修改，未重复Maven测试；V0.3保持暂停。见[交付记录](RESOURCE_CATALOG_CHANGELOG.md)。

## 首页统计可靠性（此前，2026-10-03）

本轮仅修改前端，未修改后端或数据库。`npm test` **12项通过**（6项新增首页测试及6项既有测试），`npm run build`成功，保留已有主包体积提示。`test:smoke`完整演示流程通过；`test:dashboard-live`核对真实API六项统计，并用HTTP响应夹具验证451条资源/能力跨页、205条任务、第二页故障、旧数清除、刷新恢复及5,000条统计上限，未写数据库。专项首次未通过演示入口导致超时，修正测试登录步骤后通过；未修改路由保护。

新增单元测试覆盖分页边界、恰好达到上限与超出上限、重复编号、响应格式异常、第二页失败、北京时间午夜、来源停用/资源未发布排除以及按创建时间排序。真实首页与故障截图已检查。没有重复执行后端测试，V6与V0.3暂停状态不变。详见[交付记录](DASHBOARD_CHANGELOG.md)。

## 资源前端流程可靠性（此前，2026-10-03）

本轮未修改Java或数据库迁移，主要验证前端和真实API操作，未重复运行此前已通过的45项后端回归。`npm test` **6项通过**（3项新增资源/演示测试和3项既有能力测试）；`npm run build`成功，保留现有大分包提示。

`test:smoke`通过，模拟流程现在先发布草稿再登记能力；字段演示数据按资源隔离。`test:resource-live`通过：真实扫描来源只读，切换任务后晚到字段响应不能重新打开旧抽屉；创建、修改、字段保存和发布失败不写mock；真实字段读取503显示错误而不借用演示字段。模拟“服务端已提交、客户端响应丢失”，确认不自动重复提交，弹窗保留输入，刷新显示实际字段与版本；正常字段保存、资源更新、发布后版本增加且能力可登记。初次按钮精确名称、第二次隐藏开关输入定位失败，修正为匹配可见控件后完整流程通过。

由于修改了扫描任务读取方式，再执行`test:source-live`，验证port_source_test/container_event的12字段与another_source_test/train_dispatch的3字段隔离，以及原有数据源登记、测试、空密码编辑和启停，全部通过。两种专项测试的临时元数据由编号/名称/凭据引用校验脚本清理，保留原有记录和Source业务行。

截图已人工检查：`foundation-web/.runtime/resource-live-detail.png`、`resource-live-fields.png`。日志为`foundation-web/.runtime/resource-live.log`、`resource-source-regression.log`。本轮无需打包或重启后端；两服务正常运行，数据库仍为V6。代码范围和复现命令见[资源流程交付记录](RESOURCE_FLOW_CHANGELOG.md)。

## 能力登记与调用指引（此前，2026-10-03）

完整`scripts/test-local.ps1 -Offline`：Foundation **38项**、Provider **7项**，合计 **45项通过，0失败、0错误、0跳过**。新增Provider地址边界单元测试3项，拒绝包含凭据、子路径、查询参数、片段、错误端口及协议不匹配的配置，错误不回显配置。新增真实MySQL/HTTP集成场景验证调用说明的完整地址和请求头、草稿/启用/停用可用标志、404与不支持类型，并确认读取说明不发HEAD、不修改版本、不返回密码或业务字段。

前端`npm test` **3项通过**，验证相对路径、分类型字段及PowerShell转义；`npm run build`成功（保留现有大分包提示）。`test:smoke`通过，额外检查示例填入、REST/Kafka认证选项隔离、CDC来源表只读；初次因Element Plus输入框覆盖层导致定位超时，改用键盘打开下拉后完整重跑通过。`test:capability-live`通过，实际后端地址与复制命令一致，真实读取失败不回退模拟说明，重试恢复；原有Provider 200→403→200和Source停用拒绝继续通过。浏览器截图已人工检查，测试临时元数据由校验脚本清理。

Foundation打包并重启，Provider与MySQL保持运行，两服务health正常；数据库仍为V6。执行时沙箱限制了Vite配置与Maven缓存读取，使用审批后的构建/测试命令完成；自动审批服务连接故障后重试成功。Windows运行中JAR无法改名，停止已记录的Foundation进程后打包成功。以上环境失败不算代码测试通过记录。

日志：`.runtime/invocation-tests.log`、`.runtime/invocation-package.log`、`foundation-web/.runtime/invocation-live.log`。截图：`foundation-web/.runtime/capability-registration-help.png`与`rest-capability-detail.png`。接口和文件清单见[本轮交付记录](CAPABILITY_GUIDE_CHANGELOG.md)。

## REST运行时启停（此前，2026-10-03）

完整`scripts/test-local.ps1 -Offline`：Foundation **34项**、Provider **7项**，共**41项通过，0失败、0错误、0跳过**。Foundation新增验证动态Source的REST生命周期、旧运行时Provider拒绝、启用前重新探测、来源停用和地址不匹配、同状态幂等及并发状态变化不被覆盖；Kafka既有集成验证保持通过。Provider新增真实HTTP控制元数据检查：缺失编号400、停用/未知能力/错误来源403、控制服务不可达或无效响应503，使用服务Spy确认这些拒绝请求没有读取Source业务行；恢复启用后真实查询成功。额外单元测试验证配置边界、路径注入拒绝、禁止重定向和网络失败拒绝访问。

后端两个服务打包成功并已重启，本机健康接口正常；数据库无新增迁移，仍为V6。前端最终`npm run build`、`npm run test:smoke`和`npm run test:capability-live`通过。真实页面验证动态Source→扫描container_event→发布资源→注册、验证、启用REST→Provider 200→停用403→再启用200，验证Source停用拒绝和网络操作失败不模拟成功。浏览器测试初次因中文弹窗关闭定位、随后因超时提示断言过窄失败，均修复；最终完整流程通过。每次临时UI_REST_元数据均经清理脚本校验移除，保留原有记录和Source业务行。

文件、调用方式与限制见[REST启停交付记录](REST_RUNTIME_CHANGELOG.md)。截图在`foundation-web/.runtime/rest-capability-*.png`，日志为`.runtime/rest-lifecycle-tests.log`、`.runtime/rest-lifecycle-package.log`及前端`.runtime/rest-live.log`。

## 字段备注补充（此前，2026-10-03）

V6 追加 Flyway 迁移为 Foundation 自建表缺失的字段补充中文备注，不修改已有 V1–V5。`scripts/test-local.ps1 -Offline` 完整回归：Foundation 32 项、Provider 3 项，合计 35 项通过，0 失败、0 错误、0 跳过。新增集成断言检查 85 个自建表字段无空备注；原 Source 示例表测试继续检查 `container_event` 的 12 个字段全有备注。测试库与本地开发库迁移至 V6，本地开发库原有 2 条 Source 记录保留，Foundation 打包和启动后的健康检查通过。Flyway 自身维护的 `flyway_schema_history` 不纳入项目字段备注断言。

## 动态多数据源接入（此前，2026-10-03）

`scripts/test-local.ps1 -Offline` 完整测试：Foundation 32 项、Provider 3 项，合计 **35 项通过，0 失败、0 错误、0 跳过**。动态 Source 专项 5 项覆盖两个真实独立 MySQL 库：port_source_test/container_event/12 字段，another_source_test/train_dispatch/3 字段；第三次登记不需要新增 Java 或环境变量组。验证扫描隔离、id 主键、字段发现、Foundation 无业务明细表、密码加密与更新、停用拒绝测试/扫描、非法配置不产生孤立凭据及旧记录转换。专项测试在旧引用独立化后再次通过。

新增 2 项加密单元测试验证随机 IV、引用绑定、密文篡改、缺失/错误密钥和内部凭据对象脱敏；3 项元数据连接测试验证连接/语句/结果集关闭和中文失败原因。原有 Source、扫描→资源→能力及 Provider REST/Kafka 测试保持通过，Resource/Capability/Provider 业务代码未修改。

前端最终 `npm run build`、`npm run test:smoke`、`npm run test:source-live` 均成功。真实浏览器覆盖登记、保存并测试、编辑密码为空、启停、12/3 字段扫描隔离、字段抽屉、localStorage 无密码及网络失败不模拟成功。测试临时登记已由 `scripts/cleanup-source-ui-test.ps1` 经标识和名称校验后清理，原有记录与业务表保留。

本机复现完整测试先运行 `scripts/start-local.ps1`，再运行 `scripts/test-local.ps1 -Offline`。脚本创建专用 foundation_test、port_source_test、another_source_test，生成/复用外置凭据主密钥并设置所需测试变量。其他机器手工复现还需提供 TEST_SECOND_SOURCE_DB_URL、TEST_PROVIDER_SOURCE_DB_USER/PASSWORD、FOUNDATION_CREDENTIAL_KEY，并创建第二测试库及只读账号授权；不要用生产库。未设置 RUN_MYSQL_TESTS=true 时仅单元/MVC 测试运行，集成测试跳过，不能视为真实连接验证。

打包成功，本机 Foundation 已迁移至 V5 并重启；旧环境引用仍可使用。本轮检查未发现固定 Source JDBC 地址或外部 Source DataSource Bean，扫描 SQL 仅访问 information_schema，SourceView 无 password，Foundation 运行/专项测试/打包日志未发现测试账号密码或主密钥。源码与浏览器测试截图见 [本次文件清单](DYNAMIC_SOURCE_ACCESS_CHANGELOG.md)。

## V0.2 Kafka 生产与 Topic 验证（历史记录）

2026-10-01 执行 `scripts/test-local.ps1`，独立 MySQL 测试库与嵌入式 Kafka Broker 中 Foundation **22 项**、Provider **3 项**通过，合计 **25 项，0 失败、0 错误、0 跳过**。新增测试从 Provider 向 `port.container.event.v1` 发送一条真实 Source 模拟事件，并由测试消费者核对消息键及 JSON 字段；Foundation 只读取 Topic 元数据，验证能力变为 `VERIFIED/UP`。测试消费者仅存在于测试代码中，Foundation 运行时不消费消息。JDK 17 `mvn package -DskipTests`、前端 `npm run build` 与 `npm run test:smoke` 成功。本机服务重启后 Foundation/Provider 均返回 UP；未配置常驻 Broker 时，发布接口返回 503。集成测试通过不等于已部署常驻 Kafka Broker。


## V0.2 Provider REST 与验证阶段（历史记录）

2026-10-01 执行 `scripts/test-local.ps1 -Offline`，独立 MySQL 测试库中 Foundation **22 项**、Provider **2 项**通过，合计 **24 项，0 失败、0 错误、0 跳过**。Foundation 用真实 HTTP HEAD 探测验证 `VERIFIED/UP` 与失败后的 `DRAFT/DOWN`；Provider 用只读账号直接查询 20 条 Source 模拟事件、校验过滤和分页、检查只读授权。后端 Maven 聚合打包成功，本地服务实际返回 Provider HEAD 204（含标识响应头）和 GET 20 条事件；前端 `npm run build`、`npm run test:smoke` 成功。能力启用、Kafka 和 V0.3 策略不在此阶段。

## V0.2 Capability 登记阶段（历史记录）

2026-10-01 执行 `scripts/test-local.ps1 -Offline`：独立 MySQL 测试库 **22 项通过、0 失败、0 错误、0 跳过**。新增测试覆盖四种类型的 Capability 登记、资源发布前拒绝、重复名称、非法外部 REST 地址、缺少真实适配器时拒绝验证和启用。V4 迁移已在测试库和本地开发库成功；后端打包、Capability 列表 API、前端 `npm run build` 和显式演示模式的 `npm run test:smoke` 均通过。Provider REST、Kafka 和连接探测尚未实现，不计入本次验证。

## V0.1 扫描与资源阶段（历史记录）

2026-09-30 执行 `scripts/test-local.ps1 -Offline`，使用独立 `foundation_test` / `port_source_test` 数据库：**21 项通过、0 失败、0 错误、0 跳过**。新增 `ScanResourceIntegrationTest` 两项，验证真实扫描 `container_event` 的 12 个字段、资源登记/发布、字段业务属性修改、拒绝未扫描表及 Foundation 不保存业务明细。V3 Flyway 迁移在测试库和本地开发库均成功；`mvn package`、前端 `npm run build` 成功。开发库的 health、扫描列表和资源列表接口实际返回 `code=0`。

## V0.1 数据源与适配器阶段（历史记录）

2026-09-22 执行 `scripts/test-local.ps1 -Offline`，使用独立 `foundation_test` / `port_source_test` 数据库：**19 项通过、0 失败、0 错误、0 跳过**。原始日志位于 `.runtime/source-tests.log`，报告位于 `foundation-service/target/surefire-reports/`。

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| ApiContractTest | 9 | 原有接口封装、Validation、异常行为回归 |
| MySqlBootstrapTest | 2 | V1/V2 Foundation 迁移、Source 迁移、20 条数据与应用启动 |
| MysqlConfigurationTest | 2 | JDBC 参数注入、端口/TLS 配置、配置对象不输出密码 |
| SourceIntegrationTest | 6 | CRUD 与稳定 ID、真实连接、错误凭据、表/字段读取、跨库拒绝、参数与未知字段校验、404 与分页校验 |

测试数据源记录在每项测试后清除；Source 模拟数据位于独立测试库。随后 `mvn package -DskipTests` 打包成功（复用已通过的完整测试结果），用于本地实际运行。数据源配置示例见 [SOURCE_API.md](SOURCE_API.md)。以下记录保留早期阶段的执行历史，不代表最新测试数量或表结构。

## 本地无 Docker 启动补充验证

2026-09-22：新增 `start-local.ps1`、`stop-local.ps1` 和独立 Source Flyway 命令。`mvn package` 成功，9 项 MVC 测试通过；本次未开启独立测试库开关，2 项 MySQL 集成测试明确跳过。另实际启动本机 MySQL 和打包后端，验证 Source 两个迁移成功、Foundation 基线成功、health 正常、Swagger HTTP 200、OpenAPI 可用。重复启动时 Source 无新增迁移。实际执行停止和重新启动，验证持久化数据保留。本地开发库与此前 `.runtime/mysql-check` 测试库隔离。

验证日期：2026-09-22。环境：Windows、JDK 17.0.3、Maven 3.9.8、MySQL Community 8.0.33、Spring Boot 3.5.6。

## 实际结果

`mvn test`：**11 项通过，0 失败，0 错误，0 跳过，BUILD SUCCESS**。

| 测试 | 数量 | 验证范围 |
|---|---:|---|
| ApiContractTest | 9 | health 成功封装、有效/无效 Validation、非法 JSON、业务异常、内部异常脱敏、404、405、415 |
| MySqlBootstrapTest | 2 | 真实 MySQL JDBC、完整应用启动、Flyway 基线与重复执行、MyBatis Plus 自动配置、HTTP health、OpenAPI、Source 表结构与 20 条数据 |

Source 验证了四种 event_type、X8021 与其他班列、12 个字段及字段 COMMENT，重复迁移不重复插入。管理库只存在 Flyway 历史表，业务明细只存在 Source 库。

真实数据库验证使用工作区 `.runtime/mysql-check` 下的独立 MySQL 数据目录和临时随机密码，端口 23316；同一测试实例中使用独立 `foundation_db`、`port_source_db` 两个数据库，不访问旧原型 H2 或已有 MySQL 数据目录。Compose 中则为两个独立 MySQL 实例。

完整测试结束后执行 `mvn package -DskipTests` 成功生成 `foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar`；打包阶段复用此前已通过的测试结果。

机器可用的 Java 17 路径是 `D:\Java\eclipse\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_17.0.3.v20220515-1416\jre`（包含 javac）；未修改系统默认 Java 8。

本次从工作区根目录执行的测试命令为：

```powershell
# 先设置下节数据库变量及 JAVA_HOME；本机已有 Maven 缓存位于 .runtime/m2。
mvn -o -gs railway-data-foundation/scripts/maven-settings.xml -s railway-data-foundation/scripts/maven-settings.xml "-Dmaven.repo.local=$PWD/.runtime/m2" -f railway-data-foundation/pom.xml test -B -ntp
```

测试报告：`foundation-service/target/surefire-reports/*.txt`；完整本机日志：`.runtime/mysql-check/maven-test.log`；打包日志：`.runtime/package.log`。这些均为生成文件，不提交。

## 早期 V0.1 测试复现（历史记录）

从新版工程根目录运行。预先在 MySQL 8 中创建两个**专用空测试库**，并授予对应测试账号 DDL/DML 权限。数据库本身属于基础设施引导；表结构和数据由测试调用 Flyway 创建。不要使用已有业务库；测试会向 Source 测试库插入模拟数据，不会自动删除数据库。

通过环境变量提供真实连接信息与凭据：

```powershell
$env:RUN_MYSQL_TESTS='true'
$env:FOUNDATION_DB_URL='jdbc:mysql://127.0.0.1:13306/foundation_test?allowPublicKeyRetrieval=true&sslMode=DISABLED'
$env:FOUNDATION_DB_USER='your_test_user'
# FOUNDATION_DB_PASSWORD：通过本机环境或秘密管理工具设置
$env:TEST_SOURCE_DB_URL='jdbc:mysql://127.0.0.1:13307/port_source_test?allowPublicKeyRetrieval=true&sslMode=DISABLED'
$env:TEST_SOURCE_DB_USER='your_source_test_user'
# TEST_SOURCE_DB_PASSWORD：通过本机环境或秘密管理工具设置
mvn test
```

早期版本不设置 `RUN_MYSQL_TESTS=true` 时只执行 MVC 测试，MySQL 集成测试显示 skipped；此记录不代表当前完整测试步骤，当前复现方法见本文顶部。

## 尚未验证或尚未实现

- 当前未安装/未找到 Docker，因此没有执行 Compose 容器启动或拉取镜像验证。
- 上述早期记录对应 2026-09-22 第一小阶段；Source Management、连接测试、Metadata Scan、Resource Publish 已在后续阶段交付并另行测试。
- Provider、Demand、Kafka、CDC 和治理模块等待后续阶段，不计入 V0.1 测试范围。前端为独立演示界面。
