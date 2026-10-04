# 元数据扫描与资源登记接口（V0.1）

所有接口以 `/api/v1` 为前缀，使用 `{code,message,data}` 响应。需先按 [数据源接口](SOURCE_API.md) 动态登记 MySQL 连接元信息和加密凭据；旧环境变量引用保持兼容。

| 方法与路径 | 作用 |
|---|---|
| `POST /sources/{sourceId}/scan` | 同步扫描该数据源，返回任务状态 `SUCCESS` 或 `FAILED` |
| `GET /scan-tasks?limit=50&offset=0` | 最近任务列表 |
| `GET /scan-tasks/{taskId}` | 任务详情及安全失败原因 |
| `GET /scan-tasks/{taskId}/tables` | 候选表、注释、字段数及估算行数 |
| `GET /scan-tasks/{taskId}/tables/{tableName}/fields` | 字段技术元数据 |
| `POST /resources` | 从成功扫描的表创建草稿资源 |
| `GET /resources?limit=50&offset=0` | 资源目录 |
| `GET /resources/{id}` | 资源详情 |
| `PUT /resources/{id}` | 更新人工填写的业务信息，不允许修改来源表 |
| `POST /resources/{id}/publish` | 发布草稿资源，返回 Resource ID 和新版本 |
| `GET /resources/{id}/fields` | 查看资源字段 |
| `PUT /resources/{id}/fields/{fieldName}` | 人工维护字段业务属性 |

扫描只读取 Source 的 `information_schema`，Foundation 库持久化扫描任务、表名、字段名、类型、注释等结构信息，不读取或复制业务明细行。任务为同步执行，连接/权限问题会返回 `FAILED` 状态及不含地址、账号或密码的安全原因。估算行数来自 MySQL 元数据，不保证精确。重复扫描产生新的任务历史；同一数据源的同一表只能登记一次 Resource。

任务响应包含 `table_count`、`field_count`、开始/完成时间和安全的 `failure_reason`（对应错误说明）。停用数据源的扫描请求返回 HTTP 400，不创建新任务。当前同步实现直接经历 RUNNING → SUCCESS/FAILED，PENDING 作为后续异步任务的预留状态，不伪造后台排队。中文页面展示字段数量、估算记录数、扫描状态、失败原因和字段顺序，任务间切换会清空旧结果，避免混淆不同来源。

资源创建示例（`source_id` 和 `schema_name` 使用实际扫描结果；`source_name` 如由前端提供不作为权威值）：

```json
{
  "resource_name": "广州港集装箱作业事件数据集",
  "source_id": "SRC_实际ID",
  "schema_name": "port_source_db",
  "physical_table": "container_event",
  "resource_type": "TABLE",
  "business_description": "人工确认的作业事件业务含义",
  "owner_department": "数据管理部",
  "security_level": "内部",
  "update_mode": "实时"
}
```

字段编辑示例：

```json
{"business_name":"事件发生时间","business_description":"作业发生时刻","time_field":true,"sensitivity_level":"内部"}
```

资源名称、业务说明、责任单位、安全等级和更新方式必须人工填写；扫描不会自动推断治理属性。Resource 发布只改变目录状态，不创建数据复制任务或 Source → Demand 数据通道。已发布资源可按 [Capability 接口](CAPABILITY_API.md) 关联能力；V0.2已支持现有REST查询的真实验证与启停、Kafka生产和Topic验证，其他运行时能力尚未实现。

## 前端登记、维护与发布

扫描工作台通过现有任务和数据源接口分页读取，每类最多5,000项，范围不完整有提示；任务支持关键词/来源/状态组合筛选和10/20/50项显示分页，表和字段支持结构检索。刷新会重新加载所选任务状态和结果；`?task=...`支持同页面更新，列表范围外的指定任务通过已有详情接口单独读取。表/字段故障可分别重试，旧响应不能覆盖新结果。默认真实扫描读取失败不切演示，扫描结果未确认时提示刷新检查且不自动重发；只有已启用来源才能发起扫描。同步后端流程保持不变，见[扫描工作台交付记录](SCAN_WORKBENCH_CHANGELOG.md)。

资源目录通过已有`limit/offset`接口分页读取，不增加后端搜索接口。关键词检索名称/编号/物理表/来源/责任单位，可组合来源、状态和安全等级，显示分页为10/20/50条。条件写入网址并在刷新和详情返回时恢复；每次最多读取5,000项，范围不完整有提示，检索只涵盖已读取元数据。分页中途失败或曾展示真实目录后的刷新失败显示错误，不切换成演示目录。见[目录交付记录](RESOURCE_CATALOG_CHANGELOG.md)。

资源目录的“从扫描结果创建资源”进入`/scans`。选择成功任务中的表后，来源系统、数据库模式和物理表自动带出且只读；编辑资源也不能改变这些来源字段。业务名称、说明、责任单位、安全等级和更新方式由人工填写，客户端校验位于`src/utils/resourceForm.js`，服务端校验仍由现有ResourceRequest负责。

详情页可维护字段业务属性，并直接发布草稿。字段名、类型、主键和技术注释仍来自扫描，编辑时只提交业务名称、描述、时间标记和敏感等级。保存后重新读取真实字段与资源版本；已发布才开放登记能力入口。发布返回的资源编号显示在详情和成功提示中，字段表新增业务描述展示。

真实资源保存、发布和字段编辑请求失败不会转换成模拟成功；弹窗保留输入，超时提示结果尚未确认，应刷新后检查实际状态。网络响应丢失时可能已在服务端提交，前端不会自动重发。资源详情的字段/能力读取失败按区域显示错误，不拿演示字段替代；404显示资源不存在。刷新不会更改后端数据。

目录列表在后端不可用时仍可显示有标记的演示数据；只有浏览器已有的演示来源/资源或显式全局演示模式可执行模拟操作。演示字段集中维护在`src/mock/demoData.js`，按资源编号分别存储，编辑一项资源不会改变其他资源或扫描字段。

扫描表与字段查询也按实际任务读取。切换任务会清空旧字段并关闭抽屉；此前请求稍后返回时被忽略，避免从旧结果创建错误来源的资源。创建入口绑定当前任务，抽屉入口绑定展示字段所属任务。
