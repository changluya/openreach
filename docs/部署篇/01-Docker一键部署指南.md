# Docker 一键部署指南

本文档说明 OpenReach 的两种 Docker 使用方式：

1. **普通用户部署：直接拉取 Docker Hub 已发布镜像，不需要源码、不需要 JDK、不需要 Maven。**
2. **开发者本地构建：从当前源码构建镜像，构建阶段自动执行 Maven 测试。**

当前服务不依赖外置数据库、Redis、Python、SearXNG 或商业 Search API。v0.1.3 的内部监控使用进程内 SQLite，因此普通用户仍只需要 OpenReach Java 应用；Compose 额外包含一个一次性 init 容器用于修正 Host Bind 权限，执行完成即退出。

---

## 1. 普通用户：一条命令启动

正式镜像发布到 Docker Hub 后，推荐：

```bash
sudo mkdir -p /data/openreach/data /data/openreach/logs
sudo chown -R 10001:10001 /data/openreach

docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  -e OPENREACH_LOG_PATH=/app/logs \
  -e OPENREACH_MONITOR_USERNAME=openreach \
  -e OPENREACH_MONITOR_PASSWORD=openreach \
  -v /data/openreach/data:/app/data \
  -v /data/openreach/logs:/app/logs \
  --log-driver json-file \
  --log-opt max-size=20m \
  --log-opt max-file=3 \
  codercl/openreach:latest
```

固定版本：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  -e OPENREACH_LOG_PATH=/app/logs \
  -e OPENREACH_MONITOR_USERNAME=openreach \
  -e OPENREACH_MONITOR_PASSWORD=openreach \
  -v /data/openreach/data:/app/data \
  -v /data/openreach/logs:/app/logs \
  --log-driver json-file \
  --log-opt max-size=20m \
  --log-opt max-file=3 \
  codercl/openreach:0.1.3
```

Docker 会根据客户机器自动选择：

```text
Intel / AMD       -> linux/amd64
Apple Silicon/ARM -> linux/arm64
```

普通用户不需要添加 `--platform`。

详细原理见：

- [Docker 多架构镜像与一键部署核心知识点](02-Docker多架构镜像与一键部署核心知识点.md)

---

## 2. Docker Compose：直接拉公开镜像

项目根目录 `docker-compose.yml` 默认：

```yaml
image: "${OPENREACH_IMAGE:-codercl/openreach:latest}"
```

因此发布 Docker Hub 镜像后可以直接：

```bash
docker compose up -d
```

固定版本：

```bash
OPENREACH_IMAGE=codercl/openreach:0.1.3 docker compose up -d
```

修改宿主机端口：

```bash
APP_PORT=18080 docker compose up -d
```

启动或升级时指定内部监控账号密码：

```bash
OPENREACH_MONITOR_USERNAME=admin \
OPENREACH_MONITOR_PASSWORD='change-me' \
OPENREACH_IMAGE=codercl/openreach:0.1.3 \
  docker compose up -d --force-recreate
```

也可以复制根目录 `.env.example` 为 `.env`，Compose 会自动加载。用户名 / 密码是**容器运行配置**，不会写入 SQLite；因此销毁旧容器后重建时，只要继续传入同一组环境变量即可保持原登录凭据。若不传，默认仍为 `openreach / openreach`。

然后访问：

```text
http://localhost:18080
```

---


## 2.1 v0.1.3 监控数据持久化与容器升级

`/app/data` 是 OpenReach 的稳定持久化目录。默认 SQLite 文件位于：

```text
/app/data/monitor/openreach-monitor.db
```

因此标准升级原则是：**容器可以删除，宿主机 data 目录不能删除。**

Docker Run 使用相同 Host Bind 重建即可：

```bash
docker pull codercl/openreach:0.1.3
docker rm -f openreach

docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  -e OPENREACH_MONITOR_DATA_DIR=/app/data/monitor \
  -e OPENREACH_LOG_PATH=/app/logs \
  -e OPENREACH_MONITOR_USERNAME=openreach \
  -e OPENREACH_MONITOR_PASSWORD=openreach \
  -v /data/openreach/data:/app/data \
  -v /data/openreach/logs:/app/logs \
  codercl/openreach:0.1.3
```

Compose 已内置 `openreach-init`，会在主服务启动前以最小 `CAP_CHOWN` 修正 `/app/data`、`/app/logs` 的 UID 10001 权限；正式 OpenReach 容器仍以非 root 用户运行。

升级时推荐：

```bash
docker compose pull
docker compose up -d --force-recreate
```

不要把 `docker compose down -v`、删除 `${OPENREACH_DATA_DIR}` 或 `rm -rf /data/openreach/data` 作为升级步骤。未来 Monitor Schema 有增量变更时，新镜像会读取旧数据库并按 Schema Version 执行 forward migration。

## 3. Nginx 反向代理与监控真实客户端 IP

OpenReach 部署在 Docker 后并由宿主机 Nginx 反代时，Servlet 的 `remoteAddr` 往往只能看到 Docker 网桥地址（常见为 `172.17.0.1`）。要让 `/monitor` 的“IP 地址”显示真实调用服务器 IP，需要 Nginx 显式覆盖以下请求头：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

OpenReach v0.1.3 默认配置：

```text
OPENREACH_MONITOR_TRUST_PROXY_HEADERS=true
OPENREACH_MONITOR_TRUSTED_PROXY_CIDRS=127.0.0.1/32,::1/128,172.16.0.0/12
```

安全规则：只有 OpenReach 的**直接 TCP 来源**属于可信代理 CIDR 时，应用才会读取 `X-Forwarded-For/X-Real-IP`；随后从 `X-Forwarded-For` **右向左**跳过可信代理节点，取第一个非可信节点作为真实客户端 IP。因此客户端直接访问 OpenReach 并自行伪造 Header 时不会污染监控 IP。

如果你的 Nginx/Ingress 使用 `10.x`、`192.168.x` 或自定义 Docker Network，请把实际代理网段追加到 `OPENREACH_MONITOR_TRUSTED_PROXY_CIDRS`，不要直接配置成 `0.0.0.0/0`。

如果 Nginx 前还有 CDN/SLB，需要同时把**确实可信的 CDN/SLB 出口网段**加入可信代理列表，否则 OpenReach 会把该层公网代理 IP 视为客户端 IP；这属于安全预期。

> 注意：修复只影响升级后的新请求记录，历史 SQLite 中已经保存的 `172.17.0.1` 无法从现有数据反推出真实来源。

---

## 4. 开发者：从源码构建

OpenReach 当前采用 **先打 JAR，再构建 Runtime 镜像** 的方式，不在 Dockerfile 中运行 Maven。

推荐直接执行：

```bash
./bin/quick/docker-verify.sh
```

内部流程：

```text
宿主机 Maven
   ↓
mvn clean package
   ↓
执行全部 JUnit Tests
   ↓
target/openreach-*.jar
   ↓
Dockerfile COPY JAR
   ↓
JRE 17 Runtime Image
   ↓
启动容器并执行本地 HTTP 验收
```

如果只希望生成镜像：

```bash
./bin/quick/docker-build.sh openreach:local
```

如果已经手动执行过：

```bash
./bin/quick/package.sh
```

可以跳过重复 Maven 打包：

```bash
OPENREACH_SKIP_PACKAGE=true ./bin/quick/docker-build.sh openreach:local
```

如果仍希望直接使用 Compose 源码构建，需要先确保 `target/openreach-*.jar` 已存在：

```bash
./bin/quick/package.sh && docker compose -f docker-compose.build.yml up -d --build
```

---

## 5. Dockerfile 多架构设计

当前 Dockerfile 是 **Runtime-only Dockerfile**：

```text
Host:
  Maven 3.9+ + JDK 17+
  -> mvn clean package
  -> target/openreach-*.jar

