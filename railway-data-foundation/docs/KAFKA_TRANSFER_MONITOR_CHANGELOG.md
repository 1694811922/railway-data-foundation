# Kafka 传输监测交付记录

> 本文为上一轮历史记录。角色入口和监管接口已按[管理员归口交付](KAFKA_MONITOR_ROLE_CHANGELOG.md)调整，以[当前指南](KAFKA_TRANSFER_MONITOR_GUIDE.md)为准。

日期：2026-10-16。

## 本轮完成

- 三角色共用 `/kafka-monitor` 页面。管理员/提供方查看各自有权访问的 Kafka 发送日志和订阅消费摘要；需求方直接查询本主体接收日志。接收任务增加“传输日志”入口。
- 中文筛选、10/20/50分页、展开技术详情、手动/每5秒刷新、隐藏页面暂停轮询、失败清空及重试、旧响应隔离；显式演示不访问真实接口。
- 独立需求方 Flyway V2 新增 `receiving_transfer_log`，字段有中文备注，不修改源业务表，不增加底座业务明细表。
- 创建、真实账号操作、状态转移、重启恢复、批次落库/去重、位置提交确认/未确认、摘要补报/确认/拒绝持久记录。
- 批次日志与消息、数据库检查点及待补报摘要同事务；数据库失败不生成成功日志。Kafka提交在数据库事务后执行，回执日志缺失明确为未知。补报状态与对应过程日志同事务。
- 原发送技术摘要复用现有接口与归属检查；管理员可看到采集待补报、丢弃、隔离及存储失败，避免宣称无损审计。订阅摘要展开显示编号、授权版本及观察时间。

## 主要文件

| 文件 | 作用 |
|---|---|
| `demand-service/.../db/migration/V2__transfer_logs.sql` | 独立接收过程日志及索引，继承任务主体归属 |
| `demand-service/.../TransferLogs.java` | 登录主体限定、筛选分页、提交及补报状态查询 |
| `demand-service/.../TransferLogController.java` | 只读日志接口，仍使用统一响应 |
| `DurableInbox.java` | 事务落库日志、提交回执、状态恢复及补报日志 |
| `ReceivingService.java`、`ReceivingController.java` | 页面操作同事务留痕，使用真实账号编号 |
| `ReceivingWorkers.java` | 后台实际状态、提交和摘要确认采集 |
| `foundation-web/src/views/runtime/KafkaMonitorView.vue` | 三角色统一监测页面 |
| `foundation-web/src/components/KafkaProductionLogs.vue` | 发送日志与采集状态 |
| `foundation-web/src/components/ReceivingTransferLogs.vue` | 接收过程与批次位置展示 |
| `foundation-web/src/utils/transferLogs.js` | 中文映射、参数和响应校验 |
| `foundation-web/src/api/receiving.js`、路由、菜单、`ReceivingView.vue` | 独立需求方通道及操作入口 |
| `KafkaConsumptionPanel.vue` | 订阅消费报告编号、授权版本及时间详情 |

接口和操作方法见 [使用指南](KAFKA_TRANSFER_MONITOR_GUIDE.md)。

## 验证结果

1. 主模块不同测试共221项通过：Foundation145项（完整运行143项及显式浏览器补验2项）、Provider36项、Demand40项（独立测试库完整执行）。不要把reactor内需求方25项默认跳过计为通过，也不要重复计算两次执行的单元测试。
2. 需求方新增6项集成测试：未知/未确认/已确认提交、重读去重、失败回滚、状态去重和真实操作身份、补报事务、日志分页/越权/参数/401及重启恢复。原提交顺序测试增加成功/不确定回执验证。
3. 前端 `npm test` 177项通过，`npm run build` 成功；保留原有大文件分块提示，无构建错误。
4. 实际管理员/提供方/需求方浏览器、真实 Broker 及独立测试库完成：发送1条后通过提供方界面再单条及勾选发送3条，需求方累计4条真实落库；源内容核对、后台持续接收、数据库故障不提前提交、恢复、服务重启、停止/恢复、日志展开/筛选/失败重试/晚响应隔离及原下载流程通过。订阅撤权和原REST共享回归通过。
5. 实际未登录401、管理员/提供方接收日志403、需求方B列表总数0及指定其他主体任务403通过；正常摘要与提供侧归属机制保留。
6. 显式演示浏览器同时覆盖监测页，未产生真实API请求；原三角色/注册浏览器补验通过。

证据在工作区生成目录：`.runtime/kafka-monitor-backend-final.log`、`kafka-monitor-demand-final.log`、`kafka-monitor-roles-final.log`、`kafka-monitor-front-tests.log`、`kafka-monitor-front-build-final.log`、`kafka-monitor-demo-final.log`、`kafka-monitor-chain-verified.log`。真实链路证据目录为项目下 `.runtime/kafka-chain-17144102370959250925/`，包括三角色监测截图及 `cleanup.json`。

前两轮新浏览器断言因Element Plus筛选框定位不准确而失败，不计成功；修正为选择框容器后完整重跑通过。之后一次因本机服务已停止未进入测试，恢复原服务后重新执行。原链路生成汇总中接收条数旧表达式为 `messages+1`，本轮修正生成代码为与实际SQL/分页/浏览器断言一致的 `messages+3`；已保留旧证据，不把旧汇总字段当成实际接收条数。

## 数据与运行状态

- 本机三个后端健康、前端5173运行，需求方已更新新JAR并应用V2，所有日志表字段备注完整；底座仍V17，业务源仍V2。
- 原 `port_source_db.container_event` 20条的完整内容指纹保持不变。测试使用隔离主体、授权、任务及数据库；临时登记与身份清理成功，临时服务停止。Windows测试主题按原限制保留，未在线删除。
- 账号、原消息与检查点不重置；旧接收过程不回填虚构事件。从本次更新后的实际事件开始保留日志。
- 未提交、未推送Git。云端Docker包同步包含本轮代码；本机无Docker，云端容器运行与备份恢复仍待服务器验收，不能把本机真实链路当成云端已上线。

## 限制

日志不是跨Kafka与MySQL的全局事务证明。落库后进程中断或回执日志写入失败时，提交结果保持未知。数据库完全不可用期间无法持久保存过程日志；提供侧现有有界队列/补报也可能存在缺口，前端予以标明。接收日志当前没有自动归档/清理或导出功能，没有扩大业务消费、CDC、多集群或高可用范围。
