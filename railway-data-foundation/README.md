# 铁路统一数据底座虚拟接入与共享治理原型系统

当前范围：**V0.1后端闭环 + V0.2能力登记、Provider REST真实启停、Kafka生产与验证 + 汇报演示前端**。真实后端包含数据源、元数据扫描、资源发布、字段维护和接口能力登记；REST查询在能力启用后由独立Provider直接读取Source，Foundation只提供控制元数据与无业务行探测。Kafka启停、CDC和V0.3治理尚未实现。旧Java 21 / JPA / H2原型代码已清理；历史数据和配套凭据归档于工作区根目录`.legacy-backup/`，新版不会读取。

## 架构边界

开发需求与阶段验收见 [统一数据底座需求说明书（2026-10-02）](docs/统一数据底座需求说明书_20261002.docx)，包含角色、业务流程、功能需求、验收场景及后续任务安排；V0.3 仍保持暂停。

Foundation 只管理元数据、资源、能力、共享关系、策略、计量和审计。业务明细留在 Source；Source → Demand 的数据流不经过 Foundation。Kafka Topic 和 CDC Stream 属于 Capability。外部系统通过 Adapter 接入，业务含义和权限由人工确认。

Foundation 通过 Flyway 管理数据源、扫描结果、资源、字段业务属性及能力登记表；地址、端口、数据库和用户名通过配置表动态登记，密码在独立凭据表中加密保存，主密钥由环境变量提供，不保存明文密码或业务行。旧连接环境变量引用保留兼容。`port_source_db.container_event` 及 20 条明确标识的模拟数据通过独立 Source Flyway 迁移创建。Provider 通过只读账号直接从该 Source 表返回业务行或向 Kafka 发送指定事件；Foundation 只做无业务行的 REST 探测和 Topic 元数据验证。接口见 [数据源接口](docs/SOURCE_API.md)、[扫描与资源接口](docs/SCAN_RESOURCE_API.md)、[能力接口](docs/CAPABILITY_API.md)、[Provider REST](docs/PROVIDER_REST_API.md) 和 [Provider Kafka](docs/PROVIDER_KAFKA_API.md)。

## 动态接入外部 MySQL

进入前端“数据源管理 → 新增数据源”，填写名称、数据库地址、端口、数据库名称、只读用户名、密码及加密连接方式，然后“保存并测试连接 → 开始扫描 → 查看字段”。连接元信息保存到 `source_system`，密码通过统一 CredentialService 加密存入 `source_credential`；无需修改 Java、新增 DataSource Bean 或重新启动服务。编辑密码留空会保留原凭据；停用会阻止新的测试和扫描。

Foundation 自身仍使用固定 Spring DataSource，外部数据库由 SourceConnectionFactory 按需建立并关闭 JDBC 连接。扫描仅访问 `information_schema`。本机脚本首次自动生成 `FOUNDATION_CREDENTIAL_KEY` 并保存在被 Git 忽略的 `.env.local`，重启复用；备份数据库时必须同时备份主密钥。仅支持 MySQL，旧环境引用仍可运行，详见 [动态接入接口与凭据说明](docs/SOURCE_API.md)。

## 目录

```text
railway-data-foundation/
├── pom.xml                      Maven 聚合工程，Java 17
├── foundation-service/          Spring Boot 3、API、Flyway、测试
├── provider-service/            独立的 Source 直连 REST 服务
├── demand-service/              预留，尚未实现
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

Foundation Swagger：`http://127.0.0.1:18080/swagger-ui.html`。Provider健康接口：`http://127.0.0.1:18081/api/v1/provider/health`。业务查询需先登记、验证并启用REST能力，再携带`X-Capability-Id`请求头访问18081，完整示例见[Provider REST](docs/PROVIDER_REST_API.md)。

### 汇报前端

先按上文启动本地后端，再开一个终端：

```powershell
cd foundation-web
npm install
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。首次点击“进入平台”即可使用演示布局；此入口只负责前端导航，没有后端认证。`VITE_API_BASE_URL=/api/v1` 与 `VITE_USE_MOCK=false` 已写在 [前端环境配置](foundation-web/.env)。Vite 将 `/api` 代理到本地后端 18080。数据源、扫描任务、资源、字段和 Capability 登记均优先使用真实 API；后端不可达时演示页可使用 [集中维护的演示数据](foundation-web/src/mock/demoData.js)，页面会标注“演示数据”；真实数据源的写入、测试和扫描失败不会模拟成功。设置 `VITE_USE_MOCK=true` 可强制使用演示数据。模拟编辑仅保存在当前浏览器的 localStorage，不保存密码。前端新增数据源时向后端提交密码一次，后端加密保存且不在查询接口中回显。

本机用 Navicat 查看底座表时，连接 `127.0.0.1:13306`，数据库为 `foundation_db`，账号 `foundation_app`，密码读取被忽略的 `.env.local` 中 `FOUNDATION_DB_PASSWORD`。V6 已为底座自建表字段补齐中文“注释”；在 Navicat 的“设计表”中查看。`source_system.credential_ref` 是关联 `source_credential` 的凭据编号，不是密码。

推荐演示：首页 → 登记数据源 → 测试连接 → 扫描`container_event` → 创建资源并补全业务信息 → 发布 → 维护字段 → 登记REST/Kafka/CDC能力 → 验证REST GET能力 → 启用并查询 → 停用后查询被拒绝。当前Provider服务指定来源，数据源需与其主机、端口、数据库及表一致。配置Kafka后还可验证Topic并发布事件；Kafka启停和CDC尚未实现。业务行直接从Provider返回，控制状态查询只传元数据。预设能力样例可通过`VITE_USE_MOCK=true`展示并有演示标记。

接口能力登记表单已提供中文字段说明与“填入本机示例配置”。在现有REST查询能力的“查看 → 调用方式”中，可查看后端配置的提供服务地址、完整地址及必带请求头，并复制PowerShell命令到VS Code终端执行；说明加载失败会显示错误与重试，未启用/已停用时不能查询。详见[本轮交付记录](docs/CAPABILITY_GUIDE_CHANGELOG.md)。

资源登记和字段维护也已使用真实操作结果：来源表只读，详情可发布草稿，发布后才能登记能力；真实保存、发布与字段编辑失败保留输入并提示刷新，不模拟成功。指定资源字段和扫描结果读取失败显示错误，演示字段按资源隔离。详见[资源流程修复记录](docs/RESOURCE_FLOW_CHANGELOG.md)。

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
