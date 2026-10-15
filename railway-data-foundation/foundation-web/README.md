# 统一数据底座汇报演示前端

2026-10-10 最新：Kafka订阅详情新增共用“消费观察”面板，真实累计条次/载荷字节/报告及10/20/50项分页，失败清空和重试、旧响应隔离；演示模式不请求真实摘要。一次性领取显示Kafka密码及独立上报令牌，关闭后清空且不持久化。npm test 129项、npm run build及真实消费/三角色/演示浏览器通过；后端按模块162项通过，需要新版V15 Foundation。见 [交付](../docs/KAFKA_CONSUMPTION_CHANGELOG.md)。以下为历史。

2026-10-10 账号管理补充：管理员账号真实检索/角色状态筛选/分页，普通账号主体候选可检索并加载更多；新建、启停、重置及失败/并发保护完善。127项前端、build、真实三角色浏览器及显式演示通过；演示禁止账号请求。后端156项通过、V14不变。见 [账号交付](../docs/ACCOUNT_MANAGEMENT_CHANGELOG.md)。

2026-10-10 补充：三角色共用顶部“修改密码”，真实验证当前密码并成功后重新登录；关闭/失败/身份变化清空密码。显式演示禁止改真实密码。前端124项及构建、真实浏览器和演示回归通过，见 [交付](../docs/SELF_PASSWORD_CHANGE_CHANGELOG.md)。

2026-10-10 最新：本工程共用布局和公共组件支持管理员、数据提供方、数据需求方；真实登录调用Foundation会话认证，角色/单位来自服务端，没有用户角色切换。默认VITE_USE_MOCK=false，身份/CSRF仅在内存，401/退出卸载敏感页面；显式演示不请求真实系统。首次管理员安全初始化及账号绑定方法见 [三角色指南](../docs/ROLE_WORKSPACE_GUIDE.md)，接口见 [认证](../docs/WORKSPACE_AUTH_API.md)。npm test122项、npm run build及真实三角色浏览器通过。旧可选UI脚本的假登录须迁移真实会话，当前验收脚本为scripts/role-workspaces-live.mjs，由后端独立角色集成测试自动准备和清理账号。下文日期记录为历史。

2026-10-09 新增“共享治理 → Kafka 订阅授权”：真实整主题申请、确认、退回、编辑、撤回及审批历史，中文服务端分页。当前仅审批，Broker权限始终未配置，不签发凭据；显式演示模式禁用此流程且不请求真实接口。117项测试、构建及真实审批浏览器通过，后端需V12。见[交付](../docs/KAFKA_SUBSCRIPTION_CHANGELOG.md)。

2026-10-09新增“资源详情 → 版本历史”：每页20项，查看资源/字段快照和相邻版本业务属性/状态变化，字段检索及10/20/50分页。首次基线与未留存旧版明确区分；失败重试、旧响应和真实/演示隔离。107项前端测试、build、真实历史及原结构比较/资源/共享访问/演示回归通过。需同步更新Foundation到V11；仅查看，没有恢复或结构应用。接口与文件见[交付](../docs/RESOURCE_HISTORY_CHANGELOG.md)。

2026-10-07新增资源详情“结构变化”：首次打开加载每页20项后续成功扫描，人工点击“开始比较”；中文增删/修改统计、关键词/类型筛选、10/20/50项分页及属性展开。表缺失或范围不明数量未知；失败清空旧结果并重试，切换任务/资源/页签隔离晚到响应。真实读取不回退演示样例，独立样例在`src/mock/schemaComparisonData.js`，没有明确登记扫描的旧演示资源不可比较。101项前端测试、build及真实专项/资源/访问/演示回归通过。使用同步更新后的Foundation，接口见[结构比较](../docs/SCHEMA_COMPARISON_API.md)，不会更新资源结构或版本。

