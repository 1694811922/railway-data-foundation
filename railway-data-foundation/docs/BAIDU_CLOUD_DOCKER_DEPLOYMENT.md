# 百度云 Ubuntu 24.04 单机 Docker 部署步骤

适用设备：用户截图中的百度云北京地域 BCC，4核、16 GiB、Ubuntu 24.04 LTS amd64。目标是在新服务器创建独立环境，完成统一前端及三个角色的真实 REST/Kafka 流程。本方案不自动迁移 Windows 数据、账号、加密凭据、Kafka 集群或消费位置。

**验证状态：部署文件、初始化保护及静态检查在本机验证；本机没有 Docker Engine，尚未完成容器构建、Linux 运行或百度云实际部署验收。下列步骤包含服务器端必须执行的检查，不能将文件已生成写成系统已上线。**

## 1. 部署范围与网络

| 组件 | 地址 | 保存位置 |
|---|---|---|
| Nginx 前端入口 | 初期`127.0.0.1:8080`，正式HTTPS为443 | 镜像内前端静态文件 |
| 底座 | `127.0.0.1:18080` | `foundation_db`，仅元数据/治理/技术摘要 |
| 提供方 | `127.0.0.1:18081` | 只读访问业务源；技术补报目录独立挂载 |
| 需求方接收服务 | `127.0.0.1:18082` | `demand_receiver_db`，含业务消息与检查点 |
| MySQL | `127.0.0.1:13306` | 同实例三个独立数据库、不同账号 |
| Kafka 3.9.1 | `127.0.0.1:19092`，控制端19093 | 独立消息数据目录，单节点三分区单副本 |

