# 开发规则

- V0.1 已完成并通过独立 MySQL 测试。V0.2 已实现能力登记、Provider REST、REST运行时启停、Kafka生产和Topic元数据验证；Kafka运行时启停与CDC尚未实现。用户要求暂停V0.3，不得提前实现治理模块。
- Java 17、Spring Boot 3、Maven、MyBatis Plus、MySQL 8；数据库变更使用追加式 Flyway 迁移。
- Foundation 只保存元数据与治理信息，不保存业务明细，不代理 Source → Demand 数据。
- Provider的REST查询先向Foundation读取能力控制元数据，再直连Source；请求头X-Capability-Id用于选择能力，尚不提供V0.3身份或共享授权。停用、来源不匹配或控制状态不可用时拒绝新的查询。
- Kafka Topic、CDC Stream 是 Capability，不是 Resource；外部组件通过 Adapter 接入。
- 新数据源的地址、端口、数据库和用户名通过配置表动态登记；密码通过内部 CredentialService 加密保存，主密钥由环境变量提供。旧环境变量引用保留兼容。不得提交真实凭据，不在 API 响应、日志中返回密码；密码仅作为写入参数。
- 业务含义、责任单位、安全级别和权限需要人工确认。
- 每次修改先阅读 README 与 docs；实际运行相关测试后才更新完成标记。
- 真实资源保存、发布和字段编辑失败不得转为mock成功；超时结果未确认时提示刷新检查，不自动重发。真实资源详情及指定扫描任务的读取失败不得用其他演示字段替代；演示字段按资源编号隔离。
- 旧原型代码已清理；工作区根目录 .legacy-backup 保存历史数据与配套凭据，不参与新版运行，不得提交。旧测试结果不能当作新版已交付功能。
