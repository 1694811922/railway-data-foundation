# 开发测试记录

## 数据源检索与操作可靠性（最新，2026-10-04）

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