使用 Linux Docker Engine 的 `network_mode: host`。容器共享主机网络，后端与Kafka仍只绑定回环；不配置`ports`映射，不放宽已有远程HTTPS/SASL_SSL检查。它不提供容器间网络隔离，适用于当前受同一运维方管理的单机实验，不是多组织物理隔离或高可用架构。[Docker host网络说明](https://docs.docker.com/engine/network/drivers/host/)

业务通道：提供方→Kafka→需求方数据库。Nginx分别直连`/api/`、`/provider/`、`/demand/`；底座不代理业务明细。底座容器只挂载公开Kafka集群编号，不挂载Kafka消息目录。

## 2. 百度云控制台准备

1. 确认实例处于运行中。截图显示公网IP未分配，先绑定同一北京地域的IPv4弹性公网IP；如已有可用EIP无需再次申请。费用以控制台为准。
2. 在实例安全组中允许TCP22，仅限你的管理公网IP（`你的IP/32`）。初期验收不用开放8080、5173、13306、18080–18082、19092、19093。
3. 在控制台SmartTerm登录，或在Windows PowerShell使用`ssh 登录用户名@服务器公网IP`。账号以控制台设置为准，不假定必须root。
4. 执行`cat /etc/os-release`、`uname -m`、`free -h`、`df -h`、`sudo ss -lntp`，确认Ubuntu/amd64、磁盘及端口。首次构建至少留15GiB可用空间，数据增长还需额外空间。

[绑定EIP官方步骤](https://cloud.baidu.com/doc/BCC/s/ulj2mi9it)、[安全组说明](https://cloud.baidu.com/doc/BCC/s/Xlkqskwun)。不要重装已有服务器或删除已有数据库来解决端口冲突。

## 3. 安装 Docker Engine 与 Compose

以下命令**在云服务器Linux终端执行**，不是本机PowerShell。先检查已有安装：

```bash
docker --version
docker compose version
```

若已有可用的官方Docker Engine和Compose插件，跳过安装。已有宝塔、其他容器或旧Docker时，先核对兼容性，不直接卸载。

全新Ubuntu24.04安装，依据[Docker官方APT仓库方式](https://docs.docker.com/engine/install/ubuntu/)：

```bash
sudo apt update
sudo apt install -y ca-certificates curl python3 unzip openssl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
sudo docker run --rm hello-world
```

若下载超时，先排查DNS、出站规则、官方仓库与Docker Hub连通性；不关闭TLS校验，不盲目使用未经确认的镜像站。Docker权限等价于高权限运维，不把普通平台用户加入Docker用户组。

## 4. 上传本轮部署包

本轮新增部署文件尚未自动提交/推送Git。直接克隆旧GitHub提交`8c16020`没有这些文件，优先使用随本指南交付的`railway-cloud-deploy-20261017.zip`。

在云服务器创建独立目录：

```bash
sudo install -d -m 0755 /opt/railway-release-20261017
```

**回到你电脑的PowerShell**，把文件路径、用户名和服务器IP换成实际值：

```powershell
scp "C:\Users\凯\Documents\ChatGPT\数据底座\交付文件\railway-cloud-deploy-20261017.zip" 登录用户名@服务器公网IP:~/railway-cloud-deploy-20261017.zip
```

再在**云服务器终端**解压：

```bash
sudo unzip -n ~/railway-cloud-deploy-20261017.zip -d /opt/railway-release-20261017
cd /opt/railway-release-20261017/railway-data-foundation
```

不要解压覆盖已有运行目录。交付包只包含源码、测试和部署文件，不包含Windows `.env.local`、数据库、运行日志、依赖或任何真实凭据。ZIP的SHA-256可与交付目录中的校验文件比对。

## 5. 首次检查与生成私有配置

以下全部在项目目录执行：

```bash
sudo python3 deploy/linux/manage.py preflight
sudo python3 deploy/linux/manage.py init
sudo python3 deploy/linux/manage.py verify
sudo docker compose -f deploy/linux/compose.yml config --quiet
```

`preflight`检查Docker、本机端口和磁盘，不自动停止其他服务。仅支持本机rootful Linux Docker、默认Unix socket，不支持rootless、用户命名空间重映射或远程Docker context。

`init`创建`deploy/linux/.private/`：独立随机密码、两个不同的加密主密钥、MySQL初始化文件、Kafka认证配置、三个后端环境文件以及仅回环访问的Nginx配置。运行过程不打印秘密，没有通用密码。重复执行只核对原配置，不更换密码/密钥；若私有文件丢失或不一致，会拒绝重建。

**整个`.private`目录必须保留并安全备份。**不能把它提交Git、放进更新包、通过聊天发送，不能删除后重新初始化已有数据。`docker compose config`若不带`--quiet`可能输出展开后的密码，不要截图发布。

## 6. 构建并启动完整系统

```bash
sudo docker compose -f deploy/linux/compose.yml build
sudo docker compose -f deploy/linux/compose.yml up -d --wait --wait-timeout 300
sudo docker compose -f deploy/linux/compose.yml ps -a
```

首次构建需要拉取镜像与Maven/npm依赖。镜像基础版本在Dockerfile中有主版本约束，但未锁定摘要；完成服务器验收后应记录镜像ID/摘要，后续升级另行测试，不保证未来拉取内容完全相同。

预期：`mysql`、`kafka`、`foundation`、`provider`、`demand`、`web`为运行且健康，`kafka-init`为`Exited (0)`。`kafka-init`是成功后退出的一次性配置任务，不是常驻服务。

数据库首次启动建立三个库及分离账号，只在新的`port_source_db`执行现有源SQL，写入20条明确的模拟业务记录。底座及需求方各自使用Flyway初始化。源库没有独立Flyway容器，不给正式外部源库执行这些演示SQL。普通源读取账号只有查询权限。

Kafka首次格式化新目录、建立固定探测/生产身份及精确ACL。正常重启只验证已有身份；原元数据或私有配置缺失时拒绝重新格式化/重发身份。每条订阅身份仍由真实审批流程单独创建。

检查接口：

```bash
curl -fsS http://127.0.0.1:18080/api/v1/health
curl -fsS http://127.0.0.1:18081/api/v1/provider/health
curl -fsS http://127.0.0.1:18082/api/v1/demand/health
curl -fsS http://127.0.0.1:8080/gateway-health
```

前三项应返回`code=0`，网关返回`UP`。无登录读取业务接口返回401属于正确行为，不能为了“消除401”关闭认证。

## 7. 初始化管理员并从电脑访问

在云服务器执行：

```bash
sudo python3 deploy/linux/manage.py admin
```

交互输入自己的管理员账号及密码。密码至少12字符、UTF-8最多72字节，不写入命令参数、仓库或初始化文件。已有管理员不会被覆盖。网页本地首任管理员注册开关在云端始终关闭。

**在电脑PowerShell打开SSH隧道，保持窗口运行：**

```powershell
ssh -N -L 18088:127.0.0.1:8080 登录用户名@服务器公网IP
```

在自己电脑浏览器打开`http://127.0.0.1:18088`，用刚创建的账号登录。访问是电脑本机HTTP经加密SSH通道进入服务器，初期不公开HTTP登录页，也无需给安全组开放8080或5173。隧道端口被占用时换其他本地端口。

## 8. 建立三角色并验证业务流程

1. 管理员创建提供方、需求方共享主体，再在“用户账号”创建关联账号；也可由双方注册申请，管理员审核绑定。各账号自行设定不同密码。
2. 提供方进入“我的数据源”登记：类型MySQL，Host=`127.0.0.1`，Port=`13306`，Database=`port_source_db`，Username=`source_reader`。这里的127.0.0.1指云端服务所在主机。
3. 由服务器运维人员在私有终端查看`sudo cat deploy/linux/.private/config/source-client.cnf`，把其中源读取密码填入表单，不把输出发到聊天或截图。测试连接、扫描、选择`container_event`创建并发布资源。
4. 登记Kafka能力，Topic=`port.container.event.v1`，Protocol=`KAFKA`，Auth Type=`SASL`，验证并启用。REST原适配器为GET `/containers/events`，Protocol=`HTTP`，Auth Type按当前原适配器为`NONE`；实际共享调用仍须平台访问凭据及有效共享关系，不能匿名查询。
5. 需求方申请Kafka整主题订阅；提供方确认；管理员最终审批并配置Broker权限；需求方领取一次性密码与技术令牌，配置并启动接收任务。
6. 提供方“数据发送”选择记录，先单条再批量发送；需求方查看落库消息、接收进度，并下载JSON/JSONL核对内容。
7. 管理员撤权，验证任务阻塞且不再接收；本主体已落库历史数据仍可查看。再验证REST字段/时间范围及越权拒绝。

这套部署初始化的是新集群，不继承Windows账号、授权、凭据、旧任务或消息。需要保留旧环境时，应专项迁移数据库与原密钥、同一Kafka集群身份、ACL、主题ID和消费位置；不能只导几张表或把旧接收任务指向新集群。

## 9. 正式HTTPS访问

准备域名，将A记录指向EIP，取得该域名的有效证书完整链和对应私钥。北京地域的网站发布按[百度云备案中心](https://cloud.baidu.com/beian/index.html)的适用要求办理。域名/证书未准备好时继续使用SSH隧道验收。

把证书通过SCP放到服务器私有目录，随后执行（替换域名和文件路径）：

```bash
sudo python3 deploy/linux/manage.py https --domain data.example.com --cert /安全目录/fullchain.pem --key /安全目录/privkey.pem
sudo docker compose -f deploy/linux/compose.yml run --rm --no-deps web -t
sudo docker compose -f deploy/linux/compose.yml up -d --force-recreate --wait --wait-timeout 240 foundation web
```

HTTPS命令检查有效期、域名和公私钥匹配，复制到私有目录，开启安全会话Cookie，配置443及80跳转。证书链是否受浏览器信任仍需实际验证；自签名证书不能当作正式验收成功。切换会重建底座登录服务，重新登录即可，账号密码不变。

安全组此时放行TCP80、443；保持数据库、Kafka、后端端口不对公网开放。浏览器访问`https://实际域名`。不再使用公网IP:5173。使用`curl -I https://实际域名`检查证书，不使用`-k`绕过验证。证书续期后重新执行HTTPS配置并重建web，核验新证书。

正式HTTPS启用后，8080只提供网关健康检查，不再提供HTTP登录页。REST外部客户端地址使用`https://实际域名/provider/containers/events`，保留既有认证和治理请求头；能力详情中当前显示的回环Provider地址用于服务器内部探测，不是外部电脑可直连地址。

## 10. 日常运维、日志和备份

```bash
# 状态 / 最近日志（不要把含业务操作上下文的完整日志公开）
sudo docker compose -f deploy/linux/compose.yml ps -a
sudo docker compose -f deploy/linux/compose.yml logs --tail 100 foundation provider kafka
# 需求方应用为文件日志，不通过stdout输出完整应用日志
sudo tail -n 100 deploy/linux/.private/data/demand/demand.log
# 正常停止，数据保留
sudo docker compose -f deploy/linux/compose.yml stop
# 使用已有配置恢复，不能重新init清空数据
sudo docker compose -f deploy/linux/compose.yml up -d --wait --wait-timeout 300
```

Docker stdout/stderr每个服务单文件10MB、最多5个；需求方应用日志单文件10MB、历史7天、归档总量70MB。Nginx不记录请求体、认证头、Cookie或查询字符串，不代理缓存业务响应；业务文件下载不使用磁盘响应缓冲。

Linux Kafka普通主题默认保留7天、每分区容量上限2GiB，任一条件达到后可能清理旧段。这不是集群总空间上限，内部主题及索引额外占用磁盘。接收检查点超出保留范围会按原规则阻塞，不静默跳过；后续应根据最长停机时间和业务量专项调整并重新验收；当前私有配置有完整性保护，不要直接修改后重新初始化。单副本不提供容灾。

人工备份（会短暂停止接收/发送并恢复整个原运行栈）：

```bash
sudo python3 deploy/linux/manage.py backup
```

仅在完整栈正常运行时执行。先停止网关/三个应用及Kafka，保留MySQL在线，再导出三个库的一致SQL快照，保存Kafka停机数据、原私有配置/密钥、公用集群编号、证书和提供方补报文件。备份位于`.private/backups`，含业务数据和秘密，只能转存到受控且加密的备份位置。脚本最后恢复服务；失败会删除不完整备份，不改原数据。备份只是恢复材料，**未完成独立恢复演练前不声明可恢复性已验收**。

恢复演练必须在全新隔离目录：使用匹配版本镜像、原私有配置和权限；新MySQL按原账号初始化后恢复SQL；停机恢复同一Kafka数据与公开集群编号；再启动应用核对授权、主题ID、检查点、消息数量和内容。不能把归档解压覆盖在线目录，不能单独回滚数据库或Kafka，也不要用`down -v`或删除`.private`解决问题。

更新前保留本版源码/镜像ID，先执行备份，再在新发布目录准备新版本；私有状态复用与Flyway兼容需审核。迁移后不能仅换旧镜像声称回滚，需验证数据库及Kafka匹配。当前没有自动升级、自动回滚或灾备编排。

## 11. 故障定位与上线验收

| 现象 | 检查 |
|---|---|
| SSH无法连接 | 实例运行状态、EIP、安全组22、登录账号和密钥 |
| 拉镜像/构建失败 | 出网、DNS、官方源可达性、磁盘；不是平台密码问题 |
| MySQL不健康 | 初始化日志、私有文件权限、首次SQL是否全部成功、13306端口 |
| Kafka不健康/初始化失败 | 原配置/数据是否匹配，19092/19093端口，SCRAM/ACL检查；禁止重新格式化 |
| Foundation失败 | 数据库、Flyway、凭据主密钥、Kafka初始化公开编号 |
| 来源验证失败 | 登记的127.0.0.1/13306/port_source_db必须与Provider配置相同 |
| 502 | 三个后端健康状态、Nginx代理路径；不关闭身份校验 |
| 401/403 | 登录Cookie、CSRF、角色/主体及授权；失效后重新登录 |
| 接收0条 | 先检查提供方是否已明确发送，再查看接收进度；不是源表有记录就自动入Kafka |
| HTTPS后Cookie异常 | 证书、同源访问、Secure设置；不要混用HTTP和HTTPS |

服务器验收至少记录：Docker/镜像版本，全部服务健康，三账号操作链，跨主体拒绝，至少一条真实发送与落库内容一致，停止/恢复、服务与整机重启后的持久化，实际撤权后拒绝接收、历史查询仍可用，下载完整性及备份恢复演练结果。

本方案的Kafka仅服务同机模块，不对外提供跨服务器消费入口；跨主机需另配可达地址及SASL_SSL。没有CDC、多集群、高可用、自动业务表映射或任意外部数据库生产器。平台管理员没有业务消息页面权限，但同一主机高权限运维人员仍拥有底层数据访问能力。

## 接收监测配置升级

当前新版将独立Kafka监测归口管理员，供需双方在数据发送、数据接收页查看本方记录。新建部署自动生成独立接收监测凭据；已有私有配置执行 `sudo python3 deploy/linux/manage.py enable-receiver-monitor`，验证完成后依次重建底座、需求方和前端，见[监测指南](KAFKA_TRANSFER_MONITOR_GUIDE.md)。不得重新初始化已有数据库或改变原加密密钥。新增凭据仅允许技术事件上报，不赋予审批、业务查询或Kafka权限。

## 统一传输监测与耗时升级

管理员四个监管菜单已合并为一个“传输监测”页面。底座追加V19、需求方追加V4，仅增加可空技术耗时列；原独立上报凭据继续使用。保留私有配置和数据库，依次更新底座、需求方、前端；不需要重新初始化环境。界面和口径见[统一传输监测指南](TRANSFER_MONITOR_GUIDE.md)。本次源码更新不等于云端已运行验收。

## 资源生命周期升级（2026-10-17）

本地交付源码包已包含底座V20及前端四状态操作。已有部署保留全部数据库、私有配置和密钥，先更新底座（正常执行追加迁移），再更新前端；不执行环境初始化或重建数据卷。无需新增环境变量，需求方仍V4。首次发布请求须携带version及reason，草稿删除须携带version，旧客户端同步更新。下架后的REST暂停/Kafka撤权及手动恢复规则见[生命周期指南](RESOURCE_LIFECYCLE_GUIDE.md)。此包尚未在目标云服务器实际构建和运行验收，本轮不部署云端。