2026-10-06资源维护补充：资源和字段编辑提交读取版本，服务端冲突时保留输入并禁止重复保存；点击“取消并刷新目录/详情”读取最新内容后再人工编辑。须使用同步更新的Foundation服务，旧编辑请求需补`version`或`resource_version`。93项前端测试、build、真实双页面资源闭环、共享授权/自动采集和演示回归通过，见[并发编辑交付记录](../docs/RESOURCE_EDIT_CONCURRENCY_CHANGELOG.md)。

2026-10-05界面精简：删除各页重复副标题、检索说明、字段解释、开发说明横幅和能力教程卡片；首页保留统计、接入流程及待处理事项。演示数据改为简短标签，保留错误、范围不完整、操作限制和令牌仅显示一次等必要提示。80项前端测试、生产构建、演示流程、能力操作和数据源概览专项通过；11个真实接口页面及新增数据源弹窗完成只读截图核对，无页面运行错误。详见[测试记录](../docs/TESTING.md)。

2026-10-04新增“共享治理 → 调用凭据”（`/credentials`）和“共享调用验证”（`/access-check`）。先确认启用关系，向其需求方签发令牌，再模拟需求方直接调用Provider；支持核对允许字段、时间窗口及越权、暂停/撤回、凭据撤销后的拒绝。令牌和业务结果不持久化，显式mock模式不模拟授权。前端80项测试、构建、真实授权及原关系/REST/演示回归通过，见[访问接口](../docs/ACCESS_API.md)与[交付记录](../docs/ACCESS_CHANGELOG.md)。

Vue 3、Element Plus、Axios、Vue Router。项目从外层 `railway-data-foundation` 启动后端，然后在本目录执行：

```powershell
npm install
npm run dev
```

访问 `http://127.0.0.1:5173`，使用真实账号登录。首次管理员通过 `../scripts/initialize-admin.ps1 -Username foundation_admin` 安全初始化，再由管理员创建关联共享主体的普通账号，见 [三角色指南](../docs/ROLE_WORKSPACE_GUIDE.md)。无后端纯演示请在本目录 `.env.local` 设置 `VITE_USE_MOCK=true` 并重启开发服务器；演示模式与真实认证及请求隔离，不执行真实授权或消费摘要操作，真实接口故障不会自动切换为演示成功。

2026-10-06资源创建请求新增明确`scan_task_id`，由扫描工作台入口自动带出。即使期间又完成扫描，保存仍使用已核对的原任务；资源详情基本信息的“扫描来源”可点击查看，编辑不能更换绑定。需使用同步更新的Foundation服务，旧创建客户端须补充该参数，普通编辑可省略而保持原关联。91项前端测试、build、登记专项、真实资源与共享访问回归通过，详见[交付记录](../docs/RESOURCE_SCAN_SNAPSHOT_CHANGELOG.md)。

2026-10-06新增“运行监管 → 调用审计 / 调用追踪 / 共享计量”，全部接真实API，不使用运行记录mock。先在“共享调用验证”执行真实查询，再刷新审计查看完成结果、返回数量、字节及追踪编号；由记录进入追踪详情。计量默认最近15个已结束分钟，本分钟操作需等待下一分钟或选择包含本分钟末端的范围。按对象筛选无事件时显示“—”，确认零值与部分上报分开显示。Provider异步采集可能有短暂延迟，详见[交付记录](../docs/RUNTIME_TELEMETRY_CHANGELOG.md)。

`npm test`90项与build通过。`npm run test:telemetry-live`使用5192临时端口，先核对真实自动采集，再验证只读HTTP故障/分页夹具，结束自动关闭；需运行Foundation/Provider且已有真实执行摘要，可先加载后端环境后执行`npm run test:access-live`。测试夹具不进入页面生产代码，历史摘要不因临时治理登记清理而删除。

## 页面与数据边界

首页 Dashboard、数据源管理、元数据扫描、资源目录、Resource 详情（基本信息、字段元数据、关联能力）、Capability 管理、系统说明。

V0.3已恢复，新增“共享治理 → 共享主体管理”（`/subjects`）。提供方/需求方登记、编辑、启停、中文检索、角色/状态筛选、10/20/50项分页；业务编码和角色不变，更新携带版本。真实读写失败不自动切为演示，主体启用不授予访问权限。见[主体API](../docs/SUBJECT_API.md)与[V0.3计划](../docs/V03_DEVELOPMENT_PLAN.md)。

