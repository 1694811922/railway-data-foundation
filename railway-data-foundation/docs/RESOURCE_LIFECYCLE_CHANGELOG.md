# 资源生命周期改造记录（2026-10-17）

本轮按“保留原审批，重新发布后管理员手动恢复共享”实现。操作及限制见[使用指南](RESOURCE_LIFECYCLE_GUIDE.md)。

## 主要文件

| 文件 | 用途 |
|---|---|
| `resource/ResourceLifecycleService.java` | 状态迁移、影响预览、快照校验、REST暂停及Kafka撤权意图的原子提交 |
| `resource/ResourceActionRequest.java`、`ResourceController.java` | 版本及原因校验、新生命周期接口和兼容首次发布响应 |
| `resource/ResourceLocks.java` | 固定资源父记录优先的锁顺序，串行化授权和资源生命周期 |
| `ResourceService.java`、`ResourceHistoryService.java` | 退役只读、版本保护的删除、真实操作人和原因留存 |
| `V20__resource_lifecycle.sql` | 状态中文注释、历史原因、REST事件动作长度；不改旧历史 |
| 共享、订阅、凭据、消费摘要及接收授权服务 | 父资源锁与授权核验，防止下架并发后恢复旧权限 |
| `ResourceLifecycleDialog.vue`、`ResourceLifecycleNotice.vue` | 影响及原因确认、冲突处理、持久撤权提示和管理员处理入口 |
| `ResourceListView.vue`、`ResourceDetailView.vue`、历史组件及状态工具 | 四状态筛选、操作、标签、退役字段只读、原因和操作人 |
| `mock/resourceLifecycle.js` | 独立演示状态迁移，不接触真实系统 |
| `ResourceLifecycleIntegrationTest.java`、`resource-lifecycle-live.mjs` | 状态、事务、并发及实际浏览器故障/冲突验证 |
| `KafkaFoundationLiveTest.java` | 真实提供方下架/重新发布、实际Broker撤权及需求方新凭据恢复 |

后端路径默认位于`foundation-service/src/main/java/cn/railway/foundation`；迁移位于其`resources/db/migration`，前端位于`foundation-web/src`。

## 事务与并发

资源操作先锁资源父记录，再按编号锁关联REST关系、Kafka申请及执行记录。共享/订阅的新建、审批、配置、领取凭据及接收授权核验也先锁相同父记录。需要先查父编号的子操作使用READ_COMMITTED，避免等待父锁后继续读取旧的一致性快照。资源编辑、字段维护本来就先锁父记录。

状态、版本、历史、REST暂停/终止及Kafka撤权意图同一事务提交；历史写入失败整笔回滚。数据库并发冲突返回409，不在服务器或浏览器自动重发写操作。Kafka外部调用由原协调器在资源事务提交后执行，未成功保留待处理状态；不把外部权限执行描述为数据库分布式原子事务。

## 验证记录

已执行并通过：

| 验证 | 结果与证据 |
|---|---|
| 完整后端 | Foundation165、Provider36；独立需求方45，共246项不同测试。完整reactor需求方数据库用例默认跳过30项，已另行在隔离测试库全量45项通过，未把跳过算通过。根目录`.runtime/resource-life-full-final.log`、`resource-life-demand.log` |
| 新增生命周期 | 10项独立集成测试：迁移/历史原因/版本冲突/退役只读/快照校验/草稿删除/历史失败回滚、编辑/配置/领取与下架并发，以及实际浏览器状态与故障流程；角色集成新增所属/跨主体/需求方/无归属/未登录校验 |
| 前端 | `npm test`190项、`npm run build`成功；存在既有入口包体积提示，非构建错误。真实浏览器读取故障、写入故障保留输入、并发编辑后409及不可重复提交、完整状态/历史和退役字段只读通过 |
| 显式演示 | 真实API全部拦截拒绝情况下，演示下架/重新发布/退役/原因及历史通过，无任何真实请求。修复首次验收发现的演示原因遗漏；旧基线原因仍为空 |
| 实际REST/Kafka | 项目`.runtime/kafka-chain-166742377687159127`：提供方真实登录页面下架、生产准入403、REST拒绝、Broker身份及ACL实际撤回、接收任务BLOCKED；重新发布不自动恢复；管理员手动启用REST、配置新Kafka身份，需求方领取/更新任务凭据及手动恢复后RUNNING，旧凭据拒绝，4条历史接收记录保留。原有发送、落库、数据库故障不提交位置、进程重启、摘要及监测、撤权回归同次通过 |
| Broker故障 | 集成测试注入Broker不可用，资源仍下架、撤权意图保留；快速重新发布也不撤销意图，恢复后只执行撤权、不重新授权。真实链路核对实际Broker撤权；未为此停止用户的常驻Broker |
| 部署配置 | 16项管理测试通过；源码包哈希及秘密排除另行核对。未实际云端Docker构建或部署 |

首轮完整回归发现不存在的资源参数错误从400变404，已修复并完整重跑通过；中间失败结果不计为通过。测试仅使用独立元数据、主体、授权及主题，原业务表摘要不变、20条记录保留；隔离链路资源/账号/接收任务及生命周期测试来源最终均0。Windows主题按已有限制保留，不在线删除。

本机更新到新jar，底座迁移V20成功、来源V2及需求方V4保留，三个健康接口正常；原私有配置文件完整哈希未变，未更换既有凭据。重启使原登录会话失效，刷新后使用原账号重新登录。未进行Git提交推送，也未部署云端。

最终补验：在同一资源上并发执行REST新申请、REST启用、Kafka配置与下架，核对下架后无ACTIVE关系、无GRANTED意图、策略和关系数量一致；新增对话框关闭后旧失败响应晚到的真实浏览器检查。扩大现有用例后10项生命周期验收再次通过（根目录`.runtime/resource-life-final-concurrency.log`），未新增或修改生产逻辑。前述246项不同测试计数不重复累计该次补验。独立测试登记已清理。源码包494个文件的逐项SHA256和ZIP完整性通过，10项本机私有值未进入包；截图已复制到工作区“交付文件”。
