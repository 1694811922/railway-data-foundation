# 共享主体接口（V0.3第一阶段）

由foundation-service提供，响应仍为`{code,message,data}`。这是业务参与方登记，不是运行时认证或共享授权。

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/api/v1/subjects?limit=50&offset=0` | 列表，limit为1–200，offset为0–1,000,000，按更新时间/编号倒序 |
| GET | `/api/v1/subjects/{id}` | 主体详情，不存在返回404 |
| POST | `/api/v1/subjects` | 新增主体，默认启用，版本1 |
| PUT | `/api/v1/subjects/{id}` | 更新名称、责任单位、说明，必须提供当前版本 |
| POST | `/api/v1/subjects/{id}/status` | 启用/停用登记，必须提供当前版本 |
| DELETE | `/api/v1/subjects/{id}` | 删除已停用且未被共享关系或调用凭据引用的主体登记 |

新增示例：

```json
{
  "subject_code": "GUANGZHOU_PORT",
  "subject_name": "广州港数据提供方（示例）",
  "subject_type": "PROVIDER",
  "owner_department": "广州港数据管理部",
  "description": "模拟业务参与方，不代表真实源系统授权"
}
```

主体角色为`PROVIDER`提供方或`DEMAND`需求方。业务编码全局唯一，3–64位大写字母、数字或下划线，以字母开头。名称和责任单位必填，最多120字；说明可空，最多1000字。

编辑只接受`subject_name`、`owner_department`、`description`、`version`。编码、编号、角色不能修改；客户端不能设置登记时间或状态。响应含`subject_id`、以上登记属性、`status`、`version`、`created_at`、`updated_at`，时间为UTC。编号为`SUB_`加32位随机标识。

状态请求：

```json
{"status":"DISABLED","version":1}
```

状态为`ENABLED`或`DISABLED`；有效变更版本加1，相同状态且版本当前时不增加版本。编辑和启停的过期版本返回HTTP400、code10002并提示刷新，不能覆盖最新数据。非法/未知字段返回HTTP400、code10001。编码重复返回HTTP400、code10002。删除须先停用；被共享关系引用时拒绝，包含已撤回关系的历史引用。

## 存储与页面

追加Flyway V7创建`foundation_db.sharing_subject`，字段有中文注释。不写入业务数据、连接密码或身份令牌。主体登记不自动关联来源、资源或能力；管理员可在[V8共享关系](SHARING_API.md)中显式绑定双方主体、资源与能力。

前端“共享治理 → 共享主体管理”（`/subjects`）支持检索、角色/状态筛选、10/20/50项显示分页、新增/编辑/启停/删除。删除前必须先停用。列表分批读取最多5,000条，受限时明确提示。默认真实API，真实读写失败不回退mock；旧响应在退出页面后忽略，提交时锁定表单。

仅`VITE_USE_MOCK=true`时使用`src/mock/subjectData.js`的两条示例，持久保存于浏览器localStorage键`foundation_subject_demo_v1`。真实数据库不自动植入广州港或铁路单位的主体登记记录。

登记启用只表示该主体目录记录可用，不等于授予资源权限。V0.3 REST访问由调用凭据识别需求方，并联合检查主体、关系及策略；停用主体使其参与的新请求被拒绝，原凭据历史仍保留。调用凭据另有签发、撤销流程，见[访问接口](ACCESS_API.md)。
