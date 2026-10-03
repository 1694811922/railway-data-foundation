# 动态多数据源接入接口（MySQL MVP）

Foundation 自身仍使用固定 Spring DataSource 连接 foundation_db。外部数据库按 source_id 动态查询配置、解析凭据并建立短期 JDBC 连接，不为每个 Source 新增 Bean。所有接口使用 `/api/v1` 和 `{code,message,data}`，前端不能直接连接数据库。

## 新增与编辑

`POST /sources`，例如：

```json
{
  "source_name": "广州港业务数据库",
  "source_type": "MYSQL",
  "host": "192.168.1.20",
  "port": 3306,
  "database_name": "port_source_db",
  "username": "foundation_reader",
  "password": "填写该只读账号的密码",
  "ssl_mode": "PREFERRED",
  "description": "广州港海铁联运业务数据源",
  "status": "ENABLED"
}
```

登记只校验并保存配置，不访问业务数据库。连接不通也能登记，使用“测试连接”判断连通性。编号由系统生成，形如 `SRC_` + 32 位 UUID。数据库类型目前仅支持 MySQL；host 支持 IPv4/DNS，不支持 IPv6 字面值或完整 JDBC URL；端口为 1–65535，数据库名为 1–64 位字母、数字或下划线，用户名最多 128 位，密码最多 1024 个字符。

`PUT /sources/{sourceId}` 使用同样的完整配置。密码不填写、传 null 或空字符串表示保留原密码；填写非空新密码才更新密文。用户名可更新，空密码不清空已有凭据。响应包含连接元信息、状态、credential_ref 和 connection_mode，不包含 password、密文或主密钥。`credential_ref` 由后台生成，客户端不能指定或借用别的数据源凭据。未知字段（包括 jdbc_url）被拒绝。

| 方法与路径 | 说明 |
|---|---|
| `GET /sources?limit=50&offset=0` | 列表，limit 1–200，offset 0–1000000 |
| `GET /sources/{sourceId}` | 连接元信息与状态，不返回密码 |
| `POST /sources/{sourceId}/test` | 真实 JDBC 连接检查，读取产品、版本、耗时 |
| `PATCH /sources/{sourceId}/status` | `{"status":"ENABLED"}` 或 `{"status":"DISABLED"}` |
| `POST /sources/{sourceId}/scan` | 执行只读元数据扫描；接口细节见 SCAN_RESOURCE_API.md |

数据源状态与连接测试结果分开。停用不删除历史，但禁止新的连接测试和扫描；启用不代表已经连接成功。

## 凭据与主密钥

V5 追加迁移创建 `source_credential` 并扩展 `source_system`。普通连接信息存在 source_system，密码经过 AES-256-GCM 加密后存在独立凭据表。每次加密使用随机 12 字节 IV，凭据引用作为认证附加数据，密文格式带 v1 标记，防止密文被挪用到别的凭据。CredentialService 是内部统一入口，提供保存/更新、获取、用户名维护和删除；没有对外读取密码的 HTTP 接口。

`FOUNDATION_CREDENTIAL_KEY` 为一把 Base64 编码的随机 32 字节密钥，保存在数据库之外。无需为每个数据源单独配置环境变量。

`credential_ref` 是后台生成的“凭据引用编号”，不是密码。`source_system.credential_ref` 通过外键关联 `source_credential.credential_ref`；程序为每个新数据源生成独立凭据，数据库当前没有对该外键列加唯一约束。旧环境变量登记的记录不使用此引用。V6 迁移为 Foundation 自建表的全部字段补齐中文数据库备注，Navicat 刷新 `foundation_db` 后在“设计表”的“注释”列可查看；Flyway 自身的历史表由框架维护，不在本项目字段备注范围内。Source 示例表 `container_event` 的 12 个字段已在独立迁移中写有备注。

本机 `start-local.ps1` 首次自动生成密钥并持久写入被 Git 忽略的 `.env.local`。重启复用，不自动轮换。数据库和密钥必须配套备份；丢失或替换密钥会使原凭据无法解密。其他部署环境需由运维提供该密钥；缺失/错误时返回安全的中文错误，不降级为明文保存。

前端密码输入框仅在新增或修改时提交密码，查询和编辑不回显，不保存到 localStorage。正式远程部署应使用 HTTPS 和服务端管理身份认证；当前仍为仅监听本机的实验原型。

