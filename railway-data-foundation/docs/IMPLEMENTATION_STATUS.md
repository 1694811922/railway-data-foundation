# Implementation Status

## V0.1 第一小阶段
- [x] Foundation 初始化及 Java 17 编译、统一 API、Validation、SpringDoc
- [x] Flyway 与真实 MySQL 启动验证；MyBatis Plus 自动配置加载
- [x] Source container_event、20 条模拟数据与重复迁移验证
- [ ] Docker Compose 容器启动验证（当前机器未发现 Docker）

2026-09-22 实际执行 `mvn test`：11 项通过，0 失败，0 错误，0 跳过；Java 17.0.3 / MySQL 8.0.33。随后可执行 JAR 打包成功。Compose 配置已交付，但不能将本机 MySQL 验证视为 Docker 验证。详见 [TESTING.md](TESTING.md)。

## V0.1 后续
- [x] Source Management（新增、列表、详情、更新；动态连接配置与加密凭据，兼容旧环境变量引用）
- [x] MySQL Test API（真实 JDBC、产品/版本/耗时、连接失败脱敏）
- [x] Metadata Adapter（MySQL 表和字段、参数化查询、禁止跨库读取）
- [x] Metadata Scan（任务、候选表及字段持久化；失败状态安全记录）
- [x] Resource Registry / Publish（必须关联成功扫描的表，人工确认后发布）
- [x] Field Metadata（技术字段复制到资源，业务属性人工维护）
- [x] 资源前端操作可靠性（真实失败不模拟成功、来源只读、详情发布、区域加载错误、扫描响应隔离及演示字段隔离）

2026-10-03 资源流程完善：修复真实资源保存、发布和字段编辑失败时回退mock的缺陷；来源系统/数据库模式/物理表只读，详情直接发布草稿，发布后再登记能力。指定真实资源和扫描任务的读取失败显示错误，不使用演示字段替代；晚到的字段响应在任务切换后丢弃，演示字段按资源编号隔离。前端`npm test` **6项通过**、`npm run build`成功，`test:smoke`、`test:resource-live`和两库`test:source-live`全部通过。实际接口验证字段/资源修改、发布及版本变化；响应丢失不自动重发，刷新可确认已提交结果。两种专项测试临时元数据均已按清单清理。未修改Java业务代码、Provider、数据库结构或迁移，仍为V6；V0.3继续暂停。详见[资源流程修复记录](RESOURCE_FLOW_CHANGELOG.md)。

2026-09-22 第二阶段：完整 `mvn test` 19 项通过，0 失败、0 错误、0 跳过；在独立 MySQL 测试库完成 Source CRUD、真实连接、拒绝错误凭据、配置与分页校验、字段元数据读取及密码不出库验证。新增 V2 迁移创建 `source_system`。扫描适配器通过测试不代表扫描任务 API 已实现。

2026-09-30 第三阶段：新增 V3 Flyway 迁移及真实扫描、资源、字段接口。独立 MySQL 测试库 `scripts/test-local.ps1 -Offline`：**21 项通过、0 失败、0 错误、0 跳过**；覆盖 `container_event` 的 12 个字段、从扫描结果登记资源、字段业务属性编辑、发布及拒绝未扫描的表。后端 `mvn package` 成功，本机重启后 health、扫描任务列表和资源列表均返回成功；前端 `npm run build` 成功。开发库尚无扫描任务/资源记录，需在界面执行扫描并登记。

## 接入管理动态多数据源 MVP（2026-10-03）

- [x] V5 追加迁移扩展 source_system：地址、端口、数据库、用户名、凭据引用、加密连接方式及启停状态；独立 source_credential 保存 AES-256-GCM 密文。
- [x] 内部 CredentialService 统一保存、更新、读取和删除凭据；密码仅写入、不回显，编辑留空保留密文；主密钥由数据库外环境配置提供，本机脚本自动生成并持久保留。
- [x] SourceConnectionFactory.openConnection(sourceId) 查配置与凭据，选择 MysqlSourceConnectionProvider；外部连接按需建立并关闭，不新增 Source DataSource Bean。Foundation 自身固定 DataSource 保持不变。
- [x] Source 启停、真实连接测试与中文错误分类；停用后拒绝新测试和扫描。扫描仅访问 information_schema，失败保存安全原因；任务增加字段数量。
- [x] 中文分组表单、保存并测试、密码留空编辑、启用/停用、中文任务及字段列表；演示模式不保存密码，真实 Source 请求失败不变成 mock 成功。

