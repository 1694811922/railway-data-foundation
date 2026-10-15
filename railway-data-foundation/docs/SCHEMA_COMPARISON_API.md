# 资源结构只读比较接口

前缀为`/api/v1`，统一响应`{code,message,data}`。本接口比较Foundation中两次已持久化扫描，不连接业务库、不读取业务行。基准固定为资源的`scan_task_id`，不会选择最新扫描替代登记快照。

## 1. 候选扫描

`GET /resources/{id}/schema-comparison/scans?limit=20&offset=0`

`limit`默认20，允许1–200；`offset`默认0，允许0–1000000。未知资源或登记任务404；无效参数、登记任务来源/状态不一致400。

候选仅包含同一来源的其他成功任务，开始时间不早于基准。按`started_at DESC, task_id DESC`排列；时间相同仍可作为候选，不猜测实际先后关系。总数和当前页在同一只读事务中读取。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [{
      "task_id": "SCAN_实际目标任务编号",
      "source_id": "SRC_实际来源编号",
      "status": "SUCCESS",
      "started_at": "2026-10-07T14:00:00.000",
      "finished_at": "2026-10-07T14:00:01.000"
    }],
    "total": 1
  }
}
```

示例编号需替换。扫描时间沿用已有UTC存储/响应约定，前端转换为本地时间；`finished_at`不是排序条件。

## 2. 开始比较

`GET /resources/{id}/schema-comparison?scanTaskId=SCAN_实际编号`

任务编号为`SCAN_`加32位小写十六进制，参数必填。服务在同一个只读、可重复读事务中读取资源、两份任务及表/字段快照。并发删除目标任务时，要么读取到完整旧快照，要么返回404，不拼接删除前后的数据。

| 响应字段 | 作用 |
|---|---|
| `resource_id`、`resource_version` | 被比较资源及本次事务读取时的资源版本 |
| `source_id`、`schema_name`、`physical_table` | 资源登记来源与物理表 |
| `baseline_scan`、`target_scan` | 两份任务编号、来源、状态、开始及完成时间，结构同候选项 |
| `comparison_status` | 下表中的比较状态 |
| `summary` | 已确认范围时的`added`、`removed`、`modified`、`unchanged`字段数量，否则`null` |
| `table_changes` | 表注释变化数组，元素为`{property:"table_comment",before,after}` |
| `changes` | 字段差异数组，元素见下方 |

| 比较状态 | 含义与页面显示 |
|---|---|
| `UNCHANGED` | 字段与表注释均无变化，显示“未发现结构变化” |
| `CHANGED` | 字段或表注释改变；仅注释变化时字段差异数组可为空 |
| `TABLE_NOT_FOUND` | 同模式扫描中未找到目标表，显示“扫描未发现目标表”；统计未知，不能认定表被删除 |
| `SCOPE_UNCONFIRMED` | 空扫描缺少模式证据，显示“比较范围无法确认”；统计未知，不生成全部字段删除 |

字段差异元素示例：

```json
{
  "change_type": "MODIFIED",
  "field_name": "code",
  "before": {
    "field_name": "code",
    "data_type": "varchar",
    "column_type": "varchar(10)",
    "nullable": true,
    "primary_key": false,
    "default_value": null,
    "technical_comment": "代码",
    "ordinal_position": 2
  },
  "after": {
    "field_name": "code",
    "data_type": "varchar",
    "column_type": "varchar(40)",
    "nullable": true,
    "primary_key": false,
    "default_value": null,
    "technical_comment": "代码",
    "ordinal_position": 2
  },
  "changed_properties": ["column_type"]
}
```

`change_type`为`ADDED`（新增）、`REMOVED`（删除）、`MODIFIED`（修改）。新增的`before=null`，删除的`after=null`，这两类`changed_properties=[]`；页面可展开全部技术属性。修改只返回实际不同的属性名称。

## 3. 比较和完整性规则

- 精确按字段名匹配，区分大小写。改名表现为删除旧字段、新增新字段，不推测对应关系。
- 比较`data_type`、`column_type`、`primary_key`、`nullable`、`default_value`、`technical_comment`、`ordinal_position`。默认值`null`、空字符串和字符串`"NULL"`不同。
- 按新增、删除、修改分组；新增/修改按目标顺序，删除按基准顺序。增删/调整字段位置造成其他字段顺序变化时，这些字段也计入修改。
- 业务名称、业务描述、时间字段标记、敏感等级以及估算行数不参与技术比较。
- 检查两份任务的来源、成功状态、扫描模式、任务表数量、快照字段总数以及目标表/基准表的字段数量、名称唯一性和顺序连续性。
- 未知资源/任务404。相同任务、错配来源/模式、非成功任务、较早任务、快照残缺或无效参数400。错误沿用现有错误码，不转换成演示成功。
- 空扫描不能证明模式：现有任务表没有独立保存扫描模式，故返回未知范围，不新增字段或数据库表。

## 4. 前端及演示数据

资源详情首次打开“结构变化”加载每页20项候选，默认选择当前页最新任务，点击“开始比较”才查询差异。候选分页后默认选择新页首项；没有候选可进入扫描工作台，页面不会自动扫描。差异支持中文关键词/类型筛选、10/20/50项分页和属性展开。

失败清空旧结果并提供重试；切换任务、资源或退出页签使旧请求失效，不取消服务端已经开始的读取。结果显示比较时资源版本，可能与另一个页面已经修改后的版本不同。

显式`VITE_USE_MOCK=true`或选择已标记、归属于浏览器的演示资源时，使用`src/mock/schemaComparisonData.js`中的两份独立技术快照。演示标识保留，响应结构一致，不写回演示资源/字段/任务目录。缺少明确基准的旧演示资源，以及没有独立样例的演示资源，显示不可比较。真实请求失败不回退到这些样例。

本轮没有结构更新、资源版本增长、版本历史、能力同步或共享策略同步。比较结果只代表两次扫描记录之间的差异，不代表源数据库此刻的结构，也不代表接口已自动适配。