已加载的真实 Source 操作在超时或断网时显示错误，不切换成模拟成功。只有显式演示配置或已明确标注的演示数据源才执行模拟操作。

## 动态连接链路

```text
sourceConnectionFactory.openConnection(sourceId)
  → SourceMapper 查询 source_system
  → 检查数据源启停状态与参数
  → CredentialService 按 credential_ref 解密
  → 根据 source_type 选择 SourceConnectionProvider
  → MysqlSourceConnectionProvider 生成 JDBC URL
  → 连接目标 MySQL，标记只读
  → 调用方 try-with-resources 关闭连接
```

SourceConnectionProvider 接收 SourceEntity 和内部 SourceCredential。当前仅 MySQL 实现；扩展类型时增加驱动、Provider、元数据适配器与相应校验即可，不需要为每个数据库配置独立 DataSource。扫描使用任务启动时的连接元信息快照，避免编辑数据库地址后把不同数据库的结构混进同一任务。

Provider能力验证也支持动态登记：按非秘密的主机、端口、数据库与物理表匹配，兼容旧环境引用，Source须启用。指向现有Provider来源的动态Source可以登记并启用对应REST能力；新增其他数据库仍仅接入元数据，Provider不会自动生成其业务访问接口。数据源停用会令新REST运行状态检查不可用，完整说明见[能力接口](CAPABILITY_API.md)。

MySQL 连接统一 UTF-8，连接超时 3 秒、socket 超时 5 秒、SQL 查询超时 5 秒。禁用多语句和本地文件加载。加密连接支持 PREFERRED（默认）、REQUIRED、VERIFY_CA、VERIFY_IDENTITY；DISABLED 仅用于本机实验。不维持外部数据库常驻连接池。各次探测与元数据读取完成即关闭连接、语句和结果集。

## 中文连接失败反馈

连接测试成功返回 `connected=true`、database_product、database_version、response_time_ms 和“连接成功”。失败仍为 HTTP 200 / code 0（表示测试操作已执行），返回 `connected=false`、`error_code=SOURCE_CONNECTION_FAILED` 以及安全原因：

- 用户名或密码错误
- 数据库名称不存在
- 当前账号权限不足
- 数据库地址或端口无法连接
- 数据库地址无法解析，请检查 IP 或域名
- 网络连接超时
- 数据库地址不可达或连接已中断
- 其他错误：检查连接配置、加密连接设置和账号权限

分类使用 JDBC 错误码、SQLState 和异常类型，不使用原始异常消息。配置错误返回 HTTP 400，ID 不存在返回 HTTP 404，不返回堆栈或密码。扫描失败保存安全的 failure_reason，任务为 FAILED，不导致服务退出。

## 旧登记兼容

旧记录仍可使用 connection_env_prefix，保持原有测试/扫描链路；列表会解析并展示旧连接元信息。旧请求不得混用动态字段或 password。前端编辑旧记录会转换为动态配置，需重新输入密码，Source ID 和既有扫描记录保留。动态记录不接受再转换为环境引用。旧环境方式只是兼容入口，新增界面不展示该技术字段。

现有 Provider REST/Kafka 验证依旧绑定原来的指定来源和服务，不会因新增 Source 自动提供该库的业务接口。本次不修改 Resource、Capability 或 Provider 业务逻辑。需要演示既有 Provider 能力时可继续使用原来的旧数据源记录。

## 测试与操作

本机后端已更新后，前端“数据源管理 → 新增数据源”包含“基本信息”和“连接信息”两个分组。填写名称、类型、备注、地址、端口、数据库名、用户名、密码和加密连接方式，点击“保存并测试连接”，再“开始扫描 → 查看扫描结果 → 查看字段”。新接入 MySQL 不需要修改 Java、配置新的环境变量组或重新打包。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/test-local.ps1 -Offline
```

测试脚本使用专用 foundation_test、port_source_test、another_source_test，向第二个库建立 train_dispatch 结构。DynamicSourceIntegrationTest 验证两个独立库、第三次登记、无串库、12 个事件字段和主键、凭据加密、空密码保留、错误密码、失败任务、启停、旧记录转换、非法配置及 Foundation 无业务明细表。新增单元测试验证 GCM 完整性、密钥缺失与错误、资源关闭和错误分类。
