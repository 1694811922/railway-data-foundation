# 本地 Kafka 部署实验与传输场景工具交付记录

本文件保留上一轮阶段结果。随后已完成[本地重启与配置保护完善](KAFKA_RESTART_HARDENING_CHANGELOG.md)，原凭据及消费进度连续两次重启复验通过；历史失败保留，Windows主题删除仍未解决。本文的9个保留主题与测试结果属于当时记录，最新数量以新交付记录为准。

日期：2026-10-11。依据《0924任务.docx》内容1继续实施，范围为无Docker的原生本地运行工具、真实平台联调和三类有界传输实验。**稳定部署、正式业务压测和最终接口选择结论尚未完成。**

## 本轮实现

- 固定Apache Kafka 3.9.1服务端运行依赖，Maven解析后记录SHA-512清单并在重用时核对。没有把EmbeddedKafka作为部署服务。
- 新增本地KRaft启动、正常停止、集群核对和静态账号权限检查；数据目录固定，不重新格式化已有目录。中文工作区使用相对路径规避配置编码问题。
- 探测、生产、订阅管理员、Broker内部及控制器身份分开，秘密随机生成并保存在Windows私有权限目录；无匿名超级用户，无VITE运维秘密。
- `start-local.ps1 -Kafka`显式加载私有后端覆盖配置，不修改原数据库凭据或源归属；已有不同配置进程拒绝静默改配，同一PID及配置摘要支持重复启动检查。
- 增加真实完整链路的`-NativeLocalBroker`选项，在隔离测试库中验证现有Foundation/Provider/浏览器与原生Broker。
- 增加独立接口实验工具：持续提交、集中突发、间歇提交及Kafka消费者关闭/重入，记录数量、字节、接收速率、延迟分位数和剩余接收时间。REST对照为独立源服务夹具，不是生产Provider。
- 增加VS Code实验任务、计算/边界/PowerShell语法检查、部署使用指南和正式研究计划。

## 文件用途

| 文件 | 用途 |
|---|---|
| `examples/local-kafka/pom.xml` | 独立原生Broker运行依赖声明 |
| `scripts/kafka-local-common.ps1`、`install-local-kafka.ps1` | 私有路径、Java工具、依赖完整性和目录权限 |
| `scripts/start-local-kafka.ps1`、`stop-local-kafka.ps1` | 明确启动与正常关闭，不删除或强制格式化已有集群 |
| `scripts/java/LocalKafka.java`、`kafka-logback.xml` | 原生KRaft、元信息与权限、脱敏操作结果 |
| `scripts/test-local-kafka.ps1` | 重启后身份、ACL与消息验证；当前失败如实保留 |
| `scripts/java/InterfaceLab.java`、`InterfaceLabCheck.java` | 外部合成传输实验、计算和计数验证 |
| `scripts/run-interface-lab.ps1`、`test-interface-tools.ps1` | 有界实验与脚本校验入口 |
| `scripts/start-local.ps1` | 保留默认模式，追加显式Kafka覆盖配置及运行模式核对 |
| `scripts/test-kafka-foundation-live.ps1`、`KafkaFoundationLiveTest.java` | 原生Broker复用模式、隔离库/真实浏览器及精确测试身份清理 |
| `.vscode/tasks.json` | 三项实验启动、执行与停止任务 |
| `docs/LOCAL_KAFKA_EXPERIMENT_GUIDE.md`、`INTERFACE_EXPERIMENT_PLAN.md` | 操作入口、实测口径及后续研究验收 |

## 实际验证结果

下表日志位于工作区根目录的`.runtime/`，不是项目子目录的`.runtime/`。CSV、私有Kafka运行目录与`kafka-chain-*`证据位于项目子目录的`.runtime/`。

