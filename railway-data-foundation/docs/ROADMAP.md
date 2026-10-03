# 分阶段路线图

每阶段按实现 → 编译 → 实际测试 → 修复 → 架构边界检查 → 更新文档推进，只有验证通过的功能才打勾。

| 阶段 | 范围 | 验收目标 |
|---|---|---|
| V0.1 第一小阶段（已完成开发与本机验证） | Java 17 基础工程、统一响应、health、MySQL、Flyway、Source 模拟数据 | 编译、接口测试、数据库迁移验证 |
| V0.1 后续 | Source、真实 JDBC 测试、MetadataAdapter、扫描任务、Resource、Field Metadata | 广州港 Source → 扫描 container_event → 手工补全信息 → 发布资源 |
| V0.2 | Capability、Provider REST、REST 探测与运行时启停、Kafka 生产及 Topic 元数据验证 | 一个 Resource 多种访问能力，底座不消费业务消息 |
| V0.3 | Subject、Policy、Relationship、Access Check | 状态机及字段/时间权限允许和拒绝 |
| V0.4 | Audit、Trace、Metric | 异步审计与窗口聚合，失败不阻断业务 |
| V0.5 | Demand REST、Kafka Consumer、最后接入 CDC | Source → Demand 直连、幂等、CDC 增删改 |
| V1.0 | Schema Change、版本管理、Dashboard、完整演示 | added/removed/modified 检测、业务属性人工确认 |

前端在对应后端稳定后按 Layout → Source → Metadata Scan → Resource → Capability → Policy → Relationship → Metric → Audit → Dashboard 推进。

2026-10-03 完成现有资源前端流程可靠性修复：真实保存/发布/字段编辑失败不回退mock、来源锁定、详情发布、字段与扫描响应隔离。6项前端测试、构建及演示/资源真实流程/两库扫描回归通过；不扩展后端模块，V0.3保持暂停。见[交付记录](RESOURCE_FLOW_CHANGELOG.md)。

2026-10-03 V0.2登记与调用指引已完善：能力详情显示实际提供服务地址、请求头及可复制的调用命令；登记表单增加中文说明与按类型校验。只读取已有控制元数据，未扩展治理模块或改变数据流。45项后端回归、3项前端校验、构建及演示/真实浏览器流程均通过，详见[交付记录](CAPABILITY_GUIDE_CHANGELOG.md)。

2026-10-03 已完成接入管理动态多数据源 MVP：前端登记 MySQL 连接配置，独立加密凭据服务，动态连接工厂及中文测试/扫描/启停交互；保留旧环境引用。两库扫描隔离已验证。此项属于现有接入管理优化，不恢复 V0.3。

V0.1 的数据源、扫描、资源和字段链路已完成。V0.2 的能力登记、Provider REST、REST探测与真实启停、Kafka生产与Topic元数据验证已实现并通过本机集成测试。2026-10-03完成REST启停闭环：Provider按能力编号逐次查询底座控制状态，停用或来源停用后拒绝新请求；动态登记的同一来源也可验证。Kafka本机常驻Broker需另行配置；Kafka启停、CDC与只读模板运行时尚未实现。V0.3仍按用户要求暂停。
