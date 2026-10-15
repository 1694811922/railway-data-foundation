# 外部 REST 调用方示例

V0.5 第一步：复用现有授权、提供服务及计量接口，验证外部系统如何接入统一数据底座。无需安装依赖，使用 Node.js 20.19+。

从 `railway-data-foundation` 目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/run-demand-rest-example.ps1 `
  -CapabilityId CAP_实际编号 -RelationshipId REL_实际编号 `
  -Fields 'container_no,event_time' -PageSize 7 -MaxPages 5
```

先由管理员发布资源、启用能力、确认启用共享关系并签发需求方令牌。命令提示安全输入令牌；运行仅输出记录数量及追踪编号，业务数据在内存中逐页处理后释放。

| 文件 | 作用 |
|---|---|
| `client.mjs` | 调用说明校验、提供方地址约束、授权查询、受限分页和取消 |
| `cli.mjs` | 可执行示例，凭据临时从环境读取，仅输出安全摘要 |
| `client.test.mjs` | HTTP 合约、异常、分页、权限隔离及取消测试 |
| `live.mjs` | 已启动本地服务上的真实授权、取数、审计与计量核对，自动清理临时配置 |
| `package.json` | `npm test`、`npm start` 和 `npm run test:live`，无第三方依赖 |

完整配置、流程、接口约定与真实验证方法见 [接入规范](../../docs/DEMAND_REST_INTEGRATION.md)。CLI 亦可由外部进程注入 `DEMAND_CALLER_TOKEN` 等环境配置运行；手工使用时推荐安全输入包装脚本，避免把令牌写在命令历史中。

这是固定 REST 适配器的接入示例，不含业务存储、需求方服务、Kafka 消费或 CDC；不提供自动重试、跨页快照和恰好一次处理。真实错误不会回退演示数据。
