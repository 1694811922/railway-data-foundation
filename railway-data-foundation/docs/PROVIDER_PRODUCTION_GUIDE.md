# 提供方数据发送

## 操作顺序

1. 启动底座、提供方、Kafka 和需求方接收服务：在工作区根目录运行 `./railway-data-foundation/scripts/start-local.ps1 -Offline -Kafka -Demand`，前端在 `foundation-web` 执行 `npm run dev`。
2. 登录提供方账号，进入 **数据发送**，或在 **我的接口能力 → 发送数据** 进入指定能力。
3. 选择已启用且健康正常的 Kafka 能力，核对资源、物理表与目标主题，查询源记录。
4. 单条点击 **发送**，或勾选当前页记录点击 **发送选中记录**，确认后逐条发送；每批最多50条。
5. 查看本次发送结果。成功返回主题、分区和消息位置；失败或结果未确认时停止后续发送，不自动重试。
6. 切换到需求方账号，在 **数据接收 → 查看数据** 核对消息内容和已落库数量。任务必须已启动、授权有效且订阅相同主题。

提供方、需求方可在两个独立浏览器会话分别登录观察。一个浏览器内退出或切换身份会清空当前页面；需求方后台接收任务仍持续运行。

## 数据边界

业务通道为 `源数据库 → provider-service → Kafka → demand-service → demand_receiver_db`。

源记录查询通过 `/provider` 直接访问提供方服务，底座只返回身份、归属和发送准入元信息。不把源数据、运维令牌或Kafka密码放进前端配置，不经底座后端代理业务数据。

当前提供服务绑定 `container_event` 和服务端指定源库、主题。登记其他表或主题不会自动增加生产适配器，不匹配时明确拒绝。当前源表20条属于模拟业务记录，消息传输和落库由真实服务执行。

## API

接口继续返回 `{code,message,data}`。身份从实际登录会话确定，不接收页面提供的角色、主体或操作人。

| 服务 | 方法与路径 | 行为 |
|---|---|---|
| 底座 | `GET /api/v1/capabilities/{id}/producer-context` | 校验提供方身份、主体归属及可用能力；只返回生产准入元信息 |
| 底座 | `POST /api/v1/capabilities/{id}/producer-context` | 同上，并校验会话CSRF，用于逐次发送前核验 |
| 提供方 | `GET /api/v1/production/capabilities/{id}/records` | 直接从源库分页读取，返回 `{items,total}` |
| 提供方 | `POST /api/v1/production/capabilities/{id}/records/{recordId}/send` | 按源记录编号真实发送，返回 `{topic,partition,offset}` |

查询参数：`containerNo`、`trainNo`、`eventType` 为精确匹配；`from`、`until` 为带时区时间，按源库时间转换，范围为左闭右开；`limit` 为1–50，`offset` 为0–1000000。页面提供10/20/50条分页。

前端请求为 `/provider/api/v1/production/...`，由现有独立代理直接路由至18081。部署网关须保留同源Cookie和CSRF请求头；远程底座身份核验必须使用HTTPS。浏览器不持有提供方运维令牌。

普通需求方、管理员及其他提供方均不能通过新增入口读取或发送这份业务记录；无归属存量资源也不可被普通提供方接管。未登录401、越权或CSRF失败403、记录不存在404；来源服务故障503。新增生产摘要复用原监管通道，实际账号、能力、追踪号和结果记在提供方技术日志中，无业务消息正文或密码。底座现有摘要表没有新增操作人列，此次不迁移或伪造旧记录。

## 结果与限制

- **发送成功**表示Kafka确认写入，不等于需求方已经落库；页面必须在需求方接收任务中核对。
- **未发送**用于明确的参数、权限或不存在记录等拒绝。网络中断、超时、服务器故障或无效回执显示 **结果待确认**，可能已写入，不能自动重发。
- 取消只停止后续项，在途请求可能完成。离开页面停止后续发送；这不是后台生产任务。
- 批量不是跨Kafka事务，允许部分成功；重复调用可能再次发送同一业务记录。需求方按Kafka位置幂等落库，不按业务记录编号去重。
- 不改源表，不新增数据库表，不实现CDC、持续增量生产或通用业务映射。原运维生产接口、REST访问凭据、Kafka订阅与撤权机制保留。

## 验证命令

在工作区根目录运行 `./railway-data-foundation/scripts/test-local.ps1 -Offline`；需求方数据库专项使用 `./railway-data-foundation/scripts/test-demand-local.ps1 -Offline`。

真实前后端链路使用 `./railway-data-foundation/scripts/test-kafka-foundation-live.ps1 -Offline -NativeLocalBroker -NodeExecutable 'D:/Dev/node.js/node.exe'`。测试创建随机独立主题、主体及登记，读取原源记录但不增删改；测试结束撤销临时身份和清理登记，Windows测试主题保留，不在线删除。

前端在 `foundation-web` 执行 `npm test` 与 `npm run build`。测试证据放在忽略的 `.runtime` 中，功能说明和代码保留在正常项目目录。