完整 `scripts/test-local.ps1 -Offline`：Foundation **32 项**、Provider **3 项**，合计 **35 项通过，0 失败、0 错误、0 跳过**。独立 `port_source_test` 扫描 container_event（12 字段），`another_source_test` 扫描 train_dispatch（3 字段），第三次动态登记成功；扫描结果隔离，底座无对应业务明细表。覆盖加密随机 IV、密文防挪用、主密钥错误、空密码保留、密码更新、连接关闭、错误密码、失败任务、启停及旧记录转换。调整测试旧配置引用为独立测试属性后，DynamicSourceIntegrationTest 的 5 项再次通过。

前端最终 `npm run build`、`npm run test:smoke`、`npm run test:source-live` 均通过。真实服务浏览器测试验证界面登记、保存并测试、空密码编辑、启停、两库扫描、字段展示及请求超时不产生模拟成功。Foundation 本机已打包重启并迁移至 V5；本轮 Foundation 运行日志、专项测试和打包日志中未发现实验账号密码或主密钥。UI 测试临时元数据通过带编号、名称和引用校验的清理脚本移除，不删除原有资源或 Source 业务表。

本次仅调整接入管理；Resource、Capability 和 Provider 业务代码未修改。旧 Provider 仍绑定原来的指定来源，不自动为新增数据库提供业务访问能力。仍为本机实验原型，未提供服务端管理身份认证；远程部署需 HTTPS 和权限控制。主密钥轮换、异步大规模扫描、Oracle/PostgreSQL 尚未实现。V0.3 继续暂停。

2026-10-03 字段备注补充：V6 追加 Flyway 迁移仅补数据库字段注释；`source_system.credential_ref` 与 `source_credential.credential_ref` 的引用关系写入中文备注。测试库和本地开发库均升至 V6，Foundation 自建表 85 个字段全部有注释，原有开发库 2 条数据源记录保留；Source 示例表 `container_event` 原有 12 个字段也有注释。完整后端回归 35 项通过，Foundation 打包及健康检查通过；未改变接口或业务流程。

## V0.2
- [x] 接口能力登记与调用指引（中文字段解释、按类型筛选认证、技术字段校验、真实提供服务地址与可复制命令）
- [x] Capability Registry（四种类型的元数据登记、查询和草稿编辑；仅可关联已发布资源）
- [x] Provider REST（只读账号直连 Source，固定参数化事件查询；仅监听本机）
- [x] REST Verify（Foundation 对指定 GET 能力发送无业务行的 HEAD 探测；成功/失败更新状态）
- [x] Kafka（Provider 从 Source 读取指定事件并向固定 Topic 生产；嵌入式 Broker 集成测试通过）
- [x] Kafka Verify（Foundation 仅查询 Topic 元数据；嵌入式 Broker 集成测试通过）
- [x] REST能力运行时启停（验证后启用、停用及再启用；Provider逐次检查控制元数据，动态来源匹配）

2026-10-03 调用指引完善：新增只读`GET /capabilities/{id}/invocation`，复用REST验证使用的后端地址配置，返回完整调用地址、请求头和可用标志；不探测、不查询业务数据、不改变能力状态。前端登记表单补充中文说明和本机示例填入，按类型限制认证选项，CDC来源表随资源带出，只读模板拒绝SQL。真实说明加载失败显示错误并可重试，不模拟成功。完整后端回归Foundation **38项**、Provider **7项**，共 **45项通过，0失败、0错误、0跳过**；前端`npm test` **3项通过**，`npm run build`、`test:smoke`和`test:capability-live`通过。已更新并重启Foundation，Provider保持运行；临时UI_REST_元数据已清理。未修改数据库迁移，仍为V6；V0.3继续暂停。详情见[能力登记与调用指引交付记录](CAPABILITY_GUIDE_CHANGELOG.md)。

