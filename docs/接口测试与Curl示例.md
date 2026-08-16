# 接口测试与 Curl 示例

> 适用版本：**OpenReach v0.1.3**
>
> 默认服务地址：`http://localhost:8080`

本文档集中维护当前工程的手工接口验证样例。README 只保留快速入口；版本设计与测试验收口径见 [`设计方案/v1.0.1设计访问文档.md`](设计方案/v1.0.1设计访问文档.md) 与 [`设计方案/v0.1.2优化（安全+海外）文档.md`](设计方案/v0.1.2优化（安全+海外）文档.md)。

---

## 1. 启动前 Gate

### Java

```bash
mvn clean test
mvn spring-boot:run
```

### Skill

```bash
python3 -m unittest discover -s skills/openreach/tests -p 'test_*.py' -v
```

### Docker

```bash
docker run -d --name openreach -p 8080:8080 -e OPENREACH_LOG_PATH=/app/logs -v /data/openreach/logs:/app/logs codercl/openreach:latest
```

从源码本地构建验收：

```bash
./bin/quick/docker-verify.sh
```

---

## 2. 服务连通性 / 公网攻击面

OpenReach v0.1.3 继续不暴露独立 Health API。Skill `doctor` 使用只读官网根路径：

```bash
curl -fsSI 'http://localhost:8080/'
```

业务面严格只有三个 JSON POST API。以下请求应被拒绝：

```bash
curl -i 'http://localhost:8080/api/web/health'        # 404
curl -i 'http://localhost:8080/actuator/env'          # 404
curl -i -X PUT 'http://localhost:8080/api/web/search' # 405
curl -i -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: multipart/form-data; boundary=x'   # 415
```

---

## 3. Web Search

### 3.1 国内 Auto：CN Route

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"杭州 AI Agent 开源框架",
    "limit":5,
    "region":"CN",
    "provider":"auto"
  }'
```

默认链：

```text
CN
 -> bing (cn.bing.com)
 -> baidu
 -> sogou
 -> so360
 -> duckduckgo
```

### 3.2 海外 Auto：US → GLOBAL Route

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"latest Java AI Agent frameworks",
    "limit":5,
    "region":"US",
    "provider":"auto"
  }'
```

默认链：

```text
GLOBAL
 -> brave
 -> duckduckgo
 -> bing (www.bing.com)
```

### 3.3 中文 Query + US 仍走 GLOBAL

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"OpenAI 最新进展",
    "limit":5,
    "region":"US",
    "provider":"auto"
  }'
```

Route 由 `region` 决定，不根据 Query 语言猜测。

### 3.4 英文 Query + CN 仍走 CN

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"Spring Boot AI Agent",
    "limit":5,
    "region":"CN",
    "provider":"auto"
  }'
```

### 3.5 `region=auto`

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"OpenReach",
    "limit":5,
    "region":"auto",
    "provider":"auto"
  }'
```

默认：

```yaml
openreach.web.routing.default-route: cn
```

因此兼容 v1.0.1，仍走 CN。部署方可显式把默认 Route 改为 `global`。

### 3.6 显式 Brave

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"OpenReach AI Agent",
    "limit":5,
    "region":"US",
    "provider":"brave"
  }'
```

显式 Provider 不做 fallback，适合单渠道诊断。

### 3.7 显式 DuckDuckGo

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"OpenReach",
    "limit":5,
    "region":"GLOBAL",
    "provider":"duckduckgo"
  }'
```

v0.1.3 继续使用 HTML no-JS POST Form。若检测到 bot challenge，会返回 Provider 失败；`provider=auto` 时继续下一个渠道。

### 3.8 显式 Bing Global

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"OpenReach",
    "limit":5,
    "region":"US",
    "provider":"bing"
  }'
```

相同 `provider=bing` 会根据 `region` 自动选择：

```text
CN     -> cn.bing.com
GLOBAL -> www.bing.com
```

---

### 3.7 时间范围：最近一月

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"AI Agent release",
    "limit":5,
    "region":"US",
    "provider":"auto",
    "timeRange":"month"
  }'
```

支持 `any/day/week/month/year`。指定时间范围后，auto 只调用真正支持时间过滤的 Provider；显式指定不支持该能力的 Provider 应返回 400，而不是静默忽略。

---

## 4. Image Search

### 4.1 国内 Auto

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"杭州西湖夜景",
    "limit":8,
    "region":"CN",
    "provider":"auto"
  }'
```

```text
CN -> bing -> baidu -> sogou -> openverse
```

### 4.2 海外 Auto

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"Golden Gate Bridge",
    "limit":8,
    "region":"US",
    "provider":"auto"
  }'
```

```text
GLOBAL -> bing -> openverse -> wikimedia
```

核心返回：

```text
imageUrl
thumbnailUrl
sourcePageUrl
provider
source
domain
width
height
imageFormat
license
licenseUrl
```

### 4.3 Openverse

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"Mount Fuji",
    "limit":5,
    "region":"JP",
    "provider":"openverse"
  }'
```

### 4.4 Wikimedia Commons

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"Eiffel Tower",
    "limit":5,
    "region":"FR",
    "provider":"wikimedia"
  }'
```

Wikimedia 可返回 `license` / `licenseUrl` 时会统一映射；最终使用图片仍应回到 `sourcePageUrl` 核对许可。

### 4.5 Bing Global Images

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"Tokyo skyline",
    "limit":8,
    "region":"JP",
    "provider":"bing"
  }'
```

`JP` 属于 GLOBAL，因此 Bing 使用 `www.bing.com/images/async`，并传递 JP locale Hint。

---

## 5. Web Read

### 5.1 普通网页

```bash
curl -sS -X POST 'http://localhost:8080/api/web/read' \
  -H 'Content-Type: application/json' \
  -d '{
    "url":"https://spring.io/projects/spring-boot/",
    "maxChars":20000
  }'
```

### 5.2 SSRF 防护

以下请求应该被拒绝：

```bash
curl -i -X POST 'http://localhost:8080/api/web/read' \
  -H 'Content-Type: application/json' \
  -d '{
    "url":"http://127.0.0.1:8080",
    "maxChars":5000
  }'
```

---

## 6. Agent 使用闭环

网页：

```text
search(query, region)
  -> SearchItem.url
  -> read(url)
  -> Evidence / Citation / Answer
```

图片：

```text
image-search(query, region)
  -> imageUrl + sourcePageUrl
  -> read(sourcePageUrl)
  -> 图片上下文 / 来源证据 / License 核验
```

---

## 7. Smoke Test

一键：

```bash
./bin/quick/smoke-test.sh
```

脚本应覆盖 CN / GLOBAL 两条核心 Route；公网免费 Provider 属于 best-effort，因此 Smoke 结果用于判断当前部署网络与上游健康状态，**不替代离线单元测试**。
