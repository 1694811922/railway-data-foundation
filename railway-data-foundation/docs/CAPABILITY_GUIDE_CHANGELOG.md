# 能力登记与调用指引交付记录

日期：2026-10-03。本轮完善V0.2接口能力的登记和调用说明，帮助区分数据资源、接口路径、提供服务地址和数据库连接。V0.3保持暂停，数据库仍为V6。

## 页面行为

登记弹窗增加中文用途说明和“填入本机示例配置”。请求方式附中文解释；REST/Kafka按类型筛选认证选项，协议使用受限选项，CDC来源表取关联资源且只读，查询模板编号拒绝SQL。保存前检查路径和各类型技术字段，后端保留最终校验。示例填入仅辅助填写已有接口配置，不创建接口、消息主题或变化流组件。

现有REST查询能力详情展示：提供服务根地址、完整调用地址、`X-Capability-Id`请求头、当前可用标志及PowerShell命令。复制后可在VS Code终端执行查询，管理页面不展示业务行。浏览器剪贴板不可用时选中命令，允许手工复制；调用信息请求失败显示错误与重试，不模拟成功。

## API与配置

新增`GET /api/v1/capabilities/{id}/invocation`，返回`capability_id/http_method/provider_base_url/endpoint/request_url/header_name/header_value/enabled/reason`。仅支持既有无认证`GET /containers/events`，其他类型返回明确错误，未知能力404。草稿及停用仍可查看说明，但可用标志为false。

Provider地址来自现有服务端`PROVIDER_REST_BASE_URL`，与REST验证共用校验。仅HTTP/HTTPS根地址，不能含用户信息、子路径、查询参数或片段；协议须匹配。读取说明不发送HEAD、不查询Source、不改变能力版本，不返回密码、用户名、凭据或业务行。

演示响应与真实API字段一致，单独封装于`src/mock/demoData.js`；演示编号和地址明确只供展示，不能用于调用真实服务。未新增配置项、依赖或数据库迁移。

## 文件清单

| 范围 | 文件 |
|---|---|
| Foundation服务 | `capability/CapabilityController.java`、`CapabilityService.java`、`RestCapabilityVerifier.java` |
| 后端测试 | 新建`ProviderAddressTest.java`；扩展`ScanResourceIntegrationTest.java` |
| 前端页面 | `src/views/capability/CapabilityView.vue` |
| 前端封装 | 新建`src/utils/capabilityForm.js`；修改`src/api/capability.js`、`src/utils/dataHub.js`、`src/mock/demoData.js` |
| 前端测试 | 新建`scripts/capability-form.test.mjs`；扩展`scripts/smoke.mjs`和`capability-live.mjs`；`package.json`新增`npm test` |
| 文档 | 本记录、能力API、Provider REST、开发状态、路线图、测试记录、项目及前端README |

Java文件位于Foundation服务的`src/main/java/cn/railway/foundation/`，测试位于对应`src/test/java/`。本地`.runtime/`中的执行辅助脚本和日志只用于本轮验证，不参与应用运行。

## 验证与限制

- 完整后端回归：Foundation38项、Provider7项，合计45项通过，0失败、0错误、0跳过。
- 前端：`npm test`3项、`npm run build`、`test:smoke`和`test:capability-live`全部通过。
- 实际浏览器检查提供服务地址和复制命令、读取失败/重试、启用200→停用403→恢复200、Source停用拒绝。临时UI_REST_元数据已清理，原记录与业务行保留。
- Foundation已打包重启，两服务health正常；Provider业务代码未修改。
- 调用说明显示控制状态和最近验证结果，不保证即时健康；真正查询时Provider仍逐次检查状态。配置地址需能被调用方访问。
- Kafka运行时启停、CDC、只读模板执行与V0.3治理仍未实现。认证方式登记不代表这些认证运行时已经实现。
