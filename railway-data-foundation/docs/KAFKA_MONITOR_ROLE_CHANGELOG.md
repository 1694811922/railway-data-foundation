# Kafka 监测归口管理员交付记录

日期：2026-10-16。本文替代上一轮“三角色共用独立监测页”的安排，历史记录保留。

## 本轮结果

| 角色 | 页面入口 | 范围 |
|---|---|---|
| 管理员 | 运行监管 → Kafka 传输监测 | 发送日志、接收过程、订阅消费摘要 |
| 提供方 | 数据发送 → 发送记录 | 本主体来源的发送记录；发送期间禁止打开抽屉 |
| 需求方 | 数据接收 → 任务日志 | 本主体所选任务的过程、错误及进度 |

供需双方不能进入独立监管路由或调用管理员查询接口。原主体范围、分页总数和编号校验保留。管理员只能获得技术事件，不获得需求方消息、下载接口、数据库连接或接收凭据。REST调用记录及共享审批菜单未改。

## 主要实现

- 底座追加 `V18__receiver_monitor.sql`、`ReceiverMonitorRequest/Service/Controller`：独立技术认证、严格字段及枚举校验、按授权推导关联归属、事件去重与内容冲突拒绝、管理员筛选分页。发生及接收时间统一按UTC保存。
- `POST /api/v1/telemetry/receiver-reports` 只接收白名单技术事件；`GET /api/v1/kafka-transfer-events` 必须是真实管理员。未登录401、普通身份403。
- 需求方追加 `V3__monitor_outbox.sql`、`TransferJournal`、`MonitorReporter`：过程日志与监测队列同事务；迁移回填已有真实日志；稳定事件编号、有限退避、永久错误隔离、重启补报。补报自身不再产生日志。
- 新技术凭据独立于订阅令牌，撤权后阻塞事件仍可上报；原消费及摘要核验不放宽。凭据仅在服务端，前端不接触。
- 共用 `ReceivingTransferLogs` 和 `KafkaProductionLogs`；管理员新增接收过程页签，双方使用本页抽屉。失败清空旧结果、重试、定时刷新及旧响应隔离均保留。
- Windows `scripts/enable-receiver-monitor.ps1` 与 Linux `deploy/linux/manage.py enable-receiver-monitor` 提供显式配置升级。验证原私有配置、备份、生成新增凭据和固定节点；已有密码与密钥保持不变，重复执行不轮换。

完整使用及配置步骤见 [监测指南](KAFKA_TRANSFER_MONITOR_GUIDE.md)，云端升级见 [部署步骤](BAIDU_CLOUD_DOCKER_DEPLOYMENT.md)。

## 验证结果

| 验证 | 最终结果与证据 |
|---|---|
| 完整后端及独立需求方验证 | 231项不同主模块测试通过：Foundation151、Provider36、Demand44。包含显式角色及注册浏览器测试；不重复计数多轮执行。根目录 `.runtime/monitor-owner-backend-final.log`、`monitor-owner-backend.log` 中两项显式浏览器测试、`monitor-owner-demand-http.log` |
| 前端 | `npm test` 179项通过，`npm run build` 成功；`monitor-owner-front-verified.log`、`monitor-owner-build-verified.log` |
| Linux配置 | 16项配置管理测试通过，旧清单校验、升级、幂等、拒绝损坏配置均覆盖 |
| Windows配置 | 独立夹具首次升级及重复执行通过，真实原配置前缀完全保留；`monitor-owner-windows-config.log` |
| 真实链路 | 独立授权与主题，真实提供方页面发送3条，连同初始消息实际落库4条并核对内容；停止/恢复/重启、数据库故障、位置提交、撤权、原REST与三角色浏览器通过；`monitor-owner-chain-final.log` |
| 管理员监管 | 实际接收端补报可查询；撤权后BLOCKED事件到达；普通角色403，管理员失败清空/重试通过；项目 `.runtime/kafka-chain-4259675872454479892/monitor-browser.log` |
| 可靠补报 | 同事务回滚、重复事件、冲突拒绝、永久隔离、旧日志回填、真实HTTP断连后恢复及新Reporter实例续报通过 |
| 演示与秘密边界 | 显式演示不访问真实监测；技术上传不含消息键值/头、自由异常文本或秘密；部署包另行校验文件哈希及私有配置排除 |

早期失败轮次未计通过：已修复扫描式日志时间返回类型、旧测试迁移版本预期、浏览器抽屉定位歧义及Windows文件占用/替换兼容问题，最终结果以上表为准。

## 本机与清理

本机底座已升级V18、需求方V3，实际已有4条过程日志补报完成；原 `container_event` 20条记录未修改。原配置、密码和加密密钥保留，重启后需重新登录。独立测试身份及登记已清理，测试进程已停止；Windows测试主题保留，避免在线删除的文件锁问题。清理证据见上述链路目录 `cleanup.json`。

更新后的源码部署包位于工作区 `交付文件/railway-cloud-deploy-20261016.zip`，不含本机秘密或运行数据。不提交、不推送Git。

## 限制

接收事件标为“接收端自报”，不是可信计费、业务成功或实时健康证明。没有事件显示未知，最近事件仅表示记录发生时状态。业务消息仍仅存需求方数据库；监测失败不回滚业务落库，不宣称跨Kafka与MySQL的全局恰好一次。本轮未增加自动日志清理策略、CDC、多集群或高可用。Docker云端镜像构建、实际运行和备份恢复仍需在目标服务器验收，本机静态配置通过不能算已上线。
