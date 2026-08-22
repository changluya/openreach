# OpenReach 接口文档

> 适用版本：**v0.1.4** · 本机直接运行示例：`http://localhost:8080`；AgentHub / Tool Runner 必须配置其运行环境真实可达的 `BASE_URL`

本文档基于当前工程源码（`io.github.changlu.openreach`）维护 OpenReach 对外 HTTP 接口的完整说明，面向 Agent 集成方、HTTP 插件开发者和 API 调用方。

---

## 1. 接口总览

| 能力 | 方法 | 路径 | 说明 |
|---|---|---|---|
| Web Search | `POST` | `/api/web/search` | 网页搜索，多 Provider 自动降级 |
| Image Search | `POST` | `/api/web/image-search` | 文搜图，多图片 Provider 自动降级 |
| Web Read | `POST` | `/api/web/read` | 读取网页正文，内置 SSRF 防护 |
| Safe Curl | `POST` | `/api/web/curl` | 读取公开 API / JSON / raw 源码，GET/HEAD only + SSRF/Self Guard |

Skill 判断是否已初始化时使用本地 `check`：先检查 Skill `config.json`，存在时仅执行 1 次 `POST /api/web/search` 空 JSON 探测并期待本地校验返回 `400 / VALIDATION_ERROR`，不会触发真实搜索。`doctor` 仅保留为人工官网 `GET /` 排障能力；两者都不新增额外业务 API。

Controller 定义位于 `src/main/java/io/github/changlu/openreach/web/WebCapabilityController.java`。

四个能力完全解耦，均为无需 Search API Key 的公开 HTTP 接口。安全 Filter 只允许这四个精确 POST 路径，Content-Type 仅接受 `application/json` / `application/*+json`；Multipart/文件上传、未知 API、危险 Method 均拒绝。

---

## 2. 通用约定

### 2.1 请求

- `Content-Type: application/json`
- 请求体为 JSON 对象；仅 `query` / `url` 必填（见各接口说明）
- 四个业务 API 的请求体统一受 `openreach.web.security.max-api-body-bytes` 限制，默认 **65536 bytes（64 KiB）**；包括无法提前获得 Content-Length 的请求也会在真实读取时限流
- 只接受 `application/json` 或 `application/*+json`；`multipart/*` 明确拒绝
- 未识别的字段会被忽略；可空字段不传即使用默认值

### 2.2 响应

