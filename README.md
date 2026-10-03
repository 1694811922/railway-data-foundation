# 铁路统一数据底座虚拟接入与共享治理原型系统

当前工程位于 [railway-data-foundation/](railway-data-foundation/README.md)。使用Java 17、Spring Boot 3、MyBatis Plus和MySQL 8；已完成动态数据源接入、扫描与资源发布、字段维护、能力登记、REST验证与运行时启停、Kafka生产及元数据验证，并提供Vue 3中文前端。V0.3仍暂停。

- [启动与开发说明](railway-data-foundation/README.md)
- [实现进度](railway-data-foundation/docs/IMPLEMENTATION_STATUS.md)
- [测试记录](railway-data-foundation/docs/TESTING.md)

旧版 Java 21 / JPA / H2 工程、前端、脚本、文档与缓存已清理。旧 H2 数据及其配套凭据归档在 `.legacy-backup/`，仅用于保留历史数据，新版不会加载；该目录已被 Git 忽略。`.runtime/m2/` 为新版仍使用的 Maven 依赖缓存。
