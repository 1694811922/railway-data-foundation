# 2026-10-14 接收数据下载与导出交付

需求方已可从“数据接收 → 查看数据”下载单条完整消息、原始内容及按筛选条件导出JSONL。业务文件直接来自独立需求方服务，不经过底座后端；不修改源库、任务版本或消费检查点。

## 主要文件

| 文件 | 作用 |
|---|---|
| `demand-service/.../ReceivingDownloadService.java` | 主体范围、一致快照、单条原字节、JSONL流式准备、条数/大小/时间限制与临时文件清理 |
| `demand-service/.../ReceivingDownloadController.java` | 两个附件接口、准备完成后再核验登录、固定安全文件名及字节/数量响应头 |
| `demand-service/.../ReceiverExceptionHandler.java` | 未提交的传输错误清除附件头；已提交响应不追加JSON造成伪完整文件 |
| `foundation-web/src/utils/download.js` | 完整性校验、安全文件名、错误Blob解析、内存对象URL下载 |
| `foundation-web/src/utils/request.js`、`src/api/receiving.js` | 文件响应与普通统一响应并存；身份变化隔离、独立需求方通道 |
| `foundation-web/src/views/receiving/ReceivingView.vue` | 下载消息、下载内容/完整消息、导出筛选结果、取消及错误提示 |

完整操作与API见 [下载使用指南](RECEIVING_DOWNLOAD_GUIDE.md)。不新增迁移或后台导出任务，底座V17、源V2、需求方V1不变；不提交推送Git。

## 验证结果

- 完整后端：Foundation145项和Provider36项全部通过，含真实三角色与注册浏览器；需求方专项32项全部通过（完整reactor中的17项数据库测试另以独立测试库执行），去重共213项主模块测试通过。
- 新增格式/字节上限/错误流测试，以及原始JSON、二进制、空字节、空值、重复消息头、跨主体401/403、快照排除新到消息、10001条拒绝、并发429与释放、准备后登录失效等数据库和HTTP验证。
- 前端165项通过，最终`npm run build`通过；保留原包体积提示。Blob错误保持真实中文原因，残缺附件或长度不符不保存为成功。
- 最终原生Kafka与真实浏览器证据 `.runtime/kafka-chain-6101668986081992924/`：提供方单条/批量真实发送3条，需求方累计落库4条；浏览器原始JSON下载与显示内容完全一致，完整消息元信息一致，JSONL包含全部4条。413错误Blob显示正确，取消在途下载后不保存晚到文件。管理员、提供方与其他需求方不能导出，实际撤权后本主体历史导出仍可用。
- 原REST访问、提供方确认、Kafka实际撤权、后台持续接收、服务重启、数据库故障恢复和只读进度保持正常；原Source20条及完整摘要不变，测试账号、登记、消息和故障约束清理。Windows测试主题保留，下载测试文件只在私有忽略的证据目录留存。
- 第一轮真实链路 `.runtime/kafka-chain-194806882998113595/` 已通过，最终轮增加取消及撤权后历史下载断言。初次新增登录失效测试的模拟重设方式修正后32项全通过，失败轮次不计通过。

工作区 `.runtime/download-backend-test.log`、`download-demand-test.log`、`download-frontend-test.log`、`download-build.log`、`download-chain-final.log` 记录结果。证据目录中的 `received-content.json`、`received-record.json`、`received-export.jsonl` 为实际浏览器下载的独立测试文件，均来自模拟源业务记录。

## 边界

单次最多10000条或50MiB，准备最多15秒，一次只准备/传输一个文件。文件先在需求方私有临时目录完整准备再流式返回，响应前重验登录；完成或失败后清理，不保存业务正文日志。已有本机下载文件不能撤回。

只支持原始内容和完整消息JSON/JSONL，不提供CSV/Excel业务映射、ZIP、断点续传或后台大规模导出。下载不触发Kafka消费或生产，用户现有主题未被测试自动写入。