2026-10-03 REST启停阶段：支持现有无认证的`GET /containers/events`能力从VERIFIED启用、从ENABLED停用及DISABLED重新验证/启用。启用前重新HEAD探测，核对运行时版本与来源标识；版本条件更新避免并发覆盖，重复启停同状态不重复增加版本。Provider每次GET检查`X-Capability-Id`对应的运行状态，再直连Source；未启用、来源停用或来源不匹配返回403，控制状态不可用返回503且不查询业务行。Foundation只传控制元数据，未实现V0.3身份与共享授权。

完整后端测试Foundation **34项**、Provider **7项**，合计**41项通过，0失败、0错误、0跳过**。后端打包和本机两服务重启成功；前端build、test:smoke及真实test:capability-live通过。真实浏览器验证动态Source→扫描→发布资源→登记/验证/启用REST→Provider 200→停用403→再启用200，以及Source停用拒绝和操作超时不模拟成功。临时UI_REST_元数据已按编号、凭据引用和名称校验清理；原有数据保留。未新增数据库表或迁移，仍为V6。详见[REST启停交付记录](REST_RUNTIME_CHANGELOG.md)。Kafka启停、CDC、只读模板和V0.3仍未实现。

2026-10-01 第一小阶段：新增 V4 Flyway 迁移和真实 Capability API。独立 MySQL 测试库 `scripts/test-local.ps1 -Offline`：**22 项通过、0 失败、0 错误、0 跳过**；验证四种能力登记、未发布资源拒绝、重复名称和非法 REST 地址拒绝，验证/启用不会在缺少适配器时虚报成功。后端打包和本地开发库 V4 迁移成功，Capability 列表 API 返回 `code=0`；前端 `npm run build` 与 `npm run test:smoke` 成功。此项只代表登记能力，不代表 Provider、Kafka 或真实健康验证已完成。

2026-10-01 第二小阶段：新增独立 `provider-service`，通过本地生成的只读账号直接查询 Source 的 `container_event`，提供固定 REST 查询和不返回业务行的 HEAD 探测。Foundation 对登记的 `/containers/events`、无认证 REST GET 能力进行真实 HTTP 探测：成功为 `VERIFIED/UP`，失败为 `DRAFT/DOWN`。独立 MySQL 测试库：Foundation **22 项**、Provider **2 项**通过，合计 **24 项，0 失败、0 错误、0 跳过**；验证 Provider 直连 Source、只读权限、查询过滤、参数拒绝及 Foundation 的探测状态变化。本地两服务实际启动、Provider HEAD 204 和 GET 20 条模拟事件、前端 `npm run build` 与 `npm run test:smoke` 均通过。`ENABLED` 状态仍未开放；没有 Provider 运行时开关联动或 V0.3 权限控制。

2026-10-01 第三小阶段：Provider 增加 Source 事件按 ID 发送到固定 Kafka Topic 的接口；Foundation 为 `KAFKA_TOPIC` 增加只读元数据验证，仍不消费或保存事件。独立 MySQL 测试库配合嵌入式 Kafka：Foundation **22 项**、Provider **3 项**，合计 **25 项，0 失败、0 错误、0 跳过**；测试实际消费一条 Provider 发出的 JSON 事件并验证 Topic 的 `VERIFIED/UP`。前端 Capability 页开放 Kafka 验证操作，`npm run build` 与 `npm run test:smoke` 通过。后端 JDK 17 离线打包成功。常驻本机 Kafka Broker 未由启动脚本安装，独立运行需配置 Broker 与 Topic，详见 [Provider Kafka](PROVIDER_KAFKA_API.md)。`ENABLED`、CDC 和 V0.3 仍未开发。

## V0.3
- [ ] Subject
- [ ] Policy
- [ ] Relationship
- [ ] Access Check

## V0.4
- [ ] Audit
- [ ] Trace
- [ ] Metric

## V0.5
- [ ] Demand REST
- [ ] Kafka Consumer
- [ ] CDC

## V1.0
- [ ] Version Change
- [ ] Dashboard
- [ ] Full Demo

旧原型功能未计入新版实现状态。后续领域表随阶段追加 Flyway 迁移，不能向 foundation_db 写入业务明细。

## 汇报演示前端（独立于后端版本）

