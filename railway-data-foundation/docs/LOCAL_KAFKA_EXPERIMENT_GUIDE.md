# 本地 Kafka 与接口实验使用指南

2026-10-12 本机故障修复：取消原来的24小时消息过期配置，普通主题默认使用 `log.retention.ms=-1`、`log.retention.bytes=-1`；启动时核对所有普通主题的实际保留配置，避免旧实验消息到期清理索引触发Windows文件锁并关闭Broker。消息会持续占用磁盘，需关注 `.runtime/local/kafka/data` 大小；此配置仅用于本机实验，不代表Windows在线删除/压缩已修复或稳定部署已完成。正常启动仍只核对原身份和权限，不重新格式化数据。见 [故障修复记录](KAFKA_WINDOWS_RETENTION_FIX.md)。

更新：2026-10-11。提供无Docker的Windows本地实验工具及原生Kafka平台联调入口。本轮加强验证已通过连续两次重启、原凭据、精确权限、消息和消费进度保留，见[重启完善记录](KAFKA_RESTART_HARDENING_CHANGELOG.md)。历史认证失败根因尚未确认；在线主题删除仍有文件锁和Broker退出问题，因此**Windows实验工具不计为稳定部署完成**。Linux环境选择仍待用户确定；不要在此集群安排重要业务数据。

## 文件与配置

| 文件或目录 | 作用 |
|---|---|
| `examples/local-kafka/pom.xml` | 固定3.9.1的Apache服务端运行依赖，未使用EmbeddedKafka部署 |
| `scripts/install-local-kafka.ps1` | Maven解析运行依赖，复制到忽略目录，记录并复核SHA-512清单 |
| `start-local-kafka.ps1 / stop-local-kafka.ps1` | 回环集群启动与通过停止文件进行正常关闭，不格式化既有数据、不强杀Broker |
| `scripts/java/LocalKafka.java` | 原生KRaft启动、身份和精确ACL设置、集群检查及重启测试 |
| `.runtime/tools/kafka_2.13-3.9.1/` | 已解析的服务端运行JAR及本地完整性清单 |
| `.runtime/local/kafka/data/` | 原生集群数据和元信息；不能删除以修复启动问题 |
| `.runtime/local/kafka/identity.properties / server.properties / backend.env` | 自动随机生成并保留的私有身份、服务端配置与后端覆盖配置；仅当前Windows用户和SYSTEM可读写，Git忽略，不得截图或提交 |
| `scripts/run-interface-lab.ps1` | 三类有界传输对照实验入口 |
| `.runtime/interface-lab/` | 无明文凭据或载荷的CSV、范围说明与保留主题清单 |

主机监听`127.0.0.1:19092`，控制端口19093，单节点KRaft，默认主题`port.container.event.v1`三分区、单副本。仅用于本机实验；不是高可用集群或跨机器TLS部署。没有ZooKeeper或Windows自动开机服务。

探测、生产、订阅权限管理员、Broker内部及控制器账号分开；生成64位随机十六进制秘密，无公共默认密码。客户端探测、生产和普通订阅使用SCRAM-SHA-512；运维及内部PLAIN只在回环监听使用，配置不允许匿名超级用户。SASL_PLAINTEXT仅认证、不加密；跨机器部署须配置SASL_SSL、证书及安全的监听地址，不能直接开放本机端口。

## 启动现有平台

从项目目录运行：

```powershell
./scripts/start-local.ps1 -Kafka
```

首次依赖解析可以联网；缓存完整后使用`-Offline`。安装过程只使用项目Maven依赖缓存，不覆盖JDK、MySQL或现有密码。`-Kafka`明确加载私有覆盖配置，并启用本机订阅权限执行；不改写原`.env.local`的Kafka/数据库配置。未带`-Kafka`时原默认行为保留。

若Foundation/Provider已经按其他配置运行，脚本拒绝静默改配，先停止已有服务，再使用该入口；同一覆盖配置及进程编号匹配时可重复执行。Kafka正常启动只核对已有身份、密码、精确权限及主题，不重写账号凭据。只有本次刚格式化的新集群允许首次初始化；已有数据却缺少`backend.env`时拒绝重新初始化，保留现场进行诊断。文件保留不能代替重启恢复测试。

前端仍使用原工程：

```powershell
cd foundation-web
npm install
npm run dev
```

真实登录、安全初始化管理员及创建角色账号见[三角色指南](ROLE_WORKSPACE_GUIDE.md)。本轮没有增加仓库通用账号密码，也没有创建或接管原来的无归属来源。

## 真实业务链路验证

```powershell
./scripts/test-kafka-foundation-live.ps1 -Offline -NativeLocalBroker -NodeExecutable D:/Dev/node.js/node.exe
```

该命令复用原生Broker，在独立`foundation_kafka_live_test`和随机元数据中启动真实Foundation、Provider及浏览器；实际验证资源/能力登记、审批、权限配置、一次性领取、发送、外部读取、消费观察及撤权拒绝，同时回归外部REST。业务源只读，仍是原20条模拟事件。

测试结束关闭临时服务并清理测试库元数据及测试Kafka身份/ACL；原生Broker保留运行。Windows禁用在线主题删除，证据目录`native-retained-topic.txt`明确记录保留的测试主题，不能宣称消息已清理。

## 场景实验与停止

```powershell
./scripts/run-interface-lab.ps1 -Count 180 -Rate 60 -PayloadBytes 512 -PauseMs 1500
./scripts/test-interface-tools.ps1
./scripts/test-local-kafka-bootstrap.ps1
./scripts/stop-local-kafka.ps1
```

实验REST服务是独立夹具，不是现有Provider；小规模结果不证明生产容量。详细口径、控制变量和正式研究剩余工作见[实验计划](INTERFACE_EXPERIMENT_PLAN.md)。VS Code的“终端 → 运行任务”增加“实验：启动后端与Kafka”“实验：三类传输场景”“实验：停止Kafka”。

本轮增加“实验：验证 Kafka 重启”和“实验：验证 Kafka 初始化”；使用前确认没有正在进行的消费或实验，重启验证会短时关闭本地Broker，不自动停止其他服务。

`test-local-kafka.ps1 -Offline`默认进行两次重启：先读取第一条并提交进度，每次重启后使用原凭据读取下一条，再核对提交进度。还验证错误密码拒绝和缺失配置保护，不能重新创建订阅身份来冒充恢复成功。历史失败日志仍保留；本轮通过的结果仅限本机有限回归。Windows在线主题清理仍不可用，稳定部署须在选定环境重新验收。

`test-local-kafka-bootstrap.ps1`另起独立随机端口的短时原生测试集群，验证首次初始化和固定账号配置保护；不连接数据库，也不改动主集群账号。全部检查执行且测试Broker正常停止后才返回通过。测试文件保留在私有运行目录，不作为正式部署服务。
