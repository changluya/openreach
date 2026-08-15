# OpenReach 接口文档

> 适用版本：**v0.1.0** · 服务默认地址：`http://localhost:8080`

本文档基于当前工程源码（`io.github.changlu.openreach`）维护 OpenReach 对外 HTTP 接口的完整说明，面向 Agent 集成方、HTTP 插件开发者和 API 调用方。

---

## 1. 接口总览

| 能力 | 方法 | 路径 | 说明 |
|---|---|---|---|
| Web Search | `POST` | `/api/web/search` | 网页搜索，多 Provider 自动降级 |
| Image Search | `POST` | `/api/web/image-search` | 文搜图，多图片 Provider 自动降级 |
| Web Read | `POST` | `/api/web/read` | 读取网页正文，内置 SSRF 防护 |

Controller 定义位于 `src/main/java/io/github/changlu/openreach/web/WebCapabilityController.java`。

三个能力完全解耦，均为**无鉴权**的公开 HTTP 接口，无需 API Key。请求与响应统一使用 `application/json`。

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
| `region` | string | 否 | `CN` | - | 搜索区域，各 Provider 支持程度不同 |
| `provider` | string | 否 | `auto` | - | 渠道，见 [第 5 章 Provider 矩阵](#51-web-search) |

校验失败返回 `400 VALIDATION_ERROR`（如 `query` 为空）。

### 3.2 响应字段

成功响应 HTTP `200`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `provider` | string | 实际使用的渠道（`auto` 时为 `auto`，不会替换为具体渠道名） |
| `query` | string | 回显原始查询词 |
| `region` | string | 生效区域（未传时为 `CN`） |
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

- `provider=auto`：按 `provider-order` 顺序逐个渠道请求，**任一渠道失败（超时/解析失败/空结果）不中断**，继续下一个渠道；结果按 URL 规范化后去重聚合，达到 `limit` 提前结束。默认顺序 `bing → baidu → sogou → so360 → duckduckgo`。
- `provider=<具体渠道>`：仅请求指定渠道；渠道不存在返回 `400 BAD_REQUEST`，渠道返回空结果返回 `502 UPSTREAM_ERROR`。
- 所有渠道均失败且无任何结果时返回 `502 UPSTREAM_ERROR`。
- 返回前会对 `items` 重新编号（`rank` 从 1 连续）。

### 3.4 示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{"query":"Spring Boot AI Agent","limit":5,"region":"CN","provider":"auto"}'
```

```json
{
  "provider": "auto",
  "query": "Spring Boot AI Agent",
  "region": "CN",
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
| `region` | string | 否 | `CN` | - | 图片搜索区域，各 Provider 支持程度不同 |
| `provider` | string | 否 | `auto` | - | 渠道，见 [第 5 章 Provider 矩阵](#52-image-search) |

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
| `imageUrl` | string | 原图 URL |
| `thumbnailUrl` | string | 缩略图 URL（渠道可提供时） |
| `sourcePageUrl` | string | 图片来源页面 |
| `provider` | string | 产出该结果的渠道名（如 `bing`） |
| `source` | string | 来源标记 |
| `domain` | string | 来源站点域名 |
| `width` | int / null | 原图宽度（渠道可提供时） |
| `height` | int / null | 原图高度（渠道可提供时） |
| `imageFormat` | string | 图片格式（如 jpeg/png，渠道可提供时） |
| `license` | string | 许可协议（主要 Openverse 提供） |
| `licenseUrl` | string | 许可协议链接（主要 Openverse 提供） |

> 注意：即使结果携带 `license` / `licenseUrl`，仍应以来源页面为准确认最终使用条件。

### 4.3 行为说明

- `provider=auto`：按 `provider-order` 顺序逐个渠道请求，单渠道失败不中断；按 `imageUrl` 去重聚合，达到 `limit` 提前结束。默认顺序 `bing → baidu → sogou → openverse`。
- `provider=<具体渠道>`：仅请求指定渠道；渠道不存在返回 `400 BAD_REQUEST`，返回空结果返回 `502 UPSTREAM_ERROR`。
- 所有渠道均失败且无任何结果时返回 `502 UPSTREAM_ERROR`。
- 返回前会对 `items` 重新编号。

### 4.4 示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{"query":"杭州西湖夜景","limit":8,"region":"CN","provider":"auto"}'
```

```json
{
  "provider": "auto",
  "query": "杭州西湖夜景",
  "region": "CN",
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

| 渠道 | `provider` 值 | Auto 顺序 | 接入形式 | API Key |
|---|---|---|---:|---:|
| Bing 中国 | `bing` | 1 | HTML SERP 解析 | 否 |
| 百度 | `baidu` | 2 | HTML SERP 解析 | 否 |
| 搜狗 | `sogou` | 3 | HTML SERP 解析 | 否 |
| 360 搜索 | `so360` | 4 | HTML SERP 解析 | 否 |
| DuckDuckGo | `duckduckgo` | 5 | HTML Search 解析 | 否 |

### 7.2 Image Search

| 渠道 | `provider` 值 | Auto 顺序 | 接入形式 | 正式 API |
|---|---|---|---:|---|---:|
| Bing Images | `bing` | 1 | 图片搜索结果解析 | 否 |
| 百度图片 | `baidu` | 2 | `acjson` + Cookie warmup | 否 |
| 搜狗图片 | `sogou` | 3 | 初始化 State 解析 | 否 |
| Openverse | `openverse` | 4 | 官方公开 API | 是 |

> 免费渠道属于 **best-effort 解析**，可能受上游 DOM 改版、限流、CAPTCHA、网络出口影响，不承诺商业 SLA。

---

## 8. 配置参考

配置前缀：`openreach.web`，见 `src/main/resources/application.yml`。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `openreach.web.search.provider` | `auto` | 默认搜索渠道 |
| `openreach.web.search.provider-order` | `bing,baidu,sogou,so360,duckduckgo` | auto 路由顺序 |
| `openreach.web.search.timeout-ms` | `6000` | 单渠道搜索超时（毫秒） |
| `openreach.web.search.max-results` | `20` | 搜索单请求最大结果数 |
| `openreach.web.image-search.provider` | `auto` | 默认图片搜索渠道 |
| `openreach.web.image-search.provider-order` | `bing,baidu,sogou,openverse` | auto 路由顺序 |
| `openreach.web.image-search.timeout-ms` | `8000` | 单渠道图片搜索超时（毫秒） |
| `openreach.web.image-search.max-results` | `30` | 图片搜索单请求最大结果数 |
| `openreach.web.read.timeout-ms` | `10000` | 读取请求超时（毫秒） |
| `openreach.web.read.max-bytes` | `5242880` | 响应体上限（5 MiB） |
| `openreach.web.read.max-chars` | `50000` | 正文字数上限 |
| `openreach.web.read.max-redirects` | `5` | 最大重定向次数 |

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
