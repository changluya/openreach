# OpenReach 快捷命令

`bin/quick/` 维护 OpenReach 的打包、测试、Docker 本地构建、镜像验收和 Docker Hub 发布命令。v1.0.2 起 `check-project.sh` / `package.sh` 会同时执行 Java JUnit 与 OpenReach Skill Python Test。

## 推荐链路

OpenReach 是 Java/Spring Boot 项目，默认采用最简单的镜像构建模型：

```text
mvn clean package（宿主机，只执行一次，包含单测）
        ↓
target/openreach-*.jar
        ↓
Dockerfile COPY JAR
        ↓
JRE Runtime Image
        ↓
amd64 / arm64
```

**Dockerfile 不再运行 Maven。** 因此 Maven 依赖缓存直接复用你本机的 `~/.m2`，Docker 多架构阶段只负责封装运行时镜像。

---

## 1. 一键发布（推荐）

### 1.1 前置条件（只做一次）

- Docker / OrbStack 正常运行，且 `docker info` 能看到 HTTP/HTTPS Proxy 指向宿主机代理（若需国内网络）。
- 已登录 Docker Hub，且登录账号（Docker ID）与发布 Namespace 一致，才有 push 权限：

```bash
docker login
```

> 登录状态可用 `docker info` 或 `cat ~/.docker/config.json` 确认。常见失败：
> - `insufficient_scope: authorization failed` → 未登录 / 账号无该 Namespace 权限。
> - 报 `*.facebook.com` 等证书错误 → 宿主机 DNS 被劫持，需先给 Docker daemon 配置代理（见 `docs/部署篇/05-Docker网络与发布坑点排障.md`）。

### 1.2 一键发布

默认从 `pom.xml` 读取版本、默认 Docker Hub Namespace 为 `codercl`：

```bash
./bin/quick/release.sh
```

显式指定版本：

```bash
./bin/quick/release.sh 1.0.2
```

国内网络需要本地代理时（推荐）：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh 1.0.2
```

这一条命令内部完成：

```text
mvn clean package + 全量单测
→ 生成 JAR
→ Buildx 构建 linux/amd64 + linux/arm64 Runtime 镜像
→ Push codercl/openreach:<version>
→ Push codercl/openreach:latest
→ 校验远程 Manifest
```

### 1.3 发布后验证

确认远程镜像两个架构都在：

```bash
docker buildx imagetools inspect codercl/openreach:1.0.2
```

普通用户部署（无需 Maven/JDK/源码）：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:latest
```

---

## 2. 只编译 / 测试 / 打 JAR

```bash
./bin/quick/package.sh
```

使用代理访问 Maven Central：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/package.sh
```

输出：

```text
target/openreach-*.jar
```

---

## 3. 本地 Docker 镜像

```bash
./bin/quick/docker-build.sh
```

指定镜像名：

```bash
./bin/quick/docker-build.sh codercl/openreach:local
```

代理环境：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-build.sh codercl/openreach:local
```

`docker-build.sh` 会先调用 `package.sh`，随后 Dockerfile 只执行 Runtime 封装。

如果你刚刚已经手动执行过 `package.sh`，可以跳过重复 Maven 打包：

```bash
OPENREACH_SKIP_PACKAGE=true \
./bin/quick/docker-build.sh codercl/openreach:local
```

---

## 4. 本地镜像启动验收

```bash
./bin/quick/docker-verify.sh
```

代理环境：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-verify.sh
```

验证链路：

```text
Host Maven Package
→ Docker Runtime Image
→ 启动 OpenReach 容器
→ POST /api/web/search 空请求
→ 预期 HTTP 400
→ PASS
```

这个验收不依赖 Bing、百度、搜狗等公网 Provider。

---

## 5. 只执行单测

```bash
./bin/quick/check-project.sh
```

服务已经启动，并希望继续跑公网 Smoke Test：

```bash
RUN_SMOKE=true ./bin/quick/check-project.sh
```

---

## 6. 接口 Smoke Test

```bash
./bin/quick/smoke-test.sh
```

指定服务地址：

```bash
BASE_URL=http://127.0.0.1:8080 ./bin/quick/smoke-test.sh
```

---

## 7. 底层多架构发布命令

正常情况下直接使用 `release.sh` 即可。如果需要自定义 Docker Namespace / Repository：

```bash
./bin/quick/docker-publish.sh 1.0.2 codercl openreach
```

代理环境：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-publish.sh 1.0.2 codercl openreach
```

发布后验证：

```bash
docker buildx imagetools inspect codercl/openreach:1.0.2
```

应同时看到：

```text
linux/amd64
linux/arm64
```

---

## 8. 普通用户一键部署

镜像发布完成后，普通用户无需 Maven/JDK/源码：

```bash
docker run -d \
  --name openreach \
  --restart unless-stopped \
  -p 8080:8080 \
  codercl/openreach:latest
```

Docker 会根据宿主机 CPU 自动选择 `linux/amd64` 或 `linux/arm64`。

---

## 命令速查

| 场景 | 命令 |
|---|---|
| 一键发布 | `./bin/quick/release.sh` |
| 代理一键发布 | `OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 ./bin/quick/release.sh` |
| Maven 打包 | `./bin/quick/package.sh` |
| 全量单测 | `./bin/quick/check-project.sh` |
| 本地镜像构建 | `./bin/quick/docker-build.sh` |
| 本地镜像启动验收 | `./bin/quick/docker-verify.sh` |
| 公网接口 Smoke Test | `./bin/quick/smoke-test.sh` |
| 查看远程架构 | `docker buildx imagetools inspect codercl/openreach:1.0.2` |

---

## 快速打 Tag + 推送远程仓库

以发布 `v1.0.2` 为例，在确认当前分支代码已经提交后执行：

```bash
# 1. 确认工作区与当前分支
git status
git branch --show-current

# 2. 创建带说明的 Release Tag
git tag -a v1.0.2 -m "OpenReach v1.0.2"

# 3. 推送当前分支到 origin
git push origin HEAD

# 4. 推送 Tag 到远程仓库
git push origin v1.0.2
```

也可以在 Tag 已创建后一次推送当前分支及其关联 Tag：

```bash
git push origin HEAD --follow-tags
```

> 发布新版本时只需把 `v1.0.2` 替换成目标版本号。若 Tag 已存在，先确认远端是否已经发布，不建议直接强制覆盖 Release Tag。
