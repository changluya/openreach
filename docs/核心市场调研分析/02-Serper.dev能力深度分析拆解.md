# Serper.dev 能力深度分析拆解

> 调研日期：2026-08-14  
> 定位：分析 Serper 作为商业 Google SERP Provider 能提供什么、不能替代什么，以及当前 Java Web Foundation 应如何预留兼容能力。

---

# 1. 一句话定位

Serper.dev 本质上是：

> **把 Google 的多种 SERP / 垂直搜索结果封装成稳定、低延迟、结构化的商业 API。**

它不是：

```text
LLM
Agent
Deep Research Engine
自动 Citation 系统
完整 Web Reader
```

因此正确接入位置是：

```text
Agent / Research Engine
        ↓
Search Capability
        ↓
SearchProvider SPI
        ↓
Serper
```

而不是让 Agent 直接依赖 Serper 的 JSON Schema。

---

# 2. 从用户截图拆解 Serper 的能力版图

截图/官网当前展示：

```text
Search
Images
News
Maps
Places
Videos
Shopping
Scholar
Patents
Autocomplete

Webpage Scrape
```

这组菜单可以拆成四类。

## 2.1 通用搜索 SERP

```text
Search
News
Videos
```

共同特征：

```text
Query -> Ranked Results
```

核心价值：

```text
Google ranking
snippet
position
freshness
source
```

---

## 2.2 媒体搜索

```text
Images
Videos
```

Images 官网样例返回字段包含：

```text
title
imageUrl
imageWidth
imageHeight
thumbnailUrl
thumbnailWidth
thumbnailHeight
source
domain
link
position
```

这里最值得当前工程借鉴的是：

> 图片结果必须同时携带“图片本身”和“来源页面”。

因此本工程 `ImageSearchItem` 也设计为：

```text
imageUrl
thumbnailUrl
sourcePageUrl
source
domain
width
height
imageFormat
license
licenseUrl
```

---

## 2.3 实体 / 结构化搜索

```text
Maps
Places
Shopping
Scholar
Patents
```

这些能力不能用普通 `SearchItem(title,url,snippet)` 完整表达。

例如 Serper Places 官网示例能够返回类似：

```text
title
address
latitude
longitude
rating
ratingCount
type
website
phone
placeId
```

Patents 则可能出现：

```text
priorityDate
filingDate
grantDate
publicationDate
inventor
assignee
publicationNumber
pdfUrl
figures
```

这说明成熟 Search Gateway 应允许：

```text
SearchProvider
ImageSearchProvider
PlaceSearchProvider
ProductSearchProvider
ScholarSearchProvider
...
```

分别拥有自己的 DTO。

---

## 2.4 Query Intelligence

```text
Autocomplete
```

它不是正文检索，而属于：

```text
Query Suggestion
Query Expansion
Search UX
```

对于 Agent，它未来可以用于：

```text
Query Rewrite
Query Expansion
Research Planning
```

但 V1 没有必要实现。

---

# 3. Serper Search 为什么比自己解析 HTML 稳定？

Serper 真正卖的并不是一个 HTTP Parser，而是长期运营后的基础设施能力：

```text
Google 实时查询
稳定网络出口
反封禁维护
SERP 变化适配
Geo / Language
结构化解析
Retry
高并发 API Gateway
可预期延迟
```

官网当前宣称：

- 一般查询约 1–2 秒；
- 部分重试请求可能 2–4 秒；
- 实时查询 Google，而非返回自身缓存；
- 可以通过国家/语言调整查询位置；
- 不同付费档位提供约 50~300 QPS。

因此：

```text
免费 HTML Parser
```

和：

```text
Serper
```

真正的差距不只在结果 JSON，而在：

```text
SLA-like operational capability
```

---

# 4. Serper Search 返回的不只是 Organic Results

官网普通 Search 示例包含：

```text
knowledgeGraph
organic
sitelinks
peopleAlsoAsk
relatedSearches
```

这说明商业 SERP API 可以保留 Google 搜索页中的大量结构化 Feature。

当前 V1 免费 Search 只统一：

```text
title
url
snippet
rank
source
```

这是刻意做出的抽象：

> V1 只解决 Agent “找到页面” 的最小共同能力，不为了兼容 Google SERP 把业务 DTO 做成一个巨大的可空字段集合。

未来接 Serper 时，可以采用两层模型：

```text
Unified SearchResponse
        +
Provider Extensions / Raw Metadata（可选）
```

避免丢失高级 SERP 数据。

---

# 5. Serper Images 对当前文搜图设计的启发

当前 Java OpenReach v0.1.0 设计：

```text
POST /api/web/image-search
```

统一字段：

```text
rank
title
imageUrl
thumbnailUrl
sourcePageUrl
source
domain
width
height
imageFormat
license
licenseUrl
```

与 Serper Images 的核心结构高度兼容。

因此未来增加：

```text
SerperImageSearchProvider
```

可以直接映射：

```text
Serper imageUrl      -> imageUrl
Serper thumbnailUrl  -> thumbnailUrl
Serper link          -> sourcePageUrl
Serper source        -> source
Serper domain        -> domain
Serper imageWidth    -> width
Serper imageHeight   -> height
```

Agent Tool 协议无需改变。

---

# 6. Serper 与 ChatGPT Search 的关系

不能写成：

```text
接入 Serper = 实现 ChatGPT Search
```

更准确是：

```text
                    ChatGPT-like Search
                           │
                  Agentic Search Loop
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
   Search Provider      Web Reader        Citation
        │                  │                  │
      Serper           Jsoup/Browser        Evidence
```

Serper 可以显著提升：

```text
Discovery Quality
Google SERP Consistency
Geo
Vertical Search
Reliability
```

但仍需要自己的：

