# Docker 一键部署指南

本文档说明 OpenReach 的两种 Docker 使用方式：

1. **普通用户部署：直接拉取 Docker Hub 已发布镜像，不需要源码、不需要 JDK、不需要 Maven。**
2. **开发者本地构建：从当前源码构建镜像，构建阶段自动执行 Maven 测试。**

当前服务本身不依赖数据库、Redis、Python、SearXNG 或商业 Search API，因此 V1 只需要一个 Java 应用容器。

---

## 1. 普通用户：一条命令启动

正式镜像发布到 Docker Hub 后，推荐：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:latest
```

固定版本：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:0.1.0
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
OPENREACH_IMAGE=codercl/openreach:0.1.0 docker compose up -d
```

修改宿主机端口：

```bash
APP_PORT=18080 docker compose up -d
```

然后访问：

```text
http://localhost:18080
```

---

## 3. 开发者：从源码构建

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

## 4. Dockerfile 多架构设计

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

## 5. 正式发布 Docker Hub 多架构镜像

先登录：

```bash
docker login
```

然后推荐直接使用项目脚本：

```bash
./bin/quick/release.sh 0.1.0
```

脚本会构建并推送：

```text
codercl/openreach:0.1.0
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
  -t codercl/openreach:0.1.0 \
  -t codercl/openreach:latest \
  --push \
  .
```

推荐仍然使用 `release.sh`，避免遗漏打包、测试和 Manifest 校验。

完整操作见：

- [Docker Hub 镜像发布操作指南](03-DockerHub镜像发布操作指南.md)

---

## 6. 验证多架构镜像

发布后：

```bash
docker buildx imagetools inspect codercl/openreach:0.1.0
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

## 7. 查看状态与日志

Docker Run：

```bash
docker ps
docker logs -f openreach
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

## 8. JVM 参数

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
  codercl/openreach:latest
```

Compose：

```bash
JAVA_OPTS='-Xms256m -Xmx1024m' docker compose up -d
```

---

## 9. 容器安全策略

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

## 10. 国内网络注意事项

### 构建阶段

本地源码构建需要拉取 Maven / Eclipse Temurin 基础镜像，以及 Maven 依赖。

如果 Docker Hub 或 Maven Central 网络较慢，应根据实际网络环境配置可信镜像加速或代理；项目本身不硬编码第三方镜像站。

### 运行阶段

当前 Web Search 默认：

```text
Bing 中国 -> 百度 -> 搜狗 -> 360 -> DuckDuckGo
```

Image Search 默认：

```text
Bing Images -> 百度图片 -> 搜狗图片 -> Openverse
```

因此 OpenReach 容器需要能够主动访问公网。

---

## 11. 常用命令

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

# 一键发布 0.1.0 多架构镜像
./bin/quick/release.sh 0.1.0

# 检查远程 manifest
docker buildx imagetools inspect codercl/openreach:0.1.0
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

