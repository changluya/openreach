# Docker 构建与发布代理配置

本文说明 OpenReach 在国内网络或代理环境下如何完成 Maven 打包、Docker Runtime 镜像构建和 Docker Hub 多架构发布。

## 1. 当前网络链路

OpenReach 已经不在 Dockerfile 里运行 Maven。

当前链路为：

```text
宿主机
├── Maven Central
│   └── mvn clean package
│
└── Docker Buildx
    ├── 拉取 eclipse-temurin:17-jre-noble
    └── Push Docker Hub
```

因此一个 `OPENREACH_BUILD_PROXY` 会被分成两种地址使用：

```text
宿主机 Maven / Buildx Client
  -> http://127.0.0.1:7891

BuildKit Container（macOS Docker Desktop）
  -> http://host.docker.internal:7891
```

## 2. Mac 推荐用法

假设本机代理：

```text
http://127.0.0.1:7891
```

一键发布：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh 1.0.2
```

脚本会自动处理：

```text
Host Maven
  -> 127.0.0.1:7891

Buildx Client
  -> 127.0.0.1:7891

BuildKit Builder
  -> host.docker.internal:7891
```

## 3. Maven 为什么使用宿主机代理

因为 Maven 现在直接运行在你的 Mac / Linux 主机上：

```bash
mvn clean package
```

所以它可以直接访问：

```text
127.0.0.1:7891
```

不再需要把 Maven Proxy 转换成 `host.docker.internal`，也不需要在 Dockerfile 里生成 Maven `settings.xml`。

`bin/quick/package.sh` 在设置 `OPENREACH_BUILD_PROXY` 时，会创建临时 Maven proxy settings，构建结束后自动删除。

## 4. 为什么 BuildKit 仍然需要 `host.docker.internal`

多架构发布使用：

```text
docker-container Buildx driver
```

BuildKit 自己运行在容器中。容器里的：

```text
127.0.0.1
```

表示 BuildKit 容器自己，并不是 Mac。

所以脚本会自动把：

```text
http://127.0.0.1:7891
```

转换成：

```text
http://host.docker.internal:7891
```

供 BuildKit 拉取基础镜像和访问 Registry。

## 5. 本地镜像构建

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-build.sh openreach:local
```

流程：

```text
Host Maven Package
→ target/openreach-*.jar
→ Docker Runtime-only Build
→ openreach:local
```

## 6. 本地镜像验收

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-verify.sh
```

验证包括：

```text
Maven Package
→ Docker Build
→ docker run
→ Spring Boot HTTP Validation
```

## 7. Docker Hub 多架构发布

推荐：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh 1.0.2
```

等价底层命令：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-publish.sh 1.0.2 codercl openreach
```

最终发布：

```text
codercl/openreach:1.0.2
codercl/openreach:latest
```

平台：

```text
linux/amd64
linux/arm64
```

## 8. Buildx `NO_PROXY` 注意事项

不要把下面这种包含逗号的值直接作为一个 Buildx `--driver-opt`：

```text
NO_PROXY=localhost,127.0.0.1,::1
```

Buildx driver options 使用 CSV 风格解析，可能产生：

```text
invalid value "127.0.0.1", expecting k=v
```

OpenReach 当前 Builder 只注入：

```text
HTTP_PROXY
HTTPS_PROXY
```

避免这个问题。

## 9. 代理连通性测试

Docker Registry：

```bash
curl -v \
  -x http://127.0.0.1:7891 \
  --connect-timeout 10 \
  --max-time 20 \
  https://registry-1.docker.io/v2/
```

返回：

```text
401 Unauthorized
```

反而说明 Registry 网络链路已经打通，只是尚未完成 Docker Bearer Token 认证。

Maven Central：

```bash
curl -I \
  -x http://127.0.0.1:7891 \
  https://repo.maven.apache.org/maven2/
```

## 10. Linux 主机

Linux 下 Docker 容器通常不能通过 `127.0.0.1` 访问宿主机代理。

应把 `OPENREACH_BUILD_PROXY` 设置成 BuildKit 容器也能访问的宿主机 IP / DNS，例如：

```bash
OPENREACH_BUILD_PROXY=http://192.168.1.10:7891 \
./bin/quick/release.sh 1.0.2
```

## 11. 核心原则

当前 OpenReach 的代理职责已经简化为：

```text
Maven -> 宿主机代理
Docker Registry -> Buildx / BuildKit 代理
Dockerfile -> 不再负责 Maven 网络
```

这比在多架构 Docker Build 里同时维护 Maven/JDK/Proxy 更简单，也更符合 Java 项目的常规发布方式。