共享治理包含“共享关系管理”（`/relationships`）与“访问策略”（`/policies`）。关系绑定已启用双方主体、已发布资源和所属能力，登记用途、授权有效期、允许字段、数据时间范围；支持草稿编辑、提交、确认/退回、启用登记、暂停、撤回与完整历史。策略通过关系草稿调整，确认后冻结。两页仅使用真实API，显式mock模式展示说明并停止真实请求；不生成模拟授权。REST访问还需有效调用令牌，由底座与Provider执行策略。详见[关系与策略API](../docs/SHARING_API.md)。

`VITE_PROVIDER_BASE_URL=/provider`，Vite将其代理至18081，`/api`仍至18080。共享调用不会把业务行经底座转发。`npm run test:access-live`使用5190临时端口，需先加载项目本机环境并启动新版服务；结束使用共享清单清理脚本删除测试元数据。调用令牌不要放入环境文件或Git；服务间凭据也不能配置为VITE变量。

`npm run test:sharing-live`使用5189临时端口，需加载本机环境且启动新版Foundation/Provider。通过真实API准备独立随机测试来源、扫描、资源、能力和主体，再由浏览器验证关系与策略；HTTP夹具只测试故障、版本冲突、锁定和旧响应，不写数据库。测试结束执行`powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-sharing-ui-test.ps1`清理清单记录，不删除源库业务数据。

仅显式`VITE_USE_MOCK=true`时使用`src/mock/subjectData.js`两条浏览器示例，不写入真实数据库。`npm run test:subject-live`需本机新版Foundation，使用5188端口并自动关闭，真实创建/编辑/停用临时主体后用HTTP夹具验证跨页、错误、上限和旧响应。结束执行`powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-subject-ui-test.ps1`按清单清理测试登记。当前62项前端测试、构建、主体专项和演示回归通过。

首页分页统计现有元数据接口，每类最多5,000条；超过上限显示数量下限“≥”，读取失败显示“—”并可刷新。今日扫描使用北京时间，可用能力按启用/最近验证/资源发布/来源启用状态计算，并非实时健康探测。最近资源按创建时间排序，统计卡与接入流程可点击导航。见[首页交付记录](../docs/DASHBOARD_CHANGELOG.md)。

首页“待处理事项”汇总草稿资源、草稿能力和失败扫描，支持关键词与10/20/50项分页。资源进入详情人工确认发布；能力进入所属资源的能力列表（不会自动打开或验证某项能力）；失败任务进入指定扫描结果。提示遵循当前适配器范围，不自动发布、验证或重试。相关接口失败显示“—”，读取受限显示“≥”及范围说明。`npm run test:dashboard-work-live`需Foundation，使用5186临时端口，完成自动关闭；仅发送GET，不写数据库。

真实请求集中在`src/api/`，响应处理位于`src/utils/request.js`。数据源、扫描、资源、字段和能力登记优先调用已有后端。演示数据集中在`src/mock/demoData.js`并标记来源，模拟编辑只影响当前浏览器。真实数据源、资源、字段和接口能力的写入、发布、验证、启停失败不会模拟成功。指定真实资源详情与扫描任务读取失败显示错误，不使用演示结果替代。现有REST查询能力可真实验证、启用和停用，详情显示运行状态与调用请求头；Kafka可验证Topic元数据，CDC及Kafka运行时启停未实现。Provider业务查询和Kafka发布独立于管理页。

`VITE_API_BASE_URL=/api/v1`、`VITE_USE_MOCK=false` 是默认配置。Vite 将 `/api` 代理到 `http://127.0.0.1:18080`。如需强制纯前端演示，把 `VITE_USE_MOCK=true` 写入本目录 `.env.local`（被 Git 忽略）并重启开发服务器。按浏览器存储清理可重置演示编辑。

