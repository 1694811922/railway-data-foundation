# 接入管理动态多数据源改造交付记录

日期：2026-10-03。范围仅为接入管理、凭据、动态连接及中文 Source/Metadata 页面；V0.3 保持暂停。接口与操作细节见 [SOURCE_API.md](SOURCE_API.md)，验证记录见 [TESTING.md](TESTING.md)。

## 原实现与本次结果

原代码没有把每个 Source JDBC 地址写死，也没有每库一个 DataSource Bean，但 source_system 只保存环境配置前缀，新增 Source 需要服务端配置环境变量。现在默认前端登记地址、端口、数据库、用户名和密码：非秘密连接信息落入 source_system，密码经内部 CredentialService 加密，SourceConnectionFactory 按 source_id 动态连接。Foundation 固定 DataSource 保持不变。

连接流程为：查询 SourceMapper → 校验状态与连接元信息 → CredentialService 获取凭据 → 根据类型选择 SourceConnectionProvider → MysqlSourceConnectionProvider 建立短期只读 JDBC 连接 → MetadataAdapter 获取系统结构 → 自动关闭资源。创建登记本身不访问源库，不查询业务明细。

## 新建后端文件

- `foundation-service/src/main/resources/db/migration/V5__dynamic_source_connections.sql`
- `foundation-service/src/main/java/cn/railway/foundation/credential/CredentialService.java`
- `foundation-service/src/main/java/cn/railway/foundation/credential/EncryptedCredentialService.java`
- `foundation-service/src/main/java/cn/railway/foundation/credential/SourceCredential.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/SourceConnectionFactory.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/SourceConnectionProvider.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/MysqlSourceConnectionProvider.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/SourceConnectionFailure.java`

## 修改后端文件

- `foundation-service/src/main/java/cn/railway/foundation/source/SourceEntity.java`
- `foundation-service/src/main/java/cn/railway/foundation/source/SourceRequest.java`
- `foundation-service/src/main/java/cn/railway/foundation/source/SourceView.java`
- `foundation-service/src/main/java/cn/railway/foundation/source/SourceService.java`
- `foundation-service/src/main/java/cn/railway/foundation/source/SourceController.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/MetadataAdapterFactory.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/MysqlMetadataAdapter.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/ConnectionTest.java`
- `foundation-service/src/main/java/cn/railway/foundation/metadata/ScanService.java`

## 前端文件

- 修改 `foundation-web/src/views/source/SourceView.vue`：中文两组表单、密码写入、保存并测试、连接结果、启停、名称列固定。
- 修改 `foundation-web/src/views/metadata/MetadataView.vue`：任务字段数量、安全失败原因、任务隔离、中文字段说明/顺序及宽抽屉。
- 修改 `foundation-web/src/api/source.js`：新增状态接口。
- 修改 `foundation-web/src/utils/dataHub.js`：Source 操作失败不静默模拟成功，演示数据过滤密码。
- 修改 `foundation-web/src/mock/demoData.js`：统一动态 Source 字段和任务统计，旧演示结构兼容。
- 修改 `foundation-web/src/components/StatusTag.vue`：中文启停/扫描状态。
- 新建 `foundation-web/src/utils/time.js`：UTC 时间按浏览器本地格式展示。
- 修改 `foundation-web/scripts/smoke.mjs`：中文表单、演示创建、密码不落浏览器存储、启停和字段测试。
- 新建 `foundation-web/scripts/source-live.mjs`，修改 `foundation-web/package.json`：真实 Source 浏览器专项测试。

## 测试、脚本与文档

- 新建后端测试 `DynamicSourceIntegrationTest.java`、`MetadataConnectionLifecycleTest.java`、`credential/CredentialEncryptionTest.java`。
- 修改 `MySqlBootstrapTest.java`：校验 V5 与独立凭据表。
- 修改 `scripts/local-common.ps1`、`scripts/start-local.ps1`、`scripts/test-local.ps1`：主密钥持久保存、第二测试库及只读权限。
- 新建 `scripts/cleanup-source-ui-test.ps1`：校验来源编号/凭据引用/测试名称/资源关联后清理本轮 UI 元数据，不删除业务表。
- 修改 `AGENTS.md`、`.env.example`、项目及前端 `README.md`、`docs/SOURCE_API.md`、`docs/SCAN_RESOURCE_API.md`、`docs/IMPLEMENTATION_STATUS.md`、`docs/ROADMAP.md`、`docs/TESTING.md`，新建本交付记录。
- 本机被忽略的 `.env.local` 新增一把全服务凭据加密主密钥；不提交密钥或真实密码。

## 验收结果

| 项目 | 结果 |
|---|---|
| 完整后端测试 | Foundation 32 + Provider 3 = 35 项通过，无失败/错误/跳过 |
| 专项再次验证 | 动态 Source 5 项通过 |
| 港口测试库 | port_source_test → container_event，12 字段，id 为主键，包含 container_no/event_time |
| 调度测试库 | another_source_test → train_dispatch，3 字段；无 container_event |
| 第三次登记 | 同一后端实例动态登记并扫描，无新增代码或配置组 |
| 密码与错误 | 密文存储；查询/编辑/浏览器存储不含密码；错误密码和网络失败不虚报成功 |
| 连接与边界 | 连接/语句/结果集关闭；扫描只读元数据，Foundation 无业务明细表 |
| 前端构建 | npm run build 成功（保留既有体积警告） |
| 浏览器 | test:smoke 与 test:source-live 通过；清理本轮临时登记后保留原有数据 |

真实页面截图位于 `foundation-web/.runtime/source-dialog.png`、`source-list.png`、`source-fields.png`、`source-scans.png`。截图来自本机真实 API，展示的是专用测试库结构，不是生产广州港数据。

## 使用与剩余限制

VS Code 选择“启动演示前端”，进入“接入管理 → 数据源管理 → 新增数据源”。填基本信息和连接信息，选择适合的加密连接方式，保存并测试，然后开始扫描、查看结果和字段。密码编辑留空保留；停用后禁止新测试和扫描。旧记录无需转换也能使用，编辑转换时需要重新输入密码。

当前仅 MySQL，IPv4/DNS 主机；扫描同步执行，大量表或高延迟网络需要后续异步优化。大规模分页/并发未做容量验收。加密主密钥必须与数据库配套备份，尚未提供轮换或 Vault 集成。实验前端没有服务端管理身份认证，仍绑定本机；远程部署需要身份权限和 HTTPS。旧 Provider 仍服务指定来源，新增 Source 不自动生成其业务 API；本次没有修改 Resource、Capability 或 Provider 业务实现。

后续补充：2026-10-03已在独立V0.2任务中完成REST启停及动态Source来源匹配。Provider仍服务指定数据库，指向同一地址、端口、数据库和表的动态Source现在可登记并启用对应REST能力。当前验证与限制以[REST启停交付记录](REST_RUNTIME_CHANGELOG.md)为准；本页保留动态接入阶段的交付记录。
