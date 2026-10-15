# 接口能力操作流程与状态说明（2026-10-04）

本轮完善现有V0.2前端，不新增后端模块，不修改Java、Provider、数据库或迁移；V0.3继续暂停。上一轮资源详情工作台改动保留。

## 页面与交互

能力管理新增“草稿登记 → 连接验证 → 显式启用 → 按需停用”的中文说明。登记只保存访问方式，验证是最近一次探测结果，不自动开放业务查询。

| 功能 | 本轮行为 |
|---|---|
| 操作条件 | 按现有状态和适配器限制校验，禁用按钮的悬停文字说明原因；函数内部同样拦截不支持的操作 |
| REST | 当前指定无认证`GET /containers/events`可验证、启用及停用；启用前要求已验证或已停用且最近健康结果为UP，最终仍由后端重新探测 |
| Kafka | 无认证主题可验证元数据；真实运行时启用/停用仍未实现，不因UP而开放按钮 |
| CDC/只读查询 | 可登记元数据，当前没有真实验证及运行时操作；演示操作只影响浏览器 |
| 草稿编辑 | 校验记录仍在当前列表、版本/状态/资源/类型和数据模式一致；禁止改变所属资源与类型 |
| 表单 | 保存期间锁定输入、示例填入和关闭按钮；失败保留输入，重复提交被拦截 |
| 参数 | 名称120字、描述1000字、消息结构10000字；请求方式/协议按既有后端约束校验，提交白名单不含状态、版本、凭据或运行时标志 |
| 切换与退出 | 关闭旧弹窗；迟到保存/启停结果不提示成功、不刷新新资源条件、不导航回旧页面 |
| 详情刷新 | 刷新列表时更新已打开详情的状态和版本，清除旧调用说明后重新读取；能力已不在列表时关闭详情 |
| 调用说明 | 校验响应能力编号和真实/演示模式，错配或失败时清空命令并提示重试，不显示替代信息 |

列表筛选或分页本身不改变已发出的操作目标。切换网址中的资源入口或离开页面会废弃旧操作的页面响应，但不能撤销已发出的后端写入；返回原能力后应刷新确认。前端版本绑定避免使用已变化的页面上下文，后端仍负责并发冲突和最终状态。状态门禁不是身份认证或共享授权。

系统说明页改为中文架构说明、业务数据流、四种能力支持表和建议操作顺序，修正“能力启用尚未开放”的旧说法，注明当前没有需求方身份认证和V0.3共享授权。保留Provider已可发送指定事件到Kafka、常驻Broker需另行配置的说明。能力类型支持文字复用`capabilitySupport`，不把规划功能标为完成。

## 文件

- `foundation-web/src/utils/capabilityWorkflow.js`：现有操作条件、当前记录/编辑上下文、状态说明、提交白名单。
- `foundation-web/src/utils/capabilityForm.js`：长度、类型、方法和协议校验。
- `foundation-web/src/views/capability/CapabilityView.vue`：中文流程、输入锁定、旧操作隔离、详情快照刷新和调用说明归属。
- `foundation-web/src/views/AboutView.vue`：中文架构与实际支持范围。
- `foundation-web/scripts/capability-workflow.test.mjs`、`capability-workflow-live.mjs`、`package.json`：单元测试及浏览器专项。
- 既有`capability-form.test.mjs`补齐与真实表单一致的方法/协议输入；README、接口与进度记录。

## 实际验证

- `npm test`：**38项通过**，其中5项新增覆盖REST状态转换条件、适配器边界、编辑版本/模式与不可变归属、提交白名单和参数上限、中文状态语义。首次把既有白名单的12项误计为13项，修正测试预期后全部通过。
- `npm run build`：成功，保留已有主包超过500kB提示。
- `npm run test:capability-workflow-live`：先确认真实接口读取成功，再以HTTP夹具验证不支持操作不发请求、编辑归属、字段锁定、重复提交、失败保留输入且演示存储不变、迟到保存/启停与页面退出保护、详情状态及说明刷新、错配/失败调用说明隐藏、停用状态验证不自动启用、显式启用和系统说明。夹具写入全部拦截，不写数据库。首次因流程标题包含子文字导致精确定位失败，修正选择器后通过。
- `npm run test:capability-catalog-live`：451条能力/资源检索分页、已发布资源选择、旧调用说明隔离、提交保护及读取故障回归通过。
- `npm run test:smoke`：完整演示流程通过。
- `npm run test:capability-live`：真实注册/验证/启用200→停用403→再启用200、配置地址与请求头、复制调用命令、说明失败重试以及Source停用拒绝通过。

真实专项的1组临时Source/凭据/扫描/资源/能力按清单清理。最终原有数据源2条、临时来源0条、Source业务行20条、V6，底座无对应业务明细表。未变更Java，未重复Maven或重新打包。截图已检查：前端`.runtime/capability-workflow-detail.png`、`capability-workflow-list.png`、`about-workflow.png`；前两张是浏览器夹具，真实回归另有`rest-capability-enabled.png`、`rest-capability-disabled.png`与`rest-capability-detail.png`。

复现：启动Foundation后在`foundation-web`执行`npm run test:capability-workflow-live`，端口5184，结束自动关闭。真实专项使用原`test:capability-live`及`../scripts/cleanup-rest-ui-test.ps1`，仅面向本机实验库。