- 成功响应直接返回业务对象（**无外层包装**），HTTP `200`
- 失败响应统一为错误对象，结构见 [第 7 章](#7-错误码与公共错误响应)

### 2.3 字段空值规则

响应为 Java `record` 序列化，上游未提供的字段以 `null` 输出（例如 ImageSearchItem 的 `width`、`license`）。JSON 对象字段按声明顺序输出。

### 2.4 时间与单位

- `latencyMs`：本次请求整体耗时（毫秒）
- `count`：`items` 的实际条数

---

## 3. Web Search — `POST /api/web/search`

### 3.1 请求参数

请求体（`application/json`）：

| 字段 | 类型 | 必填 | 默认值 | 校验约束 | 说明 |
|---|---|---|---|---|---|
| `query` | string | 是 | - | `@NotBlank` `@Size(max=500)` | 搜索关键词或自然语言问题，最长 500 字符 |
| `limit` | int | 否 | `10` | `@Min(1)` `@Max(20)` | 最多返回条数，服务端还会受配置 `max-results`（默认 20）钳制 |
| `region` | string | 否 | `auto` | `@Size(max=32)` | 核心路由 + Provider Locale Hint；CN aliases 走 CN，其他显式地区走 GLOBAL |
| `provider` | string | 否 | `auto` | `@Size(max=32)` | 渠道，见 [第 8 章 Provider 矩阵](#81-web-search) |
| `timeRange` / `time_range` | string | 否 | `any` | `@Size(max=32)` | 两种字段名等价；`any/day/week/month/year`；兼容 `all/none/off/0`、`d/w/m/y`、`1d/1w/1m/1y`、`past_*`、`pd/pw/pm/py`、`qdr:*` |

校验失败返回 `400 VALIDATION_ERROR`（如 `query` 为空）。

### 3.2 响应字段

成功响应 HTTP `200`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider` | string | 请求最终采用的 provider 模式；`provider=auto` 时仍返回 `auto`，具体结果渠道看每条 item 的 `source` |
| `query` | string | 回显原始查询词 |
| `region` | string | 生效区域（未传时为 `auto`） |
| `timeRange` | string | 规范化后的时间范围 |
| `count` | int | `items` 条数 |
| `latencyMs` | long | 请求耗时（毫秒） |
| `items` | array | 搜索结果列表，见 `SearchItem` |

**SearchItem 字段：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `rank` | int | 排名，从 1 开始连续编号 |
| `title` | string | 标题 |
| `url` | string | 结果 URL |
| `snippet` | string | 摘要（渠道可提供时） |
| `source` | string | 结果来源标记 |

### 3.3 行为说明

- `provider=auto`：先由 `SearchRouteResolver` 根据 `region` 选择 Route，再由 `ProviderChainResolver` 选择对应免费 Provider Chain：
  - `CN / zh-CN / zh_CN / cn-zh / zh-Hans-CN / china` → `CN` → `bing → baidu → sogou → so360 → duckduckgo`；
  - `US / JP / SG / GB / GLOBAL / wt-wt ...` 等其他显式地区 → `GLOBAL` → `brave → duckduckgo → bing`；
  - `auto` / 空值 → `openreach.web.routing.default-route`，默认仍为 `CN`。
- Auto Chain 中任一渠道失败、返回 null/空结果、解析失败时不中断，继续后续渠道；结果按 URL 规范化后去重聚合，达到 `limit` 提前结束。
- `provider=<具体渠道>`：显式 Provider 优先级最高，仅请求指定渠道，不进入 Route Chain fallback；`region` 仍作为该 Provider 的 locale/country Hint。
- 所有渠道均失败且无任何结果时返回 `502 UPSTREAM_ERROR`；Route Provider Order 为空时也返回清晰的 `UPSTREAM_ERROR`。
- 返回前会对 `items` 重新编号（`rank` 从 1 连续）。
- `timeRange!=any` 时，auto 使用独立时间过滤链：CN 默认 `baidu -> bing -> duckduckgo -> brave`、GLOBAL 默认 `bing -> brave -> duckduckgo -> baidu`。能力判断细化到具体范围：百度免费 Web 支持 `day/week/month/year`；Bing 免费 Web 已验证 `day/week/month`，`year` 不会伪造支持；Brave / DuckDuckGo 支持完整范围。显式 Provider 不支持指定范围时返回 `400 BAD_REQUEST`。

### 3.4 示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{"query":"Spring Boot AI Agent","limit":5,"region":"US","provider":"auto","timeRange":"month"}'
```

```json
{
  "provider": "auto",
  "query": "Spring Boot AI Agent",
  "region": "US",
  "timeRange": "month",
  "count": 5,
  "latencyMs": 2453,
  "items": [
    {
      "rank": 1,
      "title": "Spring Boot",
      "url": "https://spring.io/projects/spring-boot/",
      "snippet": "Spring Boot makes it easy to create stand-alone...",
      "source": "bing"
    }
  ]
}
```

---

## 4. Image Search — `POST /api/web/image-search`

### 4.1 请求参数

请求体（`application/json`）：

| 字段 | 类型 | 必填 | 默认值 | 校验约束 | 说明 |
|---|---|---|---|---|---|
| `query` | string | 是 | - | `@NotBlank` `@Size(max=500)` | 文搜图关键词或自然语言描述，最长 500 字符 |
| `limit` | int | 否 | `10` | `@Min(1)` `@Max(30)` | 最多返回条数，服务端还会受配置 `max-results`（默认 30）钳制 |
| `region` | string | 否 | `auto` | `@Size(max=32)` | 与 Web Search 共用 CN / GLOBAL 路由规则，并作为图片 Provider Locale Hint |
| `provider` | string | 否 | `auto` | `@Size(max=32)` | 渠道，见 [第 8 章 Provider 矩阵](#82-image-search) |

### 4.2 响应字段

成功响应 HTTP `200`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider` | string | 请求最终采用的 provider 模式；`provider=auto` 时仍返回 `auto`，具体图片渠道看每条 item 的 `provider` / `source` |
| `query` | string | 回显原始查询词 |
| `region` | string | 生效区域 |
| `count` | int | `items` 条数 |
| `latencyMs` | long | 请求耗时（毫秒） |
| `items` | array | 图片结果列表，见 `ImageSearchItem` |

**ImageSearchItem 字段：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `rank` | int | 排名，从 1 开始连续编号 |
| `title` | string | 图片标题 |
| `imageUrl` | string | 响应生成时已验证可直接下载的原图 URL |
| `thumbnailUrl` | string | 缩略图 URL（渠道可提供时） |
| `sourcePageUrl` | string | 图片来源页面 |
| `provider` | string | 产出该结果的渠道名（如 `bing`） |
| `source` | string | 来源标记 |
| `domain` | string | 来源站点域名 |
| `width` | int / null | 原图宽度（渠道可提供时） |
| `height` | int / null | 原图高度（渠道可提供时） |
| `imageFormat` | string | 图片格式（如 jpeg/png，渠道可提供时） |
| `license` | string | 许可协议（Openverse / Wikimedia 等上游可提供时） |
| `licenseUrl` | string | 许可协议链接（Openverse / Wikimedia 等上游可提供时） |

> 注意：即使结果携带 `license` / `licenseUrl`，仍应以来源页面为准确认最终使用条件。

### 4.3 行为说明

- `provider=auto`：复用 `SearchRouteResolver + ProviderChainResolver`：
  - CN → `bing → baidu → sogou → openverse`；
  - GLOBAL → `bing → openverse → wikimedia`。
- Provider 会多取候选，Service 对每个候选原图执行 `SecureImageDownloadVerifier`；只有经过公网 SSRF/Redirect、HTTP 2xx 与真实图片字节签名校验的 `imageUrl` 才进入响应。
- 候选数量默认按 `limit × 3` 放大，最多验证 60 个；下载验证默认超时 4 秒、最多 3 次重定向、读取最多 65536 bytes，并发 6、队列容量 48。以上均可配置。
- 当前签名白名单覆盖 JPEG / PNG / GIF / WebP / BMP / TIFF / ICO / AVIF / HEIC；HTML/XML/SVG 等主动或非图片内容不会进入响应。
- 单渠道失败、null/空结果或“有候选但全部不可下载”均不中断 auto；继续下一 Provider。按 `imageUrl` 去重聚合，达到 `limit` 提前结束。
- `provider=<具体渠道>`：仅请求指定渠道，不做 fallback；渠道不存在返回 `400 BAD_REQUEST`，返回空结果返回 `502 UPSTREAM_ERROR`。
- Bing Images 根据 Route 自动选择 `cn.bing.com/images/async` 或 `www.bing.com/images/async`。
- 所有渠道均失败且无任何结果时返回 `502 UPSTREAM_ERROR`；返回前统一重编号。

### 4.4 示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{"query":"杭州西湖夜景","limit":8,"region":"auto","provider":"auto"}'
```

```json
{
  "provider": "auto",
  "query": "杭州西湖夜景",
  "region": "auto",
  "count": 8,
  "latencyMs": 1876,
  "items": [
    {
      "rank": 1,
      "title": "西湖夜景",
      "imageUrl": "https://example.com/photo.jpg",
      "thumbnailUrl": "https://example.com/photo_th.jpg",
      "sourcePageUrl": "https://example.com/gallery/1",
      "provider": "bing",
      "source": "bing",
      "domain": "example.com",
      "width": 1920,
      "height": 1080,
      "imageFormat": "jpeg",
      "license": null,
      "licenseUrl": null
    }
  ]
}
```

---

## 5. Web Read — `POST /api/web/read`

### 5.1 请求参数

请求体（`application/json`）：

| 字段 | 类型 | 必填 | 默认值 | 校验约束 | 说明 |
|---|---|---|---|---|---|
| `url` | string | 是 | - | `@NotBlank` `@Size(max=2048)` | 需要读取的公网 HTTP/HTTPS **文本网页**地址，最长 2048 字符；禁止私网/localhost/内部附件 URL、非 80/443 与图片/压缩包等二进制 URL |
| `maxChars` / `max_chars` | int | 否 | `50000` | `@Min(1000)` `@Max(200000)` | 两种字段名等价；最多返回的正文字符数；未传使用服务端配置 `read.max-chars`（默认 50000） |

### 5.2 响应字段

成功响应 HTTP `200`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | string | 请求时传入的原始 URL |
| `finalUrl` | string | 重定向后的最终 URL |
| `title` | string | 页面标题（优先 `og:title`，其次 `<title>`） |
| `content` | string | 提取出的主要正文，超限时按 `maxChars` 截断 |
| `contentType` | string | 上游响应 `Content-Type` |
| `reader` | string | 使用的 Reader 实现名（当前为 `jsoup`） |
| `truncated` | boolean | 正文是否因超出长度限制被截断 |
| `latencyMs` | long | 请求耗时（毫秒） |
| `metadata` | object | 页面元数据（键为 `description` / `author` / `publishedAt`，缺失的键不出现） |
| `links` | array | 正文范围内的链接（绝对 URL，最多 100 个） |

**metadata 可选键：**

| 键 | 来源 |
|---|---|
| `description` | `meta[name=description]` 或 `meta[property=og:description]` |
| `author` | `meta[name=author]` 或 `meta[property=article:author]` |
| `publishedAt` | `meta[property=article:published_time]` 或 `time[datetime]` |

### 5.3 行为与安全规则

- **内容类型限制**：仅接受 `text/html`、`application/xhtml+xml`、`text/plain`，其他类型返回 `400 BAD_REQUEST`（V1 不读取 PDF / Office / 图片）。
- **SSRF 防护**（`UrlSafetyGuard`）：拒绝非 `http/https` 协议、带 user-info、host 缺失的 URL；拒绝 `localhost` / `*.localhost` / `*.local` 主机；解析出的 IP 若为回环、私网、链路本地、多播、IPv6 唯一本地地址（`fc00::/7`）则拒绝；解析失败拒绝。
- **重定向校验**：每个重定向目标都会重新经过 SSRF 校验，最多跟随 `read.max-redirects`（默认 5）次。
- **响应体大小限制**：响应体超过 `read.max-bytes`（默认 5 MiB）返回 `400 BAD_REQUEST`。
- **正文提取**：基于 Jsoup 移除 `script/style/nav/footer/header/aside/form/广告与弹窗` 等噪音节点，对 `article/main/#content/.content` 等候选根节点评分，取最优区域输出可读文本；文本长于 `maxChars` 时截断并置 `truncated=true`。
- **浏览器兼容 Header**：Read GET 会携带无 Cookie、无 Authorization 的浏览器导航兼容 Header，提升普通公开站点兼容性；不会模拟登录态或绕过验证码/WAF。
- **有界重试**：网络 I/O 与 `408/425/500/502/503/504/520~524` 等瞬时故障最多按 `read.max-attempts` 尝试；`401/403/412/429` 不自动重试。

### 5.4 示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/read' \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://spring.io/projects/spring-boot/","maxChars":20000}'
```

```json
{
  "url": "https://spring.io/projects/spring-boot/",
  "finalUrl": "https://spring.io/projects/spring-boot/",
  "title": "Spring Boot",
  "content": "Spring Boot makes it easy to create stand-alone, production-grade Spring based Applications...",
  "contentType": "text/html;charset=utf-8",
  "reader": "jsoup",
  "truncated": false,
  "latencyMs": 812,
  "metadata": {
    "description": "Spring Boot makes it easy to create stand-alone, production-grade Spring based Applications that you can just run."
  },
  "links": [
    "https://spring.io/",
    "https://github.com/spring-projects/spring-boot"
  ]
}
```

> `metadata` 仅输出实际提取到的键；未提取到的 `author` / `publishedAt` 等键不会占位输出。

---

### 5.5 公网安全基线

- 业务接口精确 allowlist：`POST /api/web/search`、`POST /api/web/image-search`、`POST /api/web/read`、`POST /api/web/curl`；
- Spring Multipart 全局关闭，Filter 明确拒绝 `multipart/*`；
- API 请求体默认最大 64 KiB，且 chunked/未知长度请求也会在读取阶段执行同一硬上限；
- 未知路径、Upload/Actuator/Debug、危险 Method、Path Traversal 提前拒绝；
- Read/Image/Curl remote fetch 仅公网 HTTP/HTTPS 80/443，并对 Redirect 每跳复检；Curl 额外禁止请求 OpenReach 自身，图片结果还必须通过被动图片 Magic Bytes 验证；
- 官网静态资源仅来自 classpath，并配置 CSP / nosniff / DENY frame 等 Header。

## 6. Safe Curl — `POST /api/web/curl`

### 6.1 请求参数

| 字段 | 类型 | 必填 | 默认值 | 校验约束 | 说明 |
|---|---|---|---|---|---|
| `url` | string | 是 | - | `@NotBlank` `@Size(max=2048)` | 公网 HTTP/HTTPS URL，仅 80/443；适合 GitHub API、raw 源码、JSON/text API |
| `method` | string | 否 | `GET` | `GET/HEAD` | 只读 Method，不支持 POST/PUT/PATCH/DELETE |
| `headers` | object | 否 | `{}` | 最多 16 个 | 可传普通内容协商 Header；禁止 Authorization/Cookie/Host/Forwarded/X-Forwarded-* 等敏感或代理欺骗头 |
| `maxChars` / `max_chars` | int | 否 | `100000` | 1000-200000 | 最多返回字符数；同时受服务端 `curl.max-chars` 限制 |

### 6.2 响应字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | string | 原始目标 URL |
| `finalUrl` | string | 重定向后的最终 URL |
| `method` | string | 实际 Method |
| `statusCode` | int | 上游 HTTP 状态码；Curl 本身成功执行时会原样返回该状态值供 Agent 判断 |
| `contentType` | string | 上游 Content-Type |
| `body` | string | JSON/text/source 响应正文 |
| `truncated` | boolean | 是否因 `maxChars` 截断 |
| `redirects` | int | 已跟随重定向次数 |
| `latencyMs` | long | 总耗时 |
| `headers` | object | 经过裁剪的响应 Header；`Set-Cookie` 不返回 |

### 6.3 GitHub 源码阅读示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/curl' \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://api.github.com/repos/spring-projects/spring-boot","method":"GET"}'
```

```bash
curl -sS -X POST 'http://localhost:8080/api/web/curl' \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.adoc"}'
```

推荐 Agent 链路：`search(site:github.com ...) -> curl(api.github.com/...) -> curl(raw.githubusercontent.com/...)`。GitHub 匿名 API 的官方限流保持原样；OpenReach 不绕过限流，也不允许通过 Curl 注入 `Authorization`。需要私有仓库或认证额度时应使用专用 GitHub Connector。

### 6.4 Curl 安全边界

- 共享 `UrlSafetyGuard`：拒绝 localhost、RFC1918、link-local、metadata、保留地址、非 HTTP(S)；
- Curl 再强制端口锁定为 `80/443`，即使 Read 的端口配置被部署方放宽也不会跟随放宽；
- **禁止请求 OpenReach 自身**：目标 Host 与当前入站 `Host/serverName/localName` 比较，并将目标解析 IP 与 `localAddr`、本机/容器网卡地址比较；
- `OPENREACH_CURL_BLOCKED_HOSTS` 可补充同一部署的其他公网域名/别名，支持 `*.example.com`；
- 每次重定向重新执行全部 SSRF + Self Target 校验；
- 仅 GET/HEAD、仅文本/API/source 内容；拒绝凭据 Header、代理伪造 Header、写 Method 与二进制下载。

---

## 7. 错误码与公共错误响应

Controller / Service 层错误由 `GlobalExceptionHandler` 生成，包含 `timestamp` 与 `traceId`；所有请求响应头同时返回 `X-OpenReach-Trace-Id`：

```json
{
  "timestamp": "2026-08-15T02:00:00.000Z",
  "status": 400,
  "code": "BAD_REQUEST",
  "traceId": "req-20260815T175351289-8f31a4c2",
  "message": "Only http/https URLs are allowed"
}
```

安全 Filter 在进入 Spring MVC 之前直接拒绝的请求（如 404 / 405 / 413 / 415）使用更精简的结构，不包含 `timestamp`：

```json
{
  "status": 415,
  "code": "UPLOAD_DISABLED",
  "traceId": "req-20260815T175351289-8f31a4c2",
  "message": "File upload is disabled"
}
```

| HTTP 状态 | `code` | 触发场景 |
|---|---|---|
| `400` | `BAD_REQUEST` | 业务参数非法（如 URL 非法 / 不支持渠道 / SSRF 拦截 / 内容类型不支持 / 响应体超限） |
| `400` | `VALIDATION_ERROR` | Bean Validation 校验失败（`query` 空或超 500、`limit` 越界、`url` 超 2048、`maxChars` 越界等） |
| `400` | `INVALID_JSON` | JSON 语法错误、类型不匹配或请求体无法反序列化 |
| `404` | `NOT_FOUND` | 未在公网 Allowlist 中的路径、未知静态/框架端点 |
| `405` | `METHOD_NOT_ALLOWED` | API 使用非 POST，或静态资源使用非 GET/HEAD |
| `413` | `PAYLOAD_TOO_LARGE` | JSON 请求体超过 `max-api-body-bytes`，默认 64 KiB |
| `415` | `UPLOAD_DISABLED` | 请求使用 `multipart/*`，文件上传被明确关闭 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 四个 API 未使用 `application/json` / `application/*+json` |
| `502` | `UPSTREAM_ERROR` | 上游全部渠道失败 / 指定渠道无结果 / 无可下载图片 / 读取网页失败 / 重定向过多 / 上游非 2xx |

Read 遇到明确的上游 HTTP 状态时，会在保持 `code=UPSTREAM_ERROR` 兼容的前提下增加：`failureType`、`upstreamStatus`、`retryable`。例如目标站返回 412：

```json
{
  "status": 502,
  "code": "UPSTREAM_ERROR",
  "message": "Upstream returned HTTP 412",
  "failureType": "HTTP_412",
  "upstreamStatus": 412,
  "retryable": false
}
```

调用方约定：`HTTP_403/HTTP_412 + retryable=false` 不应重试同一 URL，应切换公开来源；`HTTP_521` 等瞬时 5xx 由 OpenReach 先做有界 GET 重试。
| `500` | `INTERNAL_ERROR` | 未预期的服务端异常；客户端只收到固定安全消息，不回显内部堆栈 |

---

## 8. Provider 矩阵

### 8.1 Web Search

| 渠道 | `provider` 值 | CN Auto | GLOBAL Auto | 接入形式 | API Key |
|---|---|---:|---:|---|---:|
| Bing | `bing` | 1 | 3 | CN / Global HTML SERP | 否 |
| 百度 | `baidu` | 2 | - | HTML SERP | 否 |
| 搜狗 | `sogou` | 3 | - | HTML SERP | 否 |
| 360 搜索 | `so360` | 4 | - | HTML SERP | 否 |
| DuckDuckGo | `duckduckgo` | 5 | 2 | HTML no-JS POST Form | 否 |
| Brave Web | `brave` | - | 1 | 公开 Web SERP | 否 |

### 8.2 Image Search

| 渠道 | `provider` 值 | CN Auto | GLOBAL Auto | 接入形式 | API Key |
|---|---|---:|---:|---|---:|
| Bing Images | `bing` | 1 | 1 | CN / Global 图片结果页 | 否 |
| 百度图片 | `baidu` | 2 | - | `acjson` + Cookie warmup | 否 |
| 搜狗图片 | `sogou` | 3 | - | 初始化 State 解析 | 否 |
| Openverse | `openverse` | 4 | 2 | 公开图片 API | 否 |
| Wikimedia Commons | `wikimedia` | - | 3 | MediaWiki Action API | 否 |

> v0.1.4 默认 Search/Image 链继续严格限定为无需 API Key / 无需注册账号的免费能力。公开 SERP Provider 仍属于 **best-effort**，可能受 DOM 改版、限流、CAPTCHA、网络出口影响，不承诺商业 SLA。

---

## 9. 配置参考

配置前缀：`openreach.web`，见 `src/main/resources/application.yml`。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `openreach.web.routing.default-route` | `cn` | `region=auto` 时使用的默认 Route |
| `openreach.web.search.provider` | `auto` | 默认 Web Search 模式 |
| `openreach.web.search.provider-order` | `bing,baidu,sogou,so360,duckduckgo` | v1.0.1 兼容字段；新 CN Order 为空时使用 |
| `openreach.web.search.cn-provider-order` | `[]` | 可选 CN Auto Chain；为空时继承 `provider-order` |
| `openreach.web.search.global-provider-order` | `brave,duckduckgo,bing` | GLOBAL Auto Chain |
| `openreach.web.search.bing-url` | `https://cn.bing.com/search` | CN Bing Web |
| `openreach.web.search.bing-global-url` | `https://www.bing.com/search` | GLOBAL Bing Web |
| `openreach.web.search.brave-url` | `https://search.brave.com/search` | Brave Web Interface |
| `openreach.web.search.duckduckgo-url` | `https://html.duckduckgo.com/html/` | DDG no-JS HTML |
| `openreach.web.search.timeout-ms` | `6000` | 单渠道搜索超时 |
| `openreach.web.search.rate-limit-cooldown-ms` | `60000` | Provider 命中 HTTP 429 后的短期冷却时间 |
| `openreach.web.search.bot-challenge-cooldown-ms` | `60000` | Provider 命中 CAPTCHA/Bot Challenge 后的短期冷却时间 |
| `openreach.web.search.forbidden-cooldown-ms` | `30000` | Provider 命中 HTTP 403 后的短期冷却时间 |
| `openreach.web.search.max-results` | `20` | Web Search 最大结果数 |
| `openreach.web.search.max-response-bytes` | `2097152` | 单个 Web Search Provider 上游响应硬上限（2 MiB） |
| `openreach.web.image-search.provider` | `auto` | 默认 Image Search 模式 |
| `openreach.web.image-search.provider-order` | `bing,baidu,sogou,openverse` | v1.0.1 兼容字段 |
| `openreach.web.image-search.cn-provider-order` | `[]` | 可选 CN Image Chain；为空时继承 `provider-order` |
| `openreach.web.image-search.global-provider-order` | `bing,openverse,wikimedia` | GLOBAL Image Chain |
| `openreach.web.image-search.bing-global-url` | `https://www.bing.com/images/async` | GLOBAL Bing Images |
| `openreach.web.image-search.wikimedia-url` | `https://commons.wikimedia.org/w/api.php` | Wikimedia Action API |
| `openreach.web.image-search.wikimedia-user-agent` | `OpenReach/0.1.4 (...)` | Wikimedia 可识别 UA |
| `openreach.web.image-search.timeout-ms` | `8000` | 单渠道图片搜索超时 |
| `openreach.web.image-search.max-results` | `30` | Image Search 最大结果数 |
| `openreach.web.image-search.max-response-bytes` | `4194304` | 单个图片 Provider 上游响应硬上限（4 MiB） |
| `openreach.web.image-search.download-candidate-multiplier` | `3` | 为保证最终可下载结果，Provider 候选放大倍数 |
| `openreach.web.image-search.download-max-candidates` | `60` | 单请求最多进入下载验证的图片候选数 |
| `openreach.web.image-search.download-validation-timeout-ms` | `4000` | 单个图片下载验证超时 |
| `openreach.web.image-search.download-validation-max-redirects` | `3` | 图片验证最大重定向次数 |
| `openreach.web.image-search.download-validation-max-bytes` | `65536` | 图片验证最多读取字节数 |
| `openreach.web.image-search.download-validation-concurrency` | `6` | 图片验证线程并发数 |
| `openreach.web.image-search.download-validation-queue-capacity` | `48` | 图片验证有界队列容量 |
| `openreach.web.read.timeout-ms` | `10000` | 旧配置兼容的 Read 超时 fallback |
| `openreach.web.read.connect-timeout-ms` | `7000` | Read TCP/TLS 建连超时；未配置时回退 `timeout-ms` |
| `openreach.web.read.request-timeout-ms` | `15000` | Read 单次 GET 请求总超时；未配置时回退 `timeout-ms` |
| `openreach.web.read.max-attempts` | `2` | 网络 IO/连接超时及瞬时 `408/425/500/502/503/504/520~524` 的最大 GET 尝试次数；403/412/429 不重试 |
| `openreach.web.read.retry-backoff-ms` | `200` | Read 网络 IO / 瞬时 HTTP 状态重试间隔 |
| `openreach.web.read.max-bytes` | `5242880` | Read 响应体上限（5 MiB） |
| `openreach.web.read.max-chars` | `50000` | 正文字数上限 |
| `openreach.web.read.max-redirects` | `5` | 最大重定向次数 |
| `openreach.web.read.allowed-ports` | `[80,443]` | Read 允许访问的公网端口 |
| `openreach.web.curl.connect-timeout-ms` | `5000` | Curl 建连超时 |
| `openreach.web.curl.request-timeout-ms` | `10000` | Curl 单次请求超时 |
| `openreach.web.curl.max-bytes` | `2097152` | Curl 文本响应硬上限（2 MiB） |
| `openreach.web.curl.max-chars` | `100000` | Curl 默认最大返回字符数 |
| `openreach.web.curl.max-redirects` | `5` | Curl 最大重定向次数 |
| `openreach.web.curl.blocked-hosts` | `[]` | 额外 OpenReach 自身公网域名/别名；环境变量 `OPENREACH_CURL_BLOCKED_HOSTS` |
| `openreach.web.security.max-api-body-bytes` | `65536` | 四个 JSON API 请求体统一硬上限（64 KiB） |

### 9.1 兼容策略

旧部署如果只维护 `provider-order` 而没有 `cn-provider-order`，CN Route 自动继续使用旧字段；因此 v1.0.1 国内配置可平滑升级。GLOBAL Chain 使用独立 `global-provider-order`，不会把海外 Provider 插入旧 CN 链。

---

## 10. Agent 典型使用闭环

```text
search(query)
   ↓
获得 SearchItem.url（搜索结果 URL）
   ↓
read(url)
   ↓
获得正文 content、标题、metadata、links
   ↓
Agent 综合回答 / 引用来源
```

GitHub / 源码场景：

```text
search("site:github.com <topic>")
   ↓
curl("https://api.github.com/repos/<owner>/<repo>")
   ↓
curl("https://raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>")
   ↓
Agent 直接分析源码 / JSON
```

图片场景：

```text
image-search(query)
   ↓
获得 imageUrl + sourcePageUrl
   ↓
read(sourcePageUrl)
   ↓
获得图片上下文 / 来源证据
```

---

## 11. 服务启动与更多资源

```bash
mvn clean test   # 离线单测门禁
mvn spring-boot:run
```

- 官网更新日志：`/changelog`（源码事实源：根目录 `CHANGELOG.md`）
- Curl 示例与 Smoke Test：`docs/接口测试与Curl示例.md`
- HTTP 插件 JSON（Agent 平台直接导入）：`docs/agenthub/skills/openreach-http-plugin.json`
- 部署：`docs/部署篇/`


---

## 12. `All connection attempts failed` 快速判断

如果 Search / Read / Image Search 对多个完全不同目标统一出现：

```text
All connection attempts failed
```

并且没有 OpenReach 标准 `traceId`，说明错误通常发生在 **AgentHub / Tool Runner -> OpenReach** 连接阶段，请优先检查：

1. Plugin `BASE_URL` 是否仍为 `localhost`；
2. Tool Runner 是否运行在另一个容器 / 沙箱；
3. OpenReach 容器是否存活、8080 是否发布；
4. 两个容器是否在同一 Docker Network；
5. DNS / 防火墙 / 安全组 / 反向代理是否允许 Tool Runner 访问。

调用方现场验证：

```bash
BASE_URL='<插件实际 BASE_URL>' ./bin/quick/connectivity-test.sh
```

HTTP Plugin 模板不再内置 `localhost`，导入时必须显式配置 `{{BASE_URL}}`。同 Docker Network 可使用 `http://openreach:8080`（以实际服务名为准）。

另外，后端 JSON 现在同时兼容：

```text
timeRange / time_range
maxChars  / max_chars
```

避免 Agent Tool Adapter 的 snake_case 转换导致时间范围或读取长度参数被忽略。
