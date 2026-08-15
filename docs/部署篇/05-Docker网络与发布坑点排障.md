# Docker 网络与发布坑点排障

本文汇总 OpenReach 在国内网络 + 本地代理（Clash / 127.0.0.1:7891）环境下，Docker 拉取、登录与多架构发布过程中常见的坑点，以及对应的诊断与修复方法。

## 1. 坑点速查表

| # | 现象 | 根因 | 快速修复 |
|---|------|------|----------|
| 1 | `docker login` 报 `certificate is valid for *.facebook.com, not registry-1.docker.io` | 宿主机 DNS 被劫持，`registry-1.docker.io` 解析到 Facebook IP | 给 Docker daemon 配置代理（见第 2 节） |
| 2 | 容器内经 `host.docker.internal:7891` 请求返回 `502 Bad Gateway` | 代理对明文 HTTP 请求的响应，CONNECT 隧道本身正常 | 无需处理，CONNECT 可用即可 |
| 3 | `buildx build --push` 报 `insufficient_scope: authorization failed` | 未登录 Docker Hub 或账号无该仓库权限 | `docker login` 后重试（见第 3 节） |
| 4 | 发布到旧 Namespace `changlu/openreach` | 脚本默认 Namespace 与账号不一致 | 统一改为 `codercl`（见第 4 节） |

## 2. 坑点 1：DNS 劫持导致 facebook.com 证书错误

### 2.1 现象

```text
$ docker login
Error response from daemon: Get "https://registry-1.docker.io/v2/":
tls: failed to verify certificate: x509: certificate is valid for
*.facebook.com, *.facebook.net, *.fbcdn.net, ...,
not registry-1.docker.io
```

### 2.2 根因

`registry-1.docker.io` 的 DNS 被劫持，解析到了一个 Facebook IP：

```bash
nslookup registry-1.docker.io
# Name:   registry-1.docker.io
# Address: 31.13.84.34   <- Facebook IP 段
```

Docker daemon 直连时走被污染的 DNS → TLS 握手连到 Facebook 服务器 → 拿到的是 facebook.com 证书。

### 2.3 诊断

宿主机器直接连接会超时 / 证书错误，但经代理可通：

```bash
# 直接连接：失败（超时或证书错误）
curl https://registry-1.docker.io/v2/

# 经代理：返回 401 Unauthorized，说明链路已通
curl -x http://127.0.0.1:7891 https://registry-1.docker.io/v2/
```

返回 `401 Unauthorized` 反而是好事——Registry 链路已通，只差认证。

### 2.4 修复

在 Docker daemon 配置里强制走宿主代理。OrbStack 下修改 `~/.orbstack/config/docker.json`，增加 `proxies`：

```json
{
  "proxies": {
    "http-proxy": "http://host.docker.internal:7891",
    "https-proxy": "http://host.docker.internal:7891",
    "no-proxy": "localhost,127.0.0.1,::1"
  }
}
```

注意：

- 容器 / daemon 内 `127.0.0.1` 指向自身，必须用 `host.docker.internal` 才能回到宿主机代理。
- 宿主机（Maven、Buildx Client）仍用 `127.0.0.1:7891`。

改完重启 Docker 引擎使配置生效：

```bash
orb restart docker

# 确认代理已注入
docker info | grep -iE "HTTP Proxy|HTTPS Proxy|No Proxy"
# HTTP Proxy: http://host.docker.internal:7891
# HTTPS Proxy: http://host.docker.internal:7891
```

### 2.5 验证

```bash
docker pull hello-world
# Pull complete ...
```

## 3. 坑点 2：容器内经代理请求返回 502

### 3.1 现象

```bash
docker run --rm alpine sh -c \
  'export https_proxy=http://host.docker.internal:7891; \
   wget -q -S -O /dev/null https://registry-1.docker.io/v2/'
# HTTP/1.1 502 Bad Gateway
```

### 3.2 结论

部分代理（如 Clash）对走它的明文 HTTP 请求会回 502，但 CONNECT 隧道是正常的：

```bash
printf "CONNECT registry-1.docker.io:443 HTTP/1.1\r\nHost: registry-1.docker.io:443\r\n\r\n" \
  | nc host.docker.internal 7891
# HTTP/1.1 200 Connection established
```

因此容器内经代理访问 HTTPS 主要看 CONNECT 是否 200，不必纠结 GET 的 502。

## 4. 坑点 3：Push 失败 insufficient_scope

### 4.1 现象

```text
ERROR: failed to solve: failed to push changlu/openreach:0.1.0:
push access denied, repository does not exist or may require authorization:
server message: insufficient_scope: authorization failed
```

### 4.2 根因

- 拉取（pull）是匿名可用的，代理修好就能成功；
- 推送（push）必须登录，且登录账号对该 Namespace 有权限；
- `insufficient_scope` = 当前凭据没有 push 权限（未登录 / 密码错误 / 账号不属于该仓库）。

### 4.3 修复

```bash
docker login
# 用户名 = 仓库 Namespace 对应的 Docker ID（例如 codercl）
```

### 4.4 验证

- 登录失败会明确提示 `unauthorized: incorrect username or password`，此时链路正常，只是凭据不对。
- 登录成功后重新执行：

```bash
OPENREACH_BUILD_PROXY=http://127.0.0.1:7891 \
./bin/quick/release.sh 0.1.0
```

## 5. 坑点 4：发布到旧 Namespace

### 5.1 现象

发布脚本默认使用 `changlu/openreach`，与真实 Docker ID（`codercl`）不一致，push 授权失败。

### 5.2 修复

已统一为 `codercl`，涉及文件：

| 文件 | 内容 |
|------|------|
| `bin/quick/release.sh` | 默认 Namespace 改为 `codercl` |
| `bin/quick/docker-publish.sh` | 默认 Namespace 改为 `codercl` |
| `docker-compose.yml` | `OPENREACH_IMAGE` 默认值改为 `codercl/openreach:latest` |
| `README.md`、`bin/quick/README.md` | 文档示例统一 |
| `docs/部署篇/01~04`、`docs/接口测试与Curl示例.md` | 文档示例统一 |

说明：Java 包名 `io.github.changlu`（pom.xml 的 groupId）是 Maven 坐标，与 Docker Namespace 无关，未改动。

如需临时发布到其它 Namespace，脚本支持显式传参：

```bash
./bin/quick/docker-publish.sh 0.1.0 <namespace> openreach
```

## 6. 最佳实践与预防

1. **DNS 污染优先用代理绕行**：不要尝试给 `/etc/hosts` 硬编码 Registry IP（CDN 地址会变，且各处污染源不同）。正确做法是让 daemon 走 `host.docker.internal:7891` 代理。
2. **区分 127.0.0.1 的语义**：
   - 宿主机进程（Maven、Buildx Client）→ `127.0.0.1:7891`；
   - BuildKit / 容器内 → `host.docker.internal:7891`。
3. **区分 pull / push 的鉴权差异**：pull 匿名可用，push 必须 `docker login`。push 失败先确认登录状态，再看 Namespace 拼写。
4. **Proxy 连通性自检**：

```bash
# 链路自检：401 = 链路通，仅差认证
curl -x http://127.0.0.1:7891 https://registry-1.docker.io/v2/

# 认证自检：出现"incorrect username or password"= 凭据问题而非网络问题
docker login -u <user> --password-stdin <<< "test"
```

5. **多架构发布前先验证凭据**：`docker login` 成功后，先单独 `docker push` 一个测试镜像确认权限，再跑完整 `release.sh`，避免最后阶段才发现权限不足。