```text
read/open_page
source selection
rerank
evidence extraction
citation
answer synthesis
```

---

# 7. Webpage Scrape 应如何理解？

用户截图在 Serper 页面右上角展示了 `Webpage Scrape` 入口。

它再次证明：

```text
Search != Scrape/Read
```

对当前工程而言，正确映射是：

```text
Serper Search/Images/... -> Provider 层
Webpage Read/Scrape      -> PageReader 层
```

即使未来使用 Serper 的 Webpage Scrape，也应该实现为：

```text
SerperPageReader / RemotePageReader
```

而不是让 `SerperSearchProvider` 顺带抓网页。

> 注：本文只根据用户截图确认 Serper 产品页面存在 Webpage Scrape 入口；具体接口协议、额度和响应字段应以 Serper 控制台/正式 API 文档为准。

---

# 8. 成本分析：什么时候值得接 Serper？

截至 2026-08-14，Serper 官网展示的价格大致为：

| 套餐 | Credits | 单价 | QPS |
|---|---:|---:|---:|
| Starter | 50k | $1.00 / 1k | 50 |
| Standard | 500k | $0.75 / 1k | 100 |
| Scale | 2.5M | $0.50 / 1k | 200 |
| Ultimate | 12.5M | $0.30 / 1k | 300 |

并提供 2,500 free queries 作为试用入口。

因此一个很现实的决策是：

```text
普通 Agent Search
  -> 免费 Provider

高质量 / 高价值 Query
  -> Serper
```

而不是：

```text
所有 Query 都付费
```

也不是：

```text
为了省 $0.x / 1000 queries
自己养一个复杂 SERP 反爬平台
```

---

# 9. 为什么仍然不建议 Serper 多账号池？

这一点需要非常明确。

Serper 官方 Terms 当前明确写有：

```text
不得同时注册多个账号
不得尝试绕过 API Key / Account 的限制
```

因此如果未来接 Serper：

```text
一个合法企业账号
+ 正常购买额度
+ 应用侧限流/缓存/路由
```

才是正确路线。

不建议：

```text
批量免费账号
轮换 API Key
绕额度
账号池
```

这不仅增加工程复杂度，也直接碰到服务条款风险。

---

# 10. 图片版权与来源问题

Serper Terms 同样明确指出：

- API 返回数据可能包含第三方文字、图片、视频；
- 内容仍属于相应提供者；
- 不应错误标注来源或隐藏版权/来源信息；
- 有知识产权约束的内容需要获得许可或有其他合法依据才能使用。

因此当前 `image-search` 设计中特别保留：

```text
sourcePageUrl
source
domain
license
licenseUrl
```

并且工程 OpenReach v0.1.0：

> **只返回图片候选 URL 与来源元数据，不把搜索结果图片视为天然可商用素材，也不自动下载/重分发图片。**

Openverse Provider 的意义也在这里：它能够在部分结果中提供明确 License 信息，适合未来增加：

```text
license-aware image search
```

---

# 11. Serper 接入当前架构的正确方式

## 11.1 普通 Search

```java
public class SerperSearchProvider implements SearchProvider {
    @Override
    public String name() {
        return "serper";
    }
}
```

加入：

```text
SearchRouter / SearchService
├── free providers
└── serper
```

---

## 11.2 Images

```java
public class SerperImageSearchProvider implements ImageSearchProvider {
    @Override
    public String name() {
        return "serper";
    }
}
```

不改变：

```text
POST /api/web/image-search
```

---

# 12. 推荐 Premium Fallback 策略

后续 V2 可以引入：

```text
Search Quality Gate
```

例如：

```text
免费结果数量不足
结果域名多样性太低
多个免费 Provider 同时 403 / 429
用户明确要求 Google
需要 Places / Maps / Shopping
需要更强 Geo
```

才触发：

```text
Serper
```

形成：

```text
               SearchRouter
                    │
              Free Providers
                    │
              Quality Gate
                /       \
             GOOD       BAD
              │          │
            return     Serper
```

这比“全量 Serper”有更好的成本控制，也比“账号池 + 代理池”更稳定。

---

# 13. 当前工程与 Serper 能力矩阵

| Serper 能力 | 当前工程 | 后续建议 |
|---|---|---|
| Search | ✅ 免费 5 路 | 可加 Serper Provider |
| Images | ✅ 免费 4 路 | 可加 Serper Images |
| News | ❌ | 独立 `NewsSearchProvider` |
| Maps | ❌ | 优先商业 Provider |
| Places | ❌ | 优先商业 Provider |
| Videos | ❌ | 后续独立 SPI |
| Shopping | ❌ | 优先商业 Provider |
| Scholar | ❌ | 后续独立 SPI |
| Patents | ❌ | 后续独立 SPI |
| Autocomplete | ❌ | 后续 Suggest SPI |
| Webpage Read/Scrape | ✅ Jsoup 基础版 | Playwright / Remote Reader |
| Google SERP 一致性 | ❌ | Serper 补齐 |
| 商业高 QPS | ❌ | Serper 补齐 |

---

# 14. 最终判断

Serper 最值得购买的是：

```text
稳定的 Google SERP
结构化垂直结果
Geo
高并发
持续的反封禁/解析维护
```

而不是“它比 Java 多写了几个 HTTP 请求”。

因此当前最佳路线仍然是：

```text
V1 原型 / OpenReach v0.1.0
免费 Provider
    +
稳定 SPI

V2
SerperSearchProvider
SerperImageSearchProvider
    +
Quality Gate / Premium Fallback
```

> **把商业 Provider 当能力增强层，而不是基础协议。**

---

# 15. 参考资料

- Serper 官网：https://serper.dev/
- Serper Terms：https://serper.dev/terms
- OpenAI Web Search：https://developers.openai.com/api/docs/guides/tools-web-search

