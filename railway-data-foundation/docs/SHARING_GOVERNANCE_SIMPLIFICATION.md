# 共享治理入口精简（2026-10-17）

## 新入口

管理员菜单调整为：

| 菜单组 | 页面与内容 |
|---|---|
| 共享治理 → 共享授权 | 同一页面的“REST接口”和“Kafka订阅”两个页签 |
| 共享治理 → REST调用凭据 | 需求方主体级REST令牌的签发、撤销、到期与历史 |
| 系统管理 → 共享主体管理 | 提供方、需求方单位及启停、归属关联 |
| 系统管理 → 用户账号 | 登录人员、角色、注册审核、账号启停及密码重置 |

REST授权中点击“查看”，可查看访问策略和操作历史；管理员在此点击“调用测试”，仅测试当前授权。测试仍须人工提供合法需求方调用令牌，通过独立Provider通道访问；管理员登录本身不授予业务访问权限。关闭测试、切换页面或退出登录后清空临时令牌及结果，不写浏览器持久存储。

Kafka仍显示审批和实际权限执行两种状态，保留配置、领取、停止、重试、消费观察及一次性凭据规则。REST与Kafka仅归入同一工作台，未合并授权表、状态机或凭据。共享主体代表单位，用户账号代表人员，仍为独立模型。

提供方和需求方保留各自的申请、确认和历史入口；不会获得管理员统一授权工作台或调用测试入口。需求方凭据入口明确命名为“我的REST调用凭据”，Kafka一次性凭据仍从对应订阅领取。

## REST申请范围修正

前端REST表单只列当前资源下未退役的REST_API能力。后端在创建、编辑、提交、提供方确认、管理员确认/启用时同时检查能力类型，非REST返回400，不创建部分策略或关系；Kafka应走订阅授权，CDC和只读查询仍未实现共享执行。

旧非REST共享关系不迁移、不删除、不伪装成Kafka授权；保留读取、历史、退回/撤回等停止流程，禁止继续编辑或推进审批启用。已有正常REST和Kafka授权及历史均保留。

## 旧链接与权限

管理员旧链接自动归入`/sharing-authorizations`：

| 原链接 | 新位置 |
|---|---|
| `/relationships` | REST页签 |
| `/kafka-subscriptions` | Kafka页签 |
| `/policies?policyId=...` | REST对应授权详情中的访问策略 |
| `/access-check?relationshipId=...` | REST对应授权详情中的调用测试 |

原资源/能力/关键词等查询参数保留；切换页签时清除上一类型的临时入口参数，刷新可恢复当前页签。策略无具体编号时回到REST列表，从授权详情查看；目标策略不在已读取范围时明确提示检索，不能把范围外记录当作不存在。供需双方旧路径保持本角色含义，不重定向到管理员页面。

原后端只读策略、共享关系、凭据和订阅API保留，服务端角色/主体/编号检查不变。审批写入时禁止切页签；一次性Kafka凭据窗口未关闭时也禁切，避免遗失交付结果。无数据库变更，无新主体体系，无权限扩大。

## 主要文件

- `foundation-web/src/utils/workspace.js`、`router/index.js`：角色菜单、管理员旧链接兼容与路由保护。
- `views/sharing/AuthorizationView.vue`、`utils/authorizationNavigation.js`：统一授权页签、网址状态及写入期间切换保护。
- `RelationshipView.vue`、`AccessView.vue`：策略与调用测试归入授权详情，测试固定当前授权并清理秘密。
- `SubscriptionView.vue`、`CredentialView.vue`：复用原订阅操作，明确REST凭据名称。
- `foundation-service/.../sharing/RelationshipService.java`：服务端拒绝非REST申请继续走接口授权流程。
- `RelationshipIntegrationTest.java`、前端导航测试、`governance-live.mjs`：范围限制、历史兼容、菜单和真实调用回归。

## 验证与交付结果

- 完整reactor通过：Foundation166、Provider36；需求方普通单元用例21，另行隔离需求方全量51项通过，30个数据库用例未将跳过算通过。共253项不同主模块测试。
- `npm test`194项、`npm run build`通过；仍有既有入口包体积提示，不影响构建成功。显式演示浏览器验证无真实请求。
- 真实三角色浏览器通过原REST/Kafka申请、提供方确认及退回、管理员审批、账号和凭据操作，原主体隔离保持。
- 专项实际浏览器：管理员精简菜单与系统管理、REST/Kafka切换和刷新恢复、REST表单不显示Kafka能力、旧策略编号链接打开对应详情、旧验证链接打开当前授权的调用测试、合法令牌实际查询成功、错误业务令牌401不退出管理登录、关闭重开后令牌与结果清空。未拦截成功响应。
- 完整实际Kafka收发、独立需求方落库/下载、数据库故障恢复、进程重启、下架与换凭据恢复、撤权、监测和REST回归通过；项目证据`.runtime/kafka-chain-3904949116443304449`。前两次专项因测试脚本定位Element Plus下拉框失败，不计通过；修正选择器后完整重跑成功。
- 根目录验证日志：`.runtime/governance-backend-full.log`、`governance-demand-full.log`、`governance-front-final.log`、`governance-build-final.log`、`governance-demo.log`、`governance-kafka-live-verified.log`。
- 原源表20条及摘要不变，独立登记与身份已清理，Windows测试主题保留、不在线删除。未迁移或重写历史授权。
- 本机三个新运行包已启动，健康接口正常，私有配置文件完整哈希未变；仍V20/V4、来源V2。使用原账号重新登录。部署源码包同步更新，不代表云端已部署或验收。

未提交推送Git，不部署云端。
