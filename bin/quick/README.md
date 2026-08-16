# OpenReach 快捷命令

`bin/quick/` 维护 OpenReach 的打包、测试、Docker 本地构建、镜像验收和 Docker Hub 发布命令。当前 v0.1.3 的 `check-project.sh` / `package.sh` 会同时执行 Java JUnit 与 OpenReach Skill Python Test。

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
./bin/quick/release.sh 0.1.3
```

国内网络需要本地代理时（推荐）：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh 0.1.3
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

发布门禁说明：

- `OPENREACH_BUILD_PROXY` 只负责 Maven / Docker 构建网络代理，不会跳过测试；
- 任意 Java / Skill 单测失败都会在 Docker Push 前立即停止；
- `release.sh` 会校验传入版本与 `pom.xml` 版本一致，`v0.1.3` 与 `0.1.3` 均可；
- 如果 Maven 已进入 `TESTS` 阶段后失败，优先处理具体失败用例，而不是继续排查代理。
- v0.1.3 的 `RequestTraceFilterTest` 已按真实 `@RequestBody` 生命周期消费请求流，避免 `ContentCachingRequestWrapper` 因 Mock 链未读取 body 而产生请求 Payload 空值的误报。

### 1.3 发布后验证

确认远程镜像两个架构都在：

```bash
docker buildx imagetools inspect codercl/openreach:0.1.3
```

普通用户部署（无需 Maven/JDK/源码）：

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
./bin/quick/docker-publish.sh 0.1.3 codercl openreach
```

代理环境：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/docker-publish.sh 0.1.3 codercl openreach
```

发布后验证：

```bash
docker buildx imagetools inspect codercl/openreach:0.1.3
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
| Tool Runner -> OpenReach 连接诊断 | `BASE_URL=<reachable-url> ./bin/quick/connectivity-test.sh` |
| 应用自身 QPS 基准 | `./bin/quick/qps-unit-test.sh` |
| 真实上游 QPS 压测 | `./bin/quick/qps-test.sh` |
| 查看远程架构 | `docker buildx imagetools inspect codercl/openreach:0.1.3` |
| 查看 API 日志 | `OPENREACH_LOG_DIR=/data/openreach/logs ./bin/quick/logs.sh api` |
| 按 Trace 排障 | `OPENREACH_LOG_DIR=/data/openreach/logs ./bin/quick/logs.sh trace <traceId>` |

---

---

## 9. Docker 日志持久化与 Trace 排障

推荐线上固定宿主机目录：

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
  codercl/openreach:0.1.3
```

日志文件：

```text
openreach.log          完整应用日志
openreach-api.log      API request_start/request_end/request_failed
openreach-upstream.log Search/Image/Read 上游请求全过程
archive/               按日期 + 大小滚动压缩归档
```

接口返回头和错误 JSON 都包含 Trace ID。定位一条失败请求：

```bash
TRACE_ID=req-20260815T175351289-8f31a4c2
grep "$TRACE_ID" /data/openreach/logs/openreach*.log

# 或使用项目内置日志助手
OPENREACH_LOG_DIR=/data/openreach/logs ./bin/quick/logs.sh trace "$TRACE_ID"
```

详细设计见：

```text
docs/设计方案/v0.1.2请求异常诊断与日志可观测优化方案.md
```

## 快速打 Tag + 推送远程仓库

以发布 `v0.1.3` 为例，在确认当前分支代码已经提交后执行：

```bash
# 1. 确认工作区与当前分支
git status
git branch --show-current

# 2. 创建带说明的 Release Tag
git tag -a v0.1.3 -m "OpenReach v0.1.3"

# 3. 推送当前分支到 origin
git push origin HEAD

