# OpenReach 接口文档

> 适用版本：**v1.0.2** · 服务默认地址：`http://localhost:8080`

本文档基于当前工程源码（`io.github.changlu.openreach`）维护 OpenReach 对外 HTTP 接口的完整说明，面向 Agent 集成方、HTTP 插件开发者和 API 调用方。

---

## 1. 接口总览

| 能力 | 方法 | 路径 | 说明 |
|---|---|---|---|
| Web Search | `POST` | `/api/web/search` | 网页搜索，多 Provider 自动降级 |
| Image Search | `POST` | `/api/web/image-search` | 文搜图，多图片 Provider 自动降级 |
| Web Read | `POST` | `/api/web/read` | 读取网页正文，内置 SSRF 防护 |

Skill 判断是否已初始化时使用本地 `check`：先检查 Skill `config.json`，存在时仅执行 1 次 `POST /api/web/search` 空 JSON 探测并期待本地校验返回 `400 / VALIDATION_ERROR`，不会触发真实搜索。`doctor` 仅保留为人工官网 `GET /` 排障能力；两者都不新增第四个业务 API。

Controller 定义位于 `src/main/java/io/github/changlu/openreach/web/WebCapabilityController.java`。

三个能力完全解耦，均为无需 Search API Key 的公开 HTTP 接口。安全 Filter 只允许这三个精确 POST 路径且强制 `application/json`；Multipart/文件上传、未知 API、危险 Method 均拒绝。

---

## 2. 通用约定

### 2.1 请求

- `Content-Type: application/json`
- 请求体为 JSON 对象；仅 `query` / `url` 必填（见各接口说明）
- 未识别的字段会被忽略；可空字段不传即使用默认值

### 2.2 响应