2026-10-04 数据源检索与操作可靠性：分页读取现有接口、关键词/类型/状态组合检索、10/20/50项显示分页，读取失败清空旧数据并阻止管理操作；保存锁定数据模式、防止重复提交、失败保留输入、密码不持久化；连接失败清除旧成功信息，离开页面后忽略旧结果。28项前端测试、build、数据源目录专项、smoke及两库真实接入回归通过，临时元数据清理完成。后端及V6结构未修改，V0.3继续暂停。见[数据源管理交付记录](SOURCE_CATALOG_CHANGELOG.md)。

2026-10-04 元数据扫描工作台：有界分页读取任务与数据源，任务组合筛选/10/20/50项分页、表与字段检索；刷新所选任务状态和表结构、指定任务详情/同页面路由更新、区域错误/重试、旧表和字段请求隔离、扫描重复提交保护。默认真实模式读取失败不转演示。24项前端测试、构建、扫描专项、演示流程、真实资源流程与两库扫描回归均通过，临时元数据已清理。未修改后端或数据库，V0.3继续暂停。见[扫描工作台交付记录](SCAN_WORKBENCH_CHANGELOG.md)。

2026-10-03 接口能力检索与操作可靠性：分页读取能力及资源选项，提供关键词/关联资源/类型/状态/健康组合筛选和10/20/50条显示分页；仅关联已发布资源。能力与资源失败分别显示错误，不自动混入演示；提交期间阻止重复保存，失败保留输入并提示检查真实结果；旧调用说明不能覆盖新请求。20项前端测试、build、能力目录专项、smoke及真实REST启停流程均通过，临时测试元数据已清理。仅修改前端和文档，数据库仍为V6，V0.3继续暂停。见[能力管理交付记录](CAPABILITY_CATALOG_CHANGELOG.md)。

2026-10-03 资源目录检索与分页：通过现有分页接口读取资源，提供关键词/来源/状态/安全等级组合检索、10/20/50条显示分页、网址保存条件、详情返回恢复。最多读取5,000项，范围不完整有提示；中途读取失败或真实目录刷新失败不切为演示。16项前端测试、build、catalog-live、smoke及resource-live通过，真实测试临时元数据已清理。未修改Java、数据库或迁移，V0.3继续暂停。见[目录交付记录](RESOURCE_CATALOG_CHANGELOG.md)。

2026-10-03 首页统计修复：通过已有列表接口分页统计，不再把首批50/200条当成总数；每类最多读取5,000条，超过上限或分页发现重复时标注“≥”和原因。今日扫描按北京时间统计；可用能力同时要求资源已发布、来源启用。失败模块显示“—”并清除旧统计，真实首页不混入演示数据；显式mock模式仍可展示。最近资源按创建时间排序，中文类型分布，统计卡与流程支持导航。12项前端测试、build、smoke及dashboard-live通过；专项核对真实API并用HTTP夹具验证跨页、失败、恢复和上限，无数据库写入。未修改后端或V6迁移，V0.3继续暂停。见[首页交付记录](DASHBOARD_CHANGELOG.md)。

- [x] Vue 3 + Element Plus + Vue Router + Axios 基础工程、中文布局和演示入口
- [x] Dashboard、数据源管理、扫描、资源目录、Resource 详情与字段元数据、Capability 管理、系统说明
- [x] 数据源列表、登记、更新及连接测试优先使用真实 `/api/v1/sources` 接口
- [x] 扫描、资源、字段和能力登记优先使用真实API；演示数据独立封装并明确标注。真实写入及指定详情读取失败不模拟成功；首页和能力管理默认真实读取失败不自动转演示，资源目录初次回退条件见专项记录
- [x] `npm run build` 通过
- [x] Edge 浏览器页面流程测试通过（登录、首页、Source 弹窗、扫描字段、Resource 详情、Capability 弹窗）

前端能力登记可真实落库，指定REST GET能力可验证、启用和停用；详情显示运行状态与调用请求头。Provider的GET需提供已启用的能力编号才能返回Source模拟数据，前端管理页不展示业务明细。真实能力操作失败不模拟成功。演示入口没有服务端身份认证，V0.3未开发；Kafka仅登记、生产与元数据验证。
