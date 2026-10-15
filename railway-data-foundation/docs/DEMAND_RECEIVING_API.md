# 需求方接收接口

2026-10-14最新：需求方接收数据下载与导出已完成。单条原始JSON/文本/二进制、完整消息JSON、筛选JSONL及取消下载；仅本主体可用，文件准备后重验登录，固定快照最多10000条/50MiB，错误/截断不报成功，文件直接由需求方服务提供。213项主模块测试、前端165/build及真实浏览器文件核对/取消/撤权后历史下载/原授权回归通过；原源20条不变，临时登记清理、服务临时文件0，新需求方包及5173已运行。库仍V17/V2/独立V1，无Excel映射/后台导出/CDC，未提交推送。见[下载指南](RECEIVING_DOWNLOAD_GUIDE.md)、[交付](RECEIVING_DOWNLOAD_CHANGELOG.md)。以下为历史。

2026-10-14最新：需求方新增只读接收进度面板，按本主体授权核验实际Kafka保留范围、消费组位置和数据库检查点，明确无可用消息/未建立/待接收/追平/越界，未知不补0；不消费或改任务。205项主模块测试、前端161/build及真实三角色/注册/发送落库/只读进度/旧响应隔离/撤权回归通过，原源20条不变、临时登记清理。需求方新包与5173前端已启动。库仍V17/V2/独立V1，无CDC/后台生产/计费，未提交推送。见[进度使用指南](RECEIVING_PROGRESS_GUIDE.md)、[交付](RECEIVING_PROGRESS_CHANGELOG.md)。以下为历史。

统一响应 `{code,message,data}`；401未登录/技术凭据失效，403角色或主体越权/授权不可用，409版本冲突，400参数或凭据错误，503控制或接收服务暂不可用。业务结果禁止缓存。

## 底座控制接口（不接收业务内容）

| 接口 | 身份与用途 |
|---|---|
| POST `/api/v1/auth/receiver-session` | 原登录会话+CSRF，仅需求方；用于接收服务核验写操作真实身份 |
| POST `/api/v1/kafka-subscriptions/{id}/receiver-context` | 原登录会话+CSRF，仅对应需求方；返回有效授权和连接元信息，不返回秘密 |
| POST `/api/v1/kafka-subscriptions/{id}/receiver-access/check` | Bearer一次性领取的`rkc_`技术令牌，限对应授权；返回后台接收所需控制上下文 |

上下文 `data={grant,cluster_id,bootstrap_servers,security_protocol,sasl_mechanism}`。两种上下文接口不增加申请版本、不更改Broker权限。技术令牌继续只允许对应授权的核验和原消费摘要上报，不能用于登录、审批、领取凭据或REST访问。核验事务先锁同一授权，再复用原治理条件检查，与停止/撤权保持一致；不是消息转发接口。

## 接收服务（前端 `/demand` 直接路由到18082）

| 接口 | 功能 |
|---|---|
| GET `/api/v1/demand/health` | 进程健康信息，不含配置或业务数据 |
| GET `/api/v1/receiving-tasks` | 当前需求方主体任务，`keyword,grantId?,limit=20,offset=0`；`grantId`为精确授权筛选 |
| POST `/api/v1/receiving-tasks` | 验证真实Kafka凭据并创建STOPPED任务 |
| GET `/api/v1/receiving-tasks/{id}` | 本主体任务、已落库数量、待补报数量 |
| POST `/api/v1/receiving-tasks/{id}/start` | 核验授权后设置期望运行 |
| POST `/api/v1/receiving-tasks/{id}/stop` | 设置期望停止，保留业务数据及检查点 |
| POST `/api/v1/receiving-tasks/{id}/resume` | 处理阻塞原因后恢复，不重置检查点 |
| PUT `/api/v1/receiving-tasks/{id}/credentials` | 完全停止后更新同授权凭据，不能改变主题身份 |
| GET `/api/v1/receiving-tasks/{id}/messages` | 分页查询消息元信息，`topic,partition,from,until,limit,offset` |
| GET `/api/v1/receiving-tasks/{id}/messages/{message}` | 本任务消息完整内容，文本或Base64表示 |

创建请求：`{task_name,grant_id,password,consumption_token,start_position}`。`start_position=EARLIEST|LATEST`。服务端确定真实账号、主体、连接目标、主题、专属用户名及消费组；不得传入角色或主体。敏感请求DTO禁止toString输出。

操作请求：`{version}`；更新凭据请求：`{version,configuration:<创建请求结构>}`。更新不会修改任务名称、首次起点、已有消息或检查点。

分页返回`{items,total}`，limit范围1–50；列表和总数使用同一主体条件。普通任务响应不包含加密凭据或完整连接上下文。

`grantId`可选，完整格式为`KSG_`后跟32位小写十六进制；传入空值或通配符返回400。精确筛选与关键词取交集，同一只读可重复读事务返回列表和总数。其他主体/未知授权返回本主体范围内的空列表，不能据此判断其是否存在。历史任务不要求Kafka授权有效，但仍要求有效需求方登录；创建、启动、恢复继续核验实际授权。[订阅联动使用指南](RECEIVING_SUBSCRIPTION_LINK_GUIDE.md)。

消息内容中的键和值为`{encoding:UTF8|BASE64|NULL,content?,bytes}`；消息头保留顺序、重复键及NULL头值，值为Base64。业务查询不通过底座。历史数据按需求方主体保留；账号未登录或停用后不能访问页面查询，即使任务仍独立运行。

运行状态：STOPPED已停止、STOPPING正在释放连接、STARTING待启动、RUNNING接收中、RETRYING暂停并等待恢复、BLOCKED需人工处理。业务事务在期望运行时锁任务并保存；用户操作校验版本，运行状态更新不擅自替用户增加操作版本。