- 成功响应直接返回业务对象（**无外层包装**），HTTP `200`
- 失败响应统一为错误对象，结构见 [第 6 章](#6-错误码与公共错误响应)

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
| `query` | string | 是 | - | `@NotBlank` | 搜索关键词或自然语言问题 |
| `limit` | int | 否 | `10` | `@Min(1)` `@Max(20)` | 最多返回条数，服务端还会受配置 `max-results`（默认 20）钳制 |
| `region` | string | 否 | `auto` | - | 核心路由 + Provider Locale Hint；CN aliases 走 CN，其他显式地区走 GLOBAL |
| `provider` | string | 否 | `auto` | `@Size(max=32)` | 渠道，见 [第 7 章 Provider 矩阵](#71-web-search) |
| `timeRange` | string | 否 | `any` | `@Size(max=32)` | `any/day/week/month/year`；兼容 d/w/m/y、pd/pw/pm/py、qdr:* |

校验失败返回 `400 VALIDATION_ERROR`（如 `query` 为空）。

### 3.2 响应字段

成功响应 HTTP `200`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider` | string | 实际使用的渠道（`auto` 时为 `auto`，不会替换为具体渠道名） |
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
- `timeRange!=any` 时，auto 会跳过 `supportsTimeRange=false` 的 Provider；显式 Provider 不支持时返回 `400 BAD_REQUEST`。当前 Brave / DuckDuckGo 实现真实上游时间过滤。

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
| `query` | string | 是 | - | `@NotBlank` | 文搜图关键词或自然语言描述 |
| `limit` | int | 否 | `10` | `@Min(1)` `@Max(30)` | 最多返回条数，服务端还会受配置 `max-results`（默认 30）钳制 |
| `region` | string | 否 | `auto` | - | 与 Web Search 共用 CN / GLOBAL 路由规则，并作为图片 Provider Locale Hint |
| `provider` | string | 否 | `auto` | - | 渠道，见 [第 7 章 Provider 矩阵](#72-image-search) |

### 4.2 响应字段

成功响应 HTTP `200`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider` | string | 实际使用的渠道（`auto` 时为 `auto`） |
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
| `url` | string | 是 | - | `@NotBlank` | 需要读取的公网 HTTP/HTTPS 网页地址 |
| `maxChars` | int | 否 | `50000` | `@Min(1000)` `@Max(200000)` | 最多返回的正文字符数；未传使用服务端配置 `read.max-chars`（默认 50000） |

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
    "description": "Spring Boot makes it easy to create stand-alone, production-grade Spring based Applications that you can just run.",
    "author": null,
    "publishedAt": null
  },
  "links": [
    "https://spring.io/",
    "https://github.com/spring-projects/spring-boot"
  ]
}
```

> 上例中 `metadata` 中缺失的键实际不会出现在 JSON 中；此处展示仅为示意。

---

### 5.5 公网安全基线

- 业务接口精确 allowlist：`POST /api/web/search`、`POST /api/web/image-search`、`POST /api/web/read`；
- Spring Multipart 全局关闭，Filter 明确拒绝 `multipart/*`；
- API 请求体默认最大 64 KiB；
- 未知路径、Upload/Actuator/Debug、危险 Method、Path Traversal 提前拒绝；
- Read/Image remote fetch 仅公网 HTTP/HTTPS 80/443，并对 Redirect 每跳复检；
- 官网静态资源仅来自 classpath，并配置 CSP / nosniff / DENY frame 等 Header。

## 6. 错误码与公共错误响应

所有错误响应统一结构（由 `GlobalExceptionHandler` 生成）：

```json
{
  "timestamp": "2026-08-15T02:00:00.000Z",
  "status": 400,
  "code": "BAD_REQUEST",
  "message": "Only http/https URLs are allowed"
}
```

| HTTP 状态 | `code` | 触发场景 |
|---|---|---|
| `400` | `BAD_REQUEST` | 业务参数非法（如 URL 非法 / 不支持渠道 / SSRF 拦截 / 内容类型不支持 / 响应体超限）；`BadRequestException` 或 `IllegalArgumentException` |
| `400` | `VALIDATION_ERROR` | Bean Validation 校验失败（`query` 空、`limit` 越界、`maxChars` 越界等），`message` 形如 `query: must not be blank` |
| `502` | `UPSTREAM_ERROR` | 上游全部渠道失败 / 指定渠道无结果 / 读取网页失败 / 重定向过多 / 上游非 2xx |
| `500` | `INTERNAL_ERROR` | 未预期的服务端异常 |

---

## 7. Provider 矩阵

### 7.1 Web Search

| 渠道 | `provider` 值 | CN Auto | GLOBAL Auto | 接入形式 | API Key |
|---|---|---:|---:|---|---:|
| Bing | `bing` | 1 | 3 | CN / Global HTML SERP | 否 |
| 百度 | `baidu` | 2 | - | HTML SERP | 否 |
| 搜狗 | `sogou` | 3 | - | HTML SERP | 否 |
| 360 搜索 | `so360` | 4 | - | HTML SERP | 否 |
| DuckDuckGo | `duckduckgo` | 5 | 2 | HTML no-JS POST Form | 否 |
| Brave Web | `brave` | - | 1 | 公开 Web SERP | 否 |

### 7.2 Image Search

| 渠道 | `provider` 值 | CN Auto | GLOBAL Auto | 接入形式 | API Key |
|---|---|---:|---:|---|---:|
| Bing Images | `bing` | 1 | 1 | CN / Global 图片结果页 | 否 |
| 百度图片 | `baidu` | 2 | - | `acjson` + Cookie warmup | 否 |
| 搜狗图片 | `sogou` | 3 | - | 初始化 State 解析 | 否 |
| Openverse | `openverse` | 4 | 2 | 公开图片 API | 否 |
| Wikimedia Commons | `wikimedia` | - | 3 | MediaWiki Action API | 否 |

> v1.0.2 默认链严格限定为无需 API Key / 无需注册账号的免费能力。公开 SERP Provider 仍属于 **best-effort**，可能受 DOM 改版、限流、CAPTCHA、网络出口影响，不承诺商业 SLA。

---

## 8. 配置参考

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
| `openreach.web.search.max-results` | `20` | Web Search 最大结果数 |
| `openreach.web.image-search.provider` | `auto` | 默认 Image Search 模式 |
| `openreach.web.image-search.provider-order` | `bing,baidu,sogou,openverse` | v1.0.1 兼容字段 |
| `openreach.web.image-search.cn-provider-order` | `[]` | 可选 CN Image Chain；为空时继承 `provider-order` |
| `openreach.web.image-search.global-provider-order` | `bing,openverse,wikimedia` | GLOBAL Image Chain |
| `openreach.web.image-search.bing-global-url` | `https://www.bing.com/images/async` | GLOBAL Bing Images |
| `openreach.web.image-search.wikimedia-url` | `https://commons.wikimedia.org/w/api.php` | Wikimedia Action API |
| `openreach.web.image-search.wikimedia-user-agent` | `OpenReach/1.0.2 (...)` | Wikimedia 可识别 UA |
| `openreach.web.image-search.timeout-ms` | `8000` | 单渠道图片搜索超时 |
| `openreach.web.image-search.max-results` | `30` | Image Search 最大结果数 |
| `openreach.web.read.timeout-ms` | `10000` | Read 请求超时 |
| `openreach.web.read.max-bytes` | `5242880` | Read 响应体上限（5 MiB） |
| `openreach.web.read.max-chars` | `50000` | 正文字数上限 |
| `openreach.web.read.max-redirects` | `5` | 最大重定向次数 |

### 8.1 兼容策略

旧部署如果只维护 `provider-order` 而没有 `cn-provider-order`，CN Route 自动继续使用旧字段；因此 v1.0.1 国内配置可平滑升级。GLOBAL Chain 使用独立 `global-provider-order`，不会把海外 Provider 插入旧 CN 链。

---

## 9. Agent 典型使用闭环

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

## 10. 服务启动与更多资源

```bash
mvn clean test   # 离线单测门禁
mvn spring-boot:run
```

- Curl 示例与 Smoke Test：`docs/接口测试与Curl示例.md`
- HTTP 插件 JSON（Agent 平台直接导入）：`docs/agenthub/skills/openreach-http-plugin.json`
- 部署：`docs/部署篇/`
