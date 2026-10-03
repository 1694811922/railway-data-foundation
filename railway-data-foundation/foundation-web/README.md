# 统一数据底座汇报演示前端

Vue 3、Element Plus、Axios、Vue Router。项目从外层 `railway-data-foundation` 启动后端，然后在本目录执行：

```powershell
npm install
npm run dev
```

访问 `http://127.0.0.1:5173`。首次点击“进入平台”，这是前端演示入口，没有后端身份认证。需要无后端纯演示时可停止后端，页面会在接口缺失或网络不可用时显示标记过的 mock 数据。

## 页面与数据边界

首页 Dashboard、数据源管理、元数据扫描、资源目录、Resource 详情（基本信息、字段元数据、关联能力）、Capability 管理、系统说明。

真实请求集中在`src/api/`，响应处理位于`src/utils/request.js`。数据源、扫描、资源、字段和能力登记优先调用已有后端。演示数据集中在`src/mock/demoData.js`并标记来源，模拟编辑只影响当前浏览器。真实数据源、资源、字段和接口能力的写入、发布、验证、启停失败不会模拟成功。指定真实资源详情与扫描任务读取失败显示错误，不使用演示结果替代。现有REST查询能力可真实验证、启用和停用，详情显示运行状态与调用请求头；Kafka可验证Topic元数据，CDC及Kafka运行时启停未实现。Provider业务查询和Kafka发布独立于管理页。

`VITE_API_BASE_URL=/api/v1`、`VITE_USE_MOCK=false` 是默认配置。Vite 将 `/api` 代理到 `http://127.0.0.1:18080`。如需强制纯前端演示，把 `VITE_USE_MOCK=true` 写入本目录 `.env.local`（被 Git 忽略）并重启开发服务器。按浏览器存储清理可重置演示编辑。

真实 Source API 接受名称、类型、地址、端口、数据库名称、用户名、密码、加密连接方式与备注。新增弹窗分为“基本信息”和“连接信息”，提供“保存”和“保存并测试连接”；编辑密码留空保留原凭据。密码仅作为写入参数发给后端加密保存，不回显、不保存到 localStorage（包括演示模式）。表格支持测试、开始扫描、编辑、启用和停用；停用后禁止新的测试和扫描。旧环境引用记录仍可运行，编辑保存时提示重新输入密码并转换为动态配置。

```powershell
npm run build
npm test
npm run test:smoke
```

接入管理专项真实浏览器测试为 `npm run test:source-live`。先启动更新后的后端，并设置 TEST_SOURCE_DB_URL、TEST_SECOND_SOURCE_DB_URL、TEST_PROVIDER_SOURCE_DB_USER 和 TEST_PROVIDER_SOURCE_DB_PASSWORD，使用专用 port_source_test 与 another_source_test（后端测试脚本会引导创建）。测试在本机 Foundation 开发库登记带 UI_DYNAMIC_ 前缀的临时 Source/扫描元数据，不创建 Resource；清理运行 `../scripts/cleanup-source-ui-test.ps1`，仅删除清单中经校验的测试元数据。不得指向生产环境。输出 `.runtime/source-dialog.png`、source-list.png、source-fields.png 和 source-scans.png，测试服务器为 5175，结束自动关闭。覆盖保存并测试、空密码编辑、启停、真实两库扫描、无密码存储和网络失败不模拟成功。

浏览器测试默认强制使用独立演示数据，保证页面流程不依赖开发库已有记录；真实 API 由后端 MySQL 集成测试验证。测试使用本机 Edge 无界面模式；在其他机器上可设置 `FOUNDATION_BROWSER` 指向 Chrome/Edge 可执行文件。测试覆盖登录、首页、数据源弹窗、扫描字段、资源详情及能力弹窗，并输出 `.runtime/dashboard.png`。测试服务器使用 5174 端口，关闭后自动退出。

REST启停专项真实浏览器测试（本机开发库与port_source_db模拟库）如下；先启动已更新的Foundation和Provider。测试用UI_REST_前缀创建临时Source、资源、能力，清理脚本会校验编号、凭据引用和名称，保留业务表与原有元数据。

```powershell
. ../scripts/load-env.ps1 -Path ../.env.local
npm run test:capability-live
powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-rest-ui-test.ps1
```

覆盖动态登记→扫描→发布资源→页面注册/验证/启用REST→Provider返回200→停用403→重新启用200；Source停用也被拒绝，网络失败不模拟成功。测试服务器5176结束关闭，截图为`.runtime/rest-capability-enabled.png`、`rest-capability-disabled.png`与`rest-capability-detail.png`。Provider查询需携带详情中的X-Capability-Id请求头；V0.3身份及共享授权仍未实现。

汇报建议按：首页→数据源→连接测试→扫描与字段→资源目录→详情→登记能力→验证REST→启用查询→停用后拒绝。有Kafka时再验证Topic。预设样例仅在演示模式展示；真实模式需从已发布资源登记。业务行直接从18081的Provider返回；Foundation只传能力控制元数据。CDC与Kafka启停仍待后续。

## 能力登记与调用指引

登记弹窗对资源、路径、主题、认证和查询模板提供中文说明；“填入本机示例配置”填入常用技术字段，并保留已填名称。认证选项按类型过滤，CDC来源表由资源自动带出且不可手工修改；模板填写编号而非SQL。字段校验和命令生成位于`src/utils/capabilityForm.js`，`npm test`验证路径边界、各类型字段与命令转义。

现有REST查询能力详情读取真实`GET /capabilities/{id}/invocation`，显示后端配置的提供服务地址、完整调用地址和必带请求头。点击“复制调用命令”，到VS Code的PowerShell终端粘贴执行；浏览器禁止剪贴板时可选中命令手工复制。刷新调用信息读取当前控制状态，不查询业务数据。未启用、已停用或来源停用时不能实际查询。

真实调用说明加载失败只提示错误与重试；演示说明集中在`src/mock/demoData.js`且有演示提示，不能用于真实调用。`test:capability-live`额外检查实际地址、复制内容及加载失败/重试，不在管理页展示业务明细。

## 资源登记与字段维护

资源目录的创建入口进入元数据扫描页。来源系统、数据库模式和物理表由扫描结果带出且只读；编辑资源只维护业务信息。详情页支持直接发布草稿，发布后才可登记能力；字段编辑后重新读取字段和资源版本，业务描述也在表中显示。

保存、发布或字段编辑失败时保留输入并提示刷新检查真实状态，不自动重发。即使后端已提交而响应丢失，页面也不会伪造成功或写入演示存储。真实字段或关联能力加载失败按区域显示错误，支持刷新重试；演示字段按资源编号隔离，不再修改全局扫描字段。

`npm test`覆盖资源校验、演示字段隔离、演示编号归属及已有能力表单校验。专项真实浏览器验收：

```powershell
. ../scripts/load-env.ps1 -Path ../.env.local
npm run test:resource-live
powershell -NoProfile -ExecutionPolicy Bypass -File ../scripts/cleanup-rest-ui-test.ps1 -ManifestName resource-live-fixtures.json
```

先启动本机后端；只使用`port_source_db`模拟来源和带UI_REST_前缀的临时元数据，不应指向生产库。测试端口5177结束自动关闭。覆盖来源只读、晚到字段响应被忽略、真实保存/字段/发布失败无mock、响应丢失不自动重发、持久化与版本变化、发布后登记能力。清理脚本只接受两种固定清单名并校验编号、名称和凭据引用，保留原记录及业务表。截图在`.runtime/resource-live-detail.png`与`resource-live-fields.png`。