真实 Source API 接受名称、类型、地址、端口、数据库名称、用户名、密码、加密连接方式与备注。新增弹窗分为“基本信息”和“连接信息”，提供“保存”和“保存并测试连接”；编辑密码留空保留原凭据。密码仅作为写入参数发给后端加密保存，不回显、不保存到 localStorage（包括演示模式）。表格支持测试、开始扫描、编辑、启用和停用；停用后禁止新的测试和扫描。旧环境引用记录仍可运行，编辑保存时提示重新输入密码并转换为动态配置。

数据源管理已增加关键词/类型/状态组合筛选、10/20/50项分页及刷新。每次最多读取5,000项，范围不完整有提示；默认真实模式读取失败不切演示。保存期间防重复提交并锁定表单，失败保留输入、提示确认实际结果；取消、成功保存和页面退出清空密码。连接失败不保留旧成功信息，刷新清除本次测试结果。浏览器专项 `npm run test:source-catalog-live` 需启动Foundation，临时端口5182并自动关闭，使用HTTP夹具且不写数据库。见[数据源管理交付记录](../docs/SOURCE_CATALOG_CHANGELOG.md)。

数据源行的“查看”打开概览：显示连接基本信息、最近5项扫描及最近5项登记资源；各区域独立刷新/重试，可进入指定任务、资源详情或按来源筛选的资源目录。关联记录由已有全局列表分页读取后按来源编号筛选，每类最多读取5,000条，范围受限会明确标注；不查询业务行、不自动测试连接或扫描，也不显示密码和凭据引用。`npm run test:source-overview-live`需Foundation，临时端口5187并自动关闭，真实读取及HTTP夹具验证均只发送GET。见[数据源概览补充记录](../docs/SOURCE_CATALOG_CHANGELOG.md#数据源概览补充2026-10-04)。

```powershell
npm run build
npm test
npm run test:smoke
```

接入管理专项真实浏览器测试为 `npm run test:source-live`。先启动更新后的后端，并设置 TEST_SOURCE_DB_URL、TEST_SECOND_SOURCE_DB_URL、TEST_PROVIDER_SOURCE_DB_USER 和 TEST_PROVIDER_SOURCE_DB_PASSWORD，使用专用 port_source_test 与 another_source_test（后端测试脚本会引导创建）。测试在本机 Foundation 开发库登记带 UI_DYNAMIC_ 前缀的临时 Source/扫描元数据，不创建 Resource；清理运行 `../scripts/cleanup-source-ui-test.ps1`，仅删除清单中经校验的测试元数据。不得指向生产环境。输出 `.runtime/source-dialog.png`、source-list.png、source-fields.png 和 source-scans.png，测试服务器为 5175，结束自动关闭。覆盖保存并测试、空密码编辑、启停、真实两库扫描、无密码存储和网络失败不模拟成功。

浏览器测试默认强制使用独立演示数据，保证页面流程不依赖开发库已有记录；真实 API 由后端 MySQL 集成测试验证。测试使用本机 Edge 无界面模式；在其他机器上可设置 `FOUNDATION_BROWSER` 指向 Chrome/Edge 可执行文件。测试覆盖登录、首页、数据源弹窗、扫描字段、资源详情及能力弹窗，并输出 `.runtime/dashboard.png`。测试服务器使用 5174 端口，关闭后自动退出。

REST启停专项真实浏览器测试（本机开发库与port_source_db模拟库）如下；先启动已更新的Foundation和Provider。测试用UI_REST_前缀创建临时Source、资源、能力，清理脚本会校验编号、凭据引用和名称，保留业务表与原有元数据。

```powershell
. ../scripts/load-env.ps1 -Path ../.env.local
npm run test:capability-live
powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-rest-ui-test.ps1
```

覆盖动态登记→扫描→发布资源→页面注册/验证/启用REST→Provider返回200→停用403→重新启用200；Source停用也被拒绝，网络失败不模拟成功。测试服务器5176结束关闭，截图为`.runtime/rest-capability-enabled.png`、`rest-capability-disabled.png`与`rest-capability-detail.png`。Provider查询需携带详情中的X-Capability-Id请求头；V0.3身份及共享授权仍未实现。

汇报建议按：首页→数据源→连接测试→扫描与字段→资源目录→详情→登记能力→验证REST→启用查询→停用后拒绝。有Kafka时再验证Topic。预设样例仅在演示模式展示；真实模式需从已发布资源登记。业务行直接从18081的Provider返回；Foundation只传能力控制元数据。CDC与Kafka启停仍待后续。

## 能力登记与调用指引

能力管理增加中文草稿/验证/启用/停用说明，禁用按钮可悬停查看原因。保存锁定输入，编辑检查当前记录与不可变归属；切换资源入口或离开页面时旧操作不刷新新页面。列表刷新会同步已打开详情的状态并重新读取调用说明，错配说明不展示命令。系统说明以中文流程和能力支持表解释当前真实功能。`npm run test:capability-workflow-live`需Foundation，临时端口5184，HTTP夹具写入拦截且结束自动关闭。见[能力流程交付记录](../docs/CAPABILITY_WORKFLOW_CHANGELOG.md)。

能力列表支持关键词、关联资源、类型、状态、健康状态组合筛选，以及10/20/50项分页。能力和资源选项通过现有接口分页读取，每类最多5,000项，范围不完整有提示。资源选项只允许已发布资源；资源读取失败会阻止注册和编辑，能力读取失败清空旧列表。默认真实模式不自动混入演示；显式mock配置或演示资源入口会统一使用演示数据。保存失败保留输入，网络故障提示刷新确认，不自动重发；保存期间防重复提交；旧调用说明不会覆盖新结果。

专项验证命令为 `npm run test:capability-catalog-live`：需启动Foundation，临时端口5180，结束自动关闭。核对真实接口后用HTTP夹具验证跨页及故障，不写数据库。详见[能力管理交付记录](../docs/CAPABILITY_CATALOG_CHANGELOG.md)。

登记弹窗对资源、路径、主题、认证和查询模板提供中文说明；“填入本机示例配置”填入常用技术字段，并保留已填名称。认证选项按类型过滤，CDC来源表由资源自动带出且不可手工修改；模板填写编号而非SQL。字段校验和命令生成位于`src/utils/capabilityForm.js`，`npm test`验证路径边界、各类型字段与命令转义。

现有REST查询能力详情读取真实`GET /capabilities/{id}/invocation`，显示后端配置的提供服务地址、完整调用地址和必带请求头。点击“复制调用命令”，到VS Code的PowerShell终端粘贴执行；浏览器禁止剪贴板时可选中命令手工复制。刷新调用信息读取当前控制状态，不查询业务数据。未启用、已停用或来源停用时不能实际查询。

真实调用说明加载失败只提示错误与重试；演示说明集中在`src/mock/demoData.js`且有演示提示，不能用于真实调用。`test:capability-live`额外检查实际地址、复制内容及加载失败/重试，不在管理页展示业务明细。

## 元数据扫描工作台

表与字段现支持独立10/20/50项分页及已读取/匹配数量，搜索和切换任务会重置页码；字段顺序保留原始元数据顺序。错配任务/来源/表、重复名称、异常结构或混入演示标记的响应显示错误并禁止从异常结果登记资源。扫描请求返回前切换任务、改变任务入口或离开页面后，旧响应不会刷新当前结果或弹出旧通知；可手动刷新确认真实扫描结果。仍调用已有同步接口，不提供任务取消或后台排队。

任务支持关键词/来源/状态组合检索、10/20/50项分页；表名、数据库模式、表说明和字段名称/类型/注释可搜索。任务与来源通过已有接口分页读取，每类最多5,000项，范围不完整有提示。刷新重新读取所选任务及表结构，指定任务入口和同页面路由更新受支持；表/字段失败可单独重试，旧请求不能覆盖新结果。

默认真实任务读取失败不自动转演示；显式mock配置或已有演示任务入口统一使用演示数据。来源读取失败或已停用时禁止扫描，扫描网络故障提示刷新确认、不自动重发。专项命令 `npm run test:scan-catalog-live` 需启动Foundation，临时端口5181并自动关闭，不写数据库。详见[扫描工作台交付记录](../docs/SCAN_WORKBENCH_CHANGELOG.md)。

## 资源登记与字段维护

从扫描结果创建资源时先核对指定成功任务与来源表，来源名称取任务响应，错误/缺失入口或读取失败禁止保存且可重试。已读取目录内重复表提供查看已有资源入口；取消后可“继续登记所选表”。保存与发布互斥、表单输入锁定、编辑检查当前版本及不可变来源、旧响应不导航或刷新新入口。保存后端仍采用最新成功扫描，未新增快照接口。`npm run test:resource-registration-live`需Foundation，临时端口5185，夹具写入拦截且结束自动关闭。见[资源登记交付记录](../docs/RESOURCE_REGISTRATION_CHANGELOG.md)。

详情页支持字段关键词/主键或时间标记/敏感等级组合筛选，以及关联能力关键词/类型/状态/健康筛选，10/20/50项分页。字段和能力独立加载、可分别重试；保存时锁定输入、防重复提交，切换资源后旧读写响应不刷新新页面。管理能力入口保留当前资源关联条件；字段和能力读取的完整列表在前端筛选，不查询业务明细。专项 `npm run test:resource-detail-live` 使用5183临时端口，需启动Foundation，夹具写入拦截且结束自动关闭。见[资源详情交付记录](../docs/RESOURCE_DETAIL_CHANGELOG.md)。

资源目录支持关键词、来源、状态、安全等级组合筛选，10/20/50条显示分页；刷新网址和从详情返回时保留条件。调用原有分页资源接口，每次最多读取5,000条，明确展示已读取/匹配数量和不完整范围；真实读取失败不保留旧表格或切为mock。检索只涉及元数据，不查询业务行。目录只读专项为`npm run test:resource-catalog-live`，先启动Foundation，测试使用5179并自动关闭，详见[目录交付记录](../docs/RESOURCE_CATALOG_CHANGELOG.md)。

资源目录的创建入口进入元数据扫描页。来源系统、数据库模式和物理表由扫描结果带出且只读；编辑资源只维护业务信息。详情页支持直接发布草稿，发布后才可登记能力；字段编辑后重新读取字段和资源版本，业务描述也在表中显示。

保存、发布或字段编辑失败时保留输入并提示刷新检查真实状态，不自动重发。即使后端已提交而响应丢失，页面也不会伪造成功或写入演示存储。真实字段或关联能力加载失败按区域显示错误，支持刷新重试；演示字段按资源编号隔离，不再修改全局扫描字段。

`npm test`覆盖资源校验、演示字段隔离、演示编号归属及已有能力表单校验。专项真实浏览器验收：

```powershell
. ../scripts/load-env.ps1 -Path ../.env.local
npm run test:resource-live
powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-rest-ui-test.ps1 -ManifestName resource-live-fixtures.json
```

先启动本机后端；只使用`port_source_db`模拟来源和带UI_REST_前缀的临时元数据，不应指向生产库。测试端口5177结束自动关闭。覆盖来源只读、晚到字段响应被忽略、真实保存/字段/发布失败无mock、响应丢失不自动重发、持久化与版本变化、发布后登记能力。清理脚本只接受两种固定清单名并校验编号、名称和凭据引用，保留原记录及业务表。截图在`.runtime/resource-live-detail.png`与`resource-live-fields.png`。

## Kafka实际两服务联调（2026-10-09）

项目目录执行 `scripts/test-kafka-foundation-live.ps1 -Offline`。脚本准备独立服务与临时SCRAM Broker，并运行真实浏览器“验证/启用/发送/停用/调用审计”流程；前端依赖须已安装。它使用独立底座测试库，不拦截成功，不修改原Source业务行；结束清理临时登记与进程。独立脚本 `scripts/kafka-chain-live.mjs` 由该夹具提供上下文，不能脱离测试环境直接运行。见[完整链路交付](../docs/KAFKA_COMPLETE_CHAIN_CHANGELOG.md)。
