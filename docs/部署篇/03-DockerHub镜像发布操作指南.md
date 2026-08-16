# Docker Hub 镜像发布操作指南

> 目标：把 OpenReach 发布成 `linux/amd64 + linux/arm64` 多架构镜像，让普通用户只执行一条 `docker run`，Docker 自动选择适配当前 CPU 的镜像。

本文默认示例 Docker Hub Namespace 为 `codercl`，最终镜像：

```text
codercl/openreach:0.1.3
codercl/openreach:latest
```

如果你的真实 Docker ID 不是 `codercl`，把下文中的 `codercl` 替换为真实 Docker ID。

---

## 1. Docker Hub 创建公开仓库

登录 Docker Hub 后创建 Repository：

```text
Repository Name: openreach
Visibility: Public
```

最终仓库名应类似：

```text
codercl/openreach
```

---

## 2. 本地登录 Docker Hub

```bash
docker login
```

看到：

```text
Login Succeeded
```

即可继续。

注意：`docker info | grep Username` 没有输出并不能单独证明未登录。

---

## 3. 检查 Buildx

```bash
docker buildx version
```

首次可以创建 OpenReach 专用 Builder：

```bash
docker buildx create \
  --name openreach-builder \
  --driver docker-container \
  --use
```

初始化：

```bash
docker buildx inspect --bootstrap
```

重点确认支持：

```text
linux/amd64
linux/arm64
```

---

## 4. 先验证当前源码能在本机构建

OpenReach 的 Dockerfile 使用支持多架构的 Java 17 Noble 基础镜像：

```text
maven:3.9.16-eclipse-temurin-17-noble
eclipse-temurin:17-jre-noble
```

本地构建：

```bash
docker build --no-cache -t openreach:local .
```

启动：

```bash
docker run -d \
  --name openreach-local \
  -p 8080:8080 \
  openreach:local
```

日志：

```bash
docker logs -f openreach-local
```

验证完成后：

```bash
docker rm -f openreach-local
```

---

## 5. 正式发布 AMD64 + ARM64

推荐直接使用项目脚本：

```bash
./bin/quick/release.sh 0.1.3
```

脚本会执行：

```text
检查 Docker
    ↓
检查 Buildx
    ↓
创建/复用 openreach-builder
    ↓
构建 linux/amd64
    +
构建 linux/arm64
    ↓
Push codercl/openreach:0.1.3
    ↓
Push codercl/openreach:latest
    ↓
检查远程 Manifest
```

手工执行时必须先生成 JAR：

```bash
./bin/quick/package.sh

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t codercl/openreach:0.1.3 \
  -t codercl/openreach:latest \
  --push \
  .
```

---

## 6. 验证多架构 Manifest

```bash
docker buildx imagetools inspect codercl/openreach:0.1.3
```

至少应看到：

```text
linux/amd64
linux/arm64
```

只有两个平台都存在，才算完成正式的多架构发布。

---

## 7. 模拟普通用户安装

删除本地测试镜像不是必须，但可以帮助确认 Registry 拉取链路：

```bash
docker pull codercl/openreach:latest
```

然后：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:latest
```

正常情况下：

- Intel / AMD 主机自动拉 `linux/amd64`；
- Apple Silicon / ARM Server 自动拉 `linux/arm64`；
- 普通用户不需要写 `--platform`。

---

## 8. 用户最终的一键命令

README 面向普通用户只需要推荐：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:latest
```

如果生产环境要求版本固定：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:0.1.3
```

---

## 9. Docker Compose 部署

项目根目录的 `docker-compose.yml` 默认直接使用已发布镜像：

```yaml
image: "${OPENREACH_IMAGE:-codercl/openreach:latest}"
```

因此镜像发布后，用户可执行：

```bash
docker compose up -d
```

如果需要固定版本：

```bash
OPENREACH_IMAGE=codercl/openreach:0.1.3 docker compose up -d
```

如果你还没有发布 Docker Hub 镜像，需要从当前源码本地构建，则执行：

```bash
./bin/quick/package.sh && docker compose -f docker-compose.build.yml up -d --build
```

当前 Dockerfile 是 Runtime-only Dockerfile：Java 编译与单元测试在宿主机执行一次，Docker 只复制 `target/openreach-*.jar`。

---

## 10. 下一版本如何发布

例如发布 `0.1.3`：

```bash
docker login
./bin/quick/release.sh 0.1.3
```

发布后：

```text
codercl/openreach:0.1.3
codercl/openreach:0.1.3
codercl/openreach:latest  -> 当前稳定版本
```

后续可以把这套脚本迁移到 GitHub Actions，实现 Git Tag 后自动发布 Docker Hub / GHCR。

---

## 网络受限环境：通过项目参数指定构建代理

如果 Docker Hub 直连出现 `TLS handshake timeout`，而你的本机代理地址例如为：

```text
http://127.0.0.1:7891
```

推荐直接使用 OpenReach 提供的可选参数：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh 0.1.3
```

macOS 下脚本会自动把宿主机回环地址转换成 BuildKit 容器可访问的：

```text
http://host.docker.internal:7891
```

详细原理与 Linux/Compose 用法见：

[`04-Docker构建代理配置.md`](04-Docker构建代理配置.md)