Docker Runtime:
  eclipse-temurin:17-jre-noble
  -> COPY target/openreach-*.jar /app/app.jar
```

Spring Boot 可执行 JAR 与 CPU 架构无关，因此多架构发布时完全没有必要针对 AMD64 / ARM64 分别编译 Java 项目。

Buildx 只需要为下面两个平台生成 Runtime Image：

```text
linux/amd64
linux/arm64
```

这样可以避免：

- 两个架构重复下载 Maven 依赖；
- 两个架构重复执行单元测试；
- QEMU 下运行 Maven；
- Docker 构建阶段维护 Maven 代理配置。

Dockerfile 还直接使用数值 UID/GID `10001:10001` 运行应用，不需要额外执行 `groupadd/useradd`，因此 Runtime 构建基本只剩下基础镜像 + COPY JAR。

---

## 6. 正式发布 Docker Hub 多架构镜像

先登录：

```bash
docker login
```

然后推荐直接使用项目脚本：

```bash
./bin/quick/release.sh 0.1.3
```

脚本会构建并推送：

```text
codercl/openreach:0.1.3
codercl/openreach:latest
```

目标平台：

```text
linux/amd64
linux/arm64
```

如果手工执行，必须先在宿主机生成 JAR，再调用 Buildx：

```bash
./bin/quick/package.sh

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t codercl/openreach:0.1.3 \
  -t codercl/openreach:latest \
  --push \
  .
```

推荐仍然使用 `release.sh`，避免遗漏打包、测试和 Manifest 校验。

完整操作见：

- [Docker Hub 镜像发布操作指南](03-DockerHub镜像发布操作指南.md)

---

## 7. 验证多架构镜像

发布后：

```bash
docker buildx imagetools inspect codercl/openreach:0.1.3
```

至少应确认：

```text
linux/amd64
linux/arm64
```

然后模拟普通用户：

```bash
docker pull codercl/openreach:latest

docker run -d \
  --name openreach \
  -p 8080:8080 \
  codercl/openreach:latest
```

---

## 8. 查看状态与日志

Docker Run：

```bash
docker ps
docker logs -f openreach

# 全量应用日志
tail -f /data/openreach/logs/openreach.log

# API 入口/出口日志
tail -f /data/openreach/logs/openreach-api.log

# Provider / Read 上游日志
tail -f /data/openreach/logs/openreach-upstream.log
```

按 Trace ID 串联一次请求：

```bash
grep 'req-<timestamp>-<suffix>' /data/openreach/logs/openreach*.log
```

Compose：

```bash
docker compose ps
docker compose logs -f openreach
```

默认地址：

```text
http://localhost:8080
```

接口 Curl：

- [接口测试与 Curl 示例](../接口测试与Curl示例.md)

---

## 9. JVM 参数

默认：

```text
-Xms128m -Xmx512m
```

Docker Run：

```bash
docker run -d \
  --name openreach \
  -p 8080:8080 \
  -e 'JAVA_OPTS=-Xms256m -Xmx1024m' \
  -e OPENREACH_LOG_PATH=/app/logs \
  -e OPENREACH_MONITOR_USERNAME=openreach \
  -e OPENREACH_MONITOR_PASSWORD=openreach \
  -v /data/openreach/data:/app/data \
  -v /data/openreach/logs:/app/logs \
  --log-driver json-file \
  --log-opt max-size=20m \
  --log-opt max-file=3 \
  codercl/openreach:latest
