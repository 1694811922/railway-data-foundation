# 2026-10-16 百度云部署材料与文件交付

针对现有百度云4核16GiB Ubuntu24.04主机，新增独立`deploy/linux`单机容器部署目录，不覆盖原Compose数据库方案或Windows启动脚本。它仍是当前科研原型的部署准备，不是已完成云端上线。

## 文件与作用

| 文件 | 用途 |
|---|---|
| `deploy/linux/compose.yml` | MySQL、Kafka、一次性Kafka初始化、三个后端及Nginx，共7个服务；启动依赖、健康检查、日志限制、持久化目录 |
| `Dockerfile.backend` / `Dockerfile.kafka` / `Dockerfile.web` | 构建三个应用、现有Kafka3.9.1原生运行依赖、Vue构建结果及网关 |
| `manage.py` | 首次配置、原密钥保护、主机检查、交互初始化管理员、HTTPS配置、停机一致备份材料 |
| `app-entrypoint.sh` / `kafka-entrypoint.sh` | 公开集群编号加载；区分新集群与正常恢复，防止数据丢失时静默格式化或改变集群编号 |
| `HealthCheck.java` | 检查实际HTTP状态与统一响应成功码 |
| `test_manage.py` | 私有状态保护、权限配置、网关和保留策略等12项部署材料测试 |
| `package-release.py` | 在开发电脑生成源码部署包和SHA-256，排除运行数据、秘密和依赖 |
| `.dockerignore` / `.gitattributes` / `.gitignore` | 防止秘密进入构建上下文、固定脚本LF、忽略私有状态和交付压缩包 |

完整命令见[百度云部署步骤](BAIDU_CLOUD_DOCKER_DEPLOYMENT.md)。ZIP位于工作区`交付文件`目录，GitHub旧提交没有本轮新增文件；本轮不自动提交或推送Git。

## 已验证

- 12项Python部署管理测试通过；使用独立临时目录中的随机测试秘密，没有访问本机业务数据库。
- Compose通过官方compose-spec JSON Schema检查，7个服务的依赖、host网络及应用不挂载Kafka业务存储检查通过。
- 两个Shell入口语法检查通过；Java17健康探测编译及4类响应（成功、业务失败、小数伪成功、HTTP503）验证通过。
- 前端`npm run build`通过，仍有既有包体积提示；前端产品逻辑和三个后端产品Java代码均未修改。
- 未修改本机数据库、账号、Kafka消息、ACL或消费位置；没有连接百度云主机或执行云端安装。

## 尚待服务器验收

当前电脑未安装Docker Engine/WSL运行环境，因此**尚未执行镜像构建、容器启动、Nginx实际语法检查、Linux用户/卷权限、云端登录及真实收发撤权、整机重启或恢复演练**。静态规范检查不替代`docker compose config --quiet`，更不替代完整运行测试。

部分基础镜像仅约束主版本，尚未锁定摘要。新环境默认源SQL初始化20条模拟业务记录，源读取账号只读；没有自动迁移Windows历史或保留原集群订阅。Kafka保留7天且每分区2GiB，单副本，不具备高可用或跨主机加密访问。

默认网关只监听服务器回环8080，通过SSH隧道验收；取得正式域名证书后才开启443与安全Cookie。采用Linux host网络保留现有回环认证规则，没有降低远程HTTPS/SASL_SSL要求；同机容器不具备网络隔离，跨主机方案需另行设计。