# 4. 推送 Tag 到远程仓库
git push origin v0.1.3
```

也可以在 Tag 已创建后一次推送当前分支及其关联 Tag：

```bash
git push origin HEAD --follow-tags
```

> 发布新版本时只需把 `v0.1.3` 替换成目标版本号。若 Tag 已存在，先确认远端是否已经发布，不建议直接强制覆盖 Release Tag。

---

## 10. 并发 QPS 压测

### 10.1 推荐：应用自身 HTTP QPS 基准单测

该测试会启动真实 Spring Boot HTTP 服务和完整 Filter / Trace / JSON / SearchService 链路，但使用**内存 benchmark Provider**，不会请求 Bing、百度、Brave、DuckDuckGo 等公网渠道，因此适合测 OpenReach 自身吞吐，不会触发第三方反爬。

```bash
./bin/quick/qps-unit-test.sh
```

默认参数：

```text
每档请求数: 500
预热请求:   50
并发档位:   1,4,8,16,32
Provider延迟: 0ms
日志级别:   INFO（保留当前生产日志成本）
```

自定义：

```bash
REQUESTS_PER_LEVEL=2000 \
CONCURRENCY_LEVELS=1,8,16,32,64,128 \
PROVIDER_DELAY_MS=20 \
./bin/quick/qps-unit-test.sh
```

如果希望在固定压测机器上增加可选峰值 QPS 回归门槛：

```bash
MIN_PEAK_QPS=500 \
REQUESTS_PER_LEVEL=3000 \
CONCURRENCY_LEVELS=8,16,32,64 \
./bin/quick/qps-unit-test.sh
```

默认 `MIN_PEAK_QPS=0`，只报告、不阻断普通构建。

如果希望单独观察关闭高频 API/Upstream INFO 日志后的吞吐上限：

```bash
QPS_API_LOG_LEVEL=WARN \
QPS_UPSTREAM_LOG_LEVEL=WARN \
REQUESTS_PER_LEVEL=2000 \
CONCURRENCY_LEVELS=1,8,16,32,64 \
./bin/quick/qps-unit-test.sh
```

报告输出：

```text
target/qps/openreach-qps-report.md
target/qps/openreach-qps-report.csv
```

压测期间的 Logback 文件默认单独写入：

```text
target/qps/logs/
```

可通过 `QPS_LOG_PATH` 覆盖，避免污染项目根目录的运行日志。

指标包含：Concurrency、Requests、Success、Fail、Duration、QPS、Avg、P50、P95、P99、Max、HTTP Status。

### 10.2 已启动服务：真实上游 QPS

下面命令会真正调用当前 OpenReach 的 `/api/web/search`，因此 QPS 会同时受公网延迟、搜索渠道限流、反爬、出口 IP 和网络质量影响：

```bash
BASE_URL=http://127.0.0.1:8080 \
TOTAL_REQUESTS=50 \
CONCURRENCY=5 \
./bin/quick/qps-test.sh
```

报告：

```text
target/qps/openreach-real-qps-report.json
target/qps/openreach-real-qps-failures.json   # 仅失败时生成，包含 traceId 样本
```

> 公网渠道压测建议从 5 并发、50 请求开始，不建议直接用几十/上百并发轰免费搜索 Provider。应用自身性能请优先使用 `qps-unit-test.sh`。


---

## 11. Tool Runner -> OpenReach 连接诊断

当 AgentHub / Sandbox / HTTP Tool 出现裸错误：

```text
All connection attempts failed
```

且没有 OpenReach `traceId` 时，不要先调 Search Provider。应从**实际 Tool Runner 环境**测试 `BASE_URL`：

```bash
BASE_URL=http://openreach:8080 \
./bin/quick/connectivity-test.sh
```

脚本检查 DNS -> TCP -> 官网 -> 无上游 API Probe。若在容器中配置 `localhost/127.0.0.1`，脚本会提示该地址指向调用容器自身。

同 Docker Network 时通常使用 OpenReach Service/Container DNS；不同网络时使用调用方真实可达的宿主机 IP / 域名 / 反向代理地址。