```

Compose：

```bash
JAVA_OPTS='-Xms256m -Xmx1024m' docker compose up -d
```

---

## 10. 容器安全策略

当前镜像 / Compose 设计包含：

```text
非 root 用户运行
read_only filesystem（Compose）
/tmp 使用 tmpfs（Compose）
no-new-privileges（Compose）
cap_drop: ALL（Compose）
```

这些不能替代应用内部安全逻辑。`read(url)` 仍然通过 `UrlSafetyGuard` 与 `SafeHttpFetcher` 执行 SSRF / Redirect 校验。

---

## 11. 国内网络注意事项

### 构建阶段

本地源码构建需要拉取 Maven / Eclipse Temurin 基础镜像，以及 Maven 依赖。

如果 Docker Hub 或 Maven Central 网络较慢，应根据实际网络环境配置可信镜像加速或代理；项目本身不硬编码第三方镜像站。

### 运行阶段

v0.1.2 根据请求 `region` 自动选择免费 Provider Chain：

```text
Web / CN     : Bing 中国 -> 百度 -> 搜狗 -> 360 -> DuckDuckGo
Web / GLOBAL : Brave -> DuckDuckGo -> Bing Global

Image / CN     : Bing Images 中国 -> 百度图片 -> 搜狗图片 -> Openverse
Image / GLOBAL : Bing Images Global -> Openverse -> Wikimedia Commons
```

`region=auto` 默认仍走 CN；显式非 CN 地区（如 `US` / `JP` / `SG`）进入 GLOBAL。显式 `provider` 则直接调用指定 Provider，不执行自动链。

因此 OpenReach 容器需要能够主动访问公网。

---

## 12. 常用命令

```bash
# 普通用户：公开镜像启动
docker compose up -d

# 开发者：源码构建启动（先在宿主机打 JAR）
./bin/quick/package.sh && docker compose -f docker-compose.build.yml up -d --build

# 查看状态
docker compose ps

# 日志
docker compose logs -f openreach

# 停止
docker compose down

# 一键发布 0.1.2 多架构镜像
./bin/quick/release.sh 0.1.3

# 检查远程 manifest
docker buildx imagetools inspect codercl/openreach:0.1.3
```

---

## Docker 构建性能设计（当前版本）

当前版本使用最直接的 Java 镜像发布模型：**宿主机编译一次，Docker 只封装 JAR。**

```text
Host Maven
   │
   ├── mvn clean package（一次）
   ├── 全量单元测试（一次）
   └── 直接复用 ~/.m2
   │
   ▼
target/openreach-*.jar
   │
   ├─────────────┐
   ▼             ▼
AMD64 JRE      ARM64 JRE
   │             │
   └──────┬──────┘
          ▼
 Multi-arch Manifest
```

因此正式多架构构建的 Docker 阶段不再看到：

```text
mvn dependency:go-offline
mvn clean package
```

它只需要：

```text
FROM eclipse-temurin:17-jre-noble
COPY target/openreach-*.jar /app/app.jar
```

正式发布推荐一条命令：

```bash
./bin/quick/release.sh
```

代理环境：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh
```

正式发布前若希望独立验证本地镜像：

```bash
./bin/quick/docker-verify.sh
```

验收脚本会实际构建镜像、启动容器，并通过一个不依赖公网 Provider 的 HTTP 参数校验请求确认 Spring Boot 正常提供服务。


---

## v0.1.2 公网安全补充

OpenReach 当前业务面严格只有三个 JSON POST API：

```text
POST /api/web/search
POST /api/web/image-search
POST /api/web/read
```

部署验活请使用只读官网根路径 `GET /`，不再额外开放 Health API。应用层 `AttackSurfaceFilter` 会拒绝 Multipart/文件上传、未知端点、危险 HTTP Method、路径穿越和超大请求体；静态资源固定来自 classpath。

Docker 生产部署建议继续保持：

```text
非 root
read_only root filesystem
no-new-privileges
cap_drop: ALL
只暴露应用端口
出口 ACL 禁止 RFC1918 / link-local / cloud metadata
反向代理限流 / WAF
```

Read 与 Image 原图探测虽然有应用层 SSRF Guard，但仍建议用网络层 egress ACL 做纵深防御。
