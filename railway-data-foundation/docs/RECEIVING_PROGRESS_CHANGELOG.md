# 2026-10-14 接收进度诊断交付

需求方“数据接收”列表新增“接收进度”。面板查询真实Kafka元数据与需求方数据库检查点，区分无可用消息、未建立检查点、待接收、已追平和位置失效；失败不显示虚假0，也不回退演示成功。

## 主要改动

| 文件 | 作用 |
|---|---|
| `demand-service/.../ReceivingProgressService.java` | 主体隔离、只读一致快照、授权核验、主题身份保护和分区比较 |
| `demand-service/.../KafkaConnections.java` | 有界Admin元数据查询，不创建Consumer、不加入消费组或提交位置 |
| `demand-service/.../ReceivingController.java` | 新增任务下的只读progress接口 |
| `foundation-web/src/components/ReceivingProgress.vue` | 中文进度面板、错误清空重试、关闭/身份变化隔离旧响应 |
| `foundation-web/src/utils/receivingProgress.js` | 响应完整性与未知值校验、中文状态 |
| `foundation-web/src/views/receiving/ReceivingView.vue`、`src/api/receiving.js` | 新入口与独立需求方请求通道 |

字段说明、接口与操作见 [接收进度指南](RECEIVING_PROGRESS_GUIDE.md)。没有新增数据库表、迁移、业务消息代理或后台生产功能；底座V17、源V2、需求方V1不变。

## 验证

- 完整后端reactor：Foundation145、Provider36全部通过，包含真实三角色和注册浏览器；Demand12项单元测试通过，数据库专项24项全通过，去重共205项主模块测试通过。
- 新增6项进度计算测试及2项数据库/权限测试，覆盖空范围、追平、待接收、缺失检查点、历史过期/越界、空元数据、只读不改变版本和检查点、跨主体403、未登录401、撤权、Broker故障和主题重建。
- 前端161项测试和`npm run build`通过；仅保留现有包体积提示。真实进度面板显示落库下一位置与Kafka提交位置，注入503后清空旧表、重试返回真实数据，关闭后的晚到响应不重新显示。
- 最终原生Kafka链路 `.runtime/kafka-chain-17004197568589019014/` 通过：提供方页面真实单条/批量发送3条，需求方累计落库4条；进度为CAUGHT_UP/0且查询前后版本和数量不变。管理员、提供方、其他需求方直接查询拒绝，实际撤权后progress返回403。旧REST授权、Kafka实际撤权、持续接收、重启和数据库故障恢复保持正常。
- 第一轮真实链路 `.runtime/kafka-chain-3960429612634327137/` 也通过；补充直接角色及撤权断言后以上述最终证据为准。初次专项遇到MySQL未运行，原配置启动后重测；测试模拟对象重新设定方式修正后24项全通过，失败轮次不计通过。
- 原Source20条及完整摘要不变，临时账号、登记、消息与故障约束清理，测试主题保留。没有向用户当前主题自动生产消息，没有提交或推送Git。

工作区 `.runtime/progress-backend-test.log`、`progress-demand-test.log`、`progress-frontend-test.log`、`progress-build.log`、`progress-chain-final.log` 保存验证记录；最新真实页面截图在上述证据目录 `receiver-real-progress.png`。

## 本机状态与限制

MySQL、原生Kafka和三个后端已按原凭据恢复运行；需求方新包已更新，持久任务继续运行。前端5173已通过独立的`npm run dev`命令启动。自动审批曾拒绝组合启动/查询命令，未给出具体原因；没有重复该组合命令，改用不含后台启动器或私有配置读取的前台开发命令后成功。

位置差不是精确消息条数，查询不建立或重置检查点，不自动恢复任务。授权失效后拒绝查询Kafka位置，但已落库历史数据仍按原规则保留可查。诊断是两次读取间的快照，持续接收中的位置可能变化。不扩展CDC、消费计费、持续生产、多实例或结构同步。