| 验证 | 本轮结果 |
|---|---|
| 完整后端回归 | 完整reactor为Foundation131通过、1个显式角色浏览器跳过，Provider30通过；随后单独开启该浏览器测试并通过，合计162个不同测试得到验证。`.runtime/interface-backend-final.log`、`interface-roles-final.log` |
| 前端测试与构建 | npm test 129全部通过；npm run build成功，已有大包警告保留。`.runtime/interface-web-test.log`、`interface-web-build.log` |
| 独立Kafka示例默认测试 | 16通过、完整链路默认跳过1；`.runtime/interface-kafka-default-tests.log` |
| 原生Broker完整平台链路 | 显式完整链路1项通过，真实消息1条320字节、外部读取、消费观察和撤权拒绝；原REST兼容回归通过。`.runtime/native-kafka-chain.log`，证据`.runtime/kafka-chain-6231084961528944692/` |
| 三角色真实浏览器 | 管理员/提供方/需求方、账号及本人改密回归通过；`.runtime/role-browser.log` |
| 场景工具 | 先60条烟雾验证，最终三场景每种传输180条；六行均180唯一接收、错误0、重复0、92160载荷字节。最终CSV为项目`.runtime/interface-lab/20261011-153223/results.csv` |
| 计算与脚本检查 | 合成长度/序号、重复计数、分位数、CSV单位、非法范围拒绝及PowerShell语法检查通过；`.runtime/interface-tools-check.log` |
| 原生集群权限只读核对 | 集群编号匹配，默认主题领导节点可用，匿名/通配身份ACL不存在；临时身份0、临时ACL0；**测试主题保留9项**。`.runtime/native-kafka-audit.log` |
| 平台运行 | Foundation18080、Provider18081、前端5173均HTTP200；显式Kafka模式重复启动核对通过。`.runtime/native-platform-final.log`、`native-platform-repeat.log` |

这些验证不能证明实际业务高峰容量、跨机器传输或最终接口选择。本次试验规模小、单机单副本，REST为测试夹具，两类间断场景恢复语义不同，正式研究仍按[实验计划](INTERFACE_EXPERIMENT_PLAN.md)推进。

## 未通过的部署验收

1. Windows在线删除测试主题时，日志目录重命名发生`AccessDeniedException`，原生Broker退出。保留失败日志，不记为稳定部署成功。此后Windows实验配置禁用在线主题删除；测试撤销身份与权限，保留主题及准确清单。
2. 重启验证曾读取到消息，但最新完整复验中订阅SCRAM身份认证失败，重启恢复验收未通过。没有在测试中重新创建订阅身份来伪造恢复成功。静态运维PLAIN账号仅用于回环实验，不能据此宣称普通订阅凭据可跨重启正常恢复。
3. 本机没有已安装可用的WSL分发版；已询问使用已有Linux服务器、启用WSL或暂缓稳定部署。尚未安装WSL、修改Windows可选功能或重启电脑。

官方下载归档的传输未成功，改用固定版本Apache Maven服务端依赖。本地完整性清单是安装后校验，不冒充Apache归档签名验证。

## 数据与清理

开发库仍V15，未新增数据库迁移；原业务源20条、原2个来源无主体归属保持不变。最终核对`foundation_test`角色账号/主体/来源均为0，`foundation_kafka_live_test`账号/主体/来源/资源/能力/订阅/执行/消费报告均为0；Kafka临时身份/ACL撤销，但保留的测试主题及合成或原模拟源消息没有宣称已删除。Foundation与Provider实际健康接口、前端5173均HTTP200，未登录数据源请求返回401；任务JSON和Git差异空白检查通过。

自动审批曾拒绝删除一个失效测试日志目录，返回`blocked by policy`，未执行。随后验证原生Broker已停止、来源与目标都在本项目私有运行目录，改为归档保留全部文件到`.runtime/local/kafka/offline-test-archive/rdlocal_check_ea30d24e22514f0bbbf05e8bc28c05d0-0`。没有删除其他目录，也没有清理之前记录的旧临时Kafka目录。

新增日志已检查不包含本轮生成的Kafka秘密。私有身份、配置、CSV和证据均在Git忽略目录，不提交或推送Git。后端业务架构和前端页面未扩展；没有CDC、只读查询运行时或需求方业务服务。
