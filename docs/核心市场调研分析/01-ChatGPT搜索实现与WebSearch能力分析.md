# ChatGPT 搜索实现与 WebSearch 能力分析

> 调研日期：2026-08-14  
> 说明：ChatGPT 的服务端私有实现并未公开。本文只基于 OpenAI 官方公开的 ChatGPT Search / Responses API Web Search 行为与接口能力进行分析；其中“内部架构图”属于基于公开行为的工程推断，不代表 OpenAI 官方披露的内部源码实现。

---

## 1. 核心结论

如果目标是给 Agent 建设一套接近 ChatGPT Search 的 Web 能力，不能把问题简单理解为：

```text
找一个 Search API -> 把 Top10 丢给 LLM
```

更接近真实产品能力的抽象应是：

```text
用户问题
   ↓
是否需要联网 / Search Intent
   ↓
Query Planning / Rewrite
   ↓
Search
   ↓
结果筛选 / 去重 / 来源判断
   ↓
Open Page / Read
   ↓
Find in Page / Evidence Extraction
   ↓
是否证据足够？ ──否──> 再搜索 / 再阅读
   ↓ 是
Evidence / Sources
   ↓
LLM Synthesis
   ↓
Answer + Citation
```

OpenAI 官方 Web Search 文档公开了三种层级：

1. **Non-reasoning web search**：快速查询，直接基于 Top Results 回答；
2. **Agentic search**：Reasoning Model 主动管理搜索过程，并决定是否继续搜索；
3. **Deep Research**：长周期、多来源的 Agent-driven Research。

官方 Web Search Tool 的动作模型还明确包含：

```text
search
open_page
find_in_page
```

因此，本工程将基础层收敛为：

```text
search(query)        -> 发现网页
image-search(query)  -> 发现图片及其来源
read(url)            -> 打开/读取网页
```

是比“一个大而全 web_search()”更适合长期演进的基础设施设计。

---

# 2. 从用户截图看：Web Search 产品究竟包含哪些能力？

用户截图展示的是 Serper.dev 首页的一组搜索能力入口：

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

+ Webpage Scrape
```

这张图真正有价值的地方，不是“要复制 10 个 API”，而是揭示了成熟 Web Search 平台通常存在三个能力层次。

## 2.1 第一层：Discovery / SERP —— 找到信息

截图中的：

```text
Search
Images
News
Videos
Shopping
Scholar
Patents
```

本质上都属于：

> **不同垂直索引/不同 SERP 类型的 Discovery。**

输入通常都是 Query：

```text
query + language + country/location + page
```

输出则是结构化候选结果。

例如普通网页：

```text
title
url
snippet
rank
source
```

图片：

```text
title
imageUrl
thumbnailUrl
sourcePageUrl
source/domain
width/height
```

新闻则额外关注：

```text
publishedAt
publisher
freshness
```

因此它们并不是同一种 Result DTO，只是共享“Search”行为。

---

## 2.2 第二层：Entity / Local / Structured Search —— 找结构化实体

截图中的：

```text
Maps
Places
Shopping
```

与普通网页搜索的区别很大。

Places 更像：

```text
query
 ↓
Business / POI Entity Search
 ↓
name
address
lat/lng
rating
phone
website
placeId
```

Shopping 更像：

```text
product
price
merchant
rating
image
shopping link
```

因此后续如果扩展这类能力，建议单独设计：

```text
place_search()
product_search()
```

而不是继续往普通 `SearchRequest` 里增加几十个参数。

---

## 2.3 第三层：Webpage Read / Scrape —— 把结果真正读进去

截图右上角单独出现：

```text
Webpage Scrape
```

这是非常重要的产品设计信号：

> **Search 和 Read/Scrape 是两个独立问题。**

Search 解决：

```text
哪些 URL 值得读？
```

Read/Scrape 解决：

```text
这个 URL 的真实正文是什么？
```

这正是当前工程坚持将：

```text
search(query)
read(url)
```

拆开的核心原因。

截图只能证明 Serper UI 将 Webpage Scrape 作为单独能力呈现；具体 Scrape API 字段和 SLA 仍应以其正式 API 文档/控制台为准，不在本文猜测。

---

# 3. ChatGPT 式 Web Search 与普通 SERP API 的根本差异

Serper 类型服务的核心产物是：

```text
Structured SERP Data
```

ChatGPT Search 类型产品最终需要产出：

```text
Grounded Answer
```

中间至少多出以下步骤：

```text
SERP Provider
     ↓
Search Results
     ↓
Source Selection
     ↓
Page Read
     ↓
Evidence Extraction
     ↓
Cross-source Reasoning
     ↓
Citation Binding
     ↓
Natural Language Answer
```

因此：

```text
Serper != ChatGPT Search
```

更准确的关系是：

```text
Serper / Bing / Baidu / SearXNG
            ↓
       Search Provider
            ↓
      Agentic Web Layer
            ↓
        ChatGPT-like Search
```

---

# 4. OpenAI 官方公开能力可以反推出什么设计？

## 4.1 Search 不是一次调用就结束

OpenAI 官方将 reasoning 模型的 Web Search 描述为 Agentic Search：模型可以搜索、分析结果，并决定是否继续搜索。

因此一个 Agent Search Loop 更合理的是：

```text
while evidence_not_enough:
    queries = plan()
    results = search(queries)
    pages = choose_sources(results)
    evidence = read(pages)
```

而不是固定：

```text
永远只搜 1 次 + 永远读 Top5
```

---

## 4.2 `open_page` 与 `find_in_page` 是一等动作

官方 Web Search Tool 输出中公开了：

```text
search
open_page
find_in_page
```

这意味着成熟 Web Search 并不只依赖 Search Snippet。

典型流程可能是：

```text
search("Spring Boot 4 release notes")
        ↓
open_page(official release notes)
        ↓
find_in_page("Java 17")
        ↓
拿到局部证据
```

当前工程 V1 的：

```text
read(url)
```

已经覆盖了 `open_page` 的基础能力。

未来可以继续在 Read 上层增加：

```text
find_in_page(url, pattern/query)
```

但不需要改变 `read` 的基础接口。

---

# 5. Citation 为什么是核心能力而不是 UI 装饰？

OpenAI 官方 Web Search 输出将：

```text
web_search_call
message
annotations / cited URLs
sources
```

区分开。

这里至少有三个概念：

### Search Result

搜索引擎返回的候选页面。

### Consulted Source

Agent 实际打开/读取过的来源。

### Citation

最终回答中真正用于支持某个结论的来源。

它们不能简单等价：

```text
搜索到 20 个 URL
≠
读了 20 个 URL
≠
回答引用 20 个 URL
```

未来 Research Layer 应维护：

```text
SearchResult
    ↓
SourceDocument
    ↓
EvidenceChunk
    ↓
Citation
```

而不是只在 LLM Prompt 最后附 URL。

---

# 6. 图片搜索：为什么应该独立成为第三个原语？

OpenAI 官方 Web Search 已公开支持 image results，并且图片结果包含：

```text
image_url
thumbnail_url
source_website_url
caption
```

这非常接近当前工程设计的：

```text
ImageSearchItem
├── imageUrl
├── thumbnailUrl
├── sourcePageUrl
├── title
├── source/domain
├── width/height
├── imageFormat
└── license/licenseUrl
```

因此不建议：

```text
search(query, type="image")
```

然后返回一个字段完全不一样的 SearchItem。

更干净的基础协议是：

```text
search(query)        -> SearchResponse
image-search(query)  -> ImageSearchResponse
read(url)            -> ReadResponse
```

---

# 7. 为什么文搜图不能只返回图片直链？

Agent 拿到图片后通常还需要回答：

```text
这是什么？
图片来自哪里？
原始页面是什么？
能否引用来源？
是不是只是缩略图？
图片尺寸如何？
是否存在授权信息？
```

因此最低结果模型应该保留：

```text
imageUrl          原图/候选图片 URL
thumbnailUrl      缩略图
sourcePageUrl     图片所在网页
source/domain     来源站点
width/height      可得时返回
imageFormat       可得时返回
license           可得时返回
licenseUrl        可得时返回
```

其中：

> `sourcePageUrl` 的价值甚至不低于 `imageUrl`，因为 Agent 后续可以继续调用 `read(sourcePageUrl)` 获取图片的文本上下文和可引用来源。

最终形成：

```text
image-search(query)
       ↓
图片候选
       ↓
read(sourcePageUrl)
       ↓
图片上下文 / 来源证据
```

---

# 8. 对截图中十类 Search 能力的工程拆分建议

| 截图能力 | 本质 | 建议基础接口 | 当前状态 |
|---|---|---|---|
| Search | 通用网页发现 | `search` | ✅ V1 |
| Images | 文搜图 | `image-search` | ✅ OpenReach v0.1.1 |
| News | 时效性网页/新闻实体 | `news-search` | 后续 |
| Maps | 地图 SERP | `map-search` | 后续，优先商业 Provider |
| Places | POI / Business Entity | `place-search` | 后续，优先商业 Provider |
| Videos | 视频结果 | `video-search` | 后续 |
| Shopping | 商品 SERP | `product-search` | 后续，优先商业 Provider |
| Scholar | 学术检索 | `scholar-search` | 后续 |
| Patents | 专利检索 | `patent-search` | 后续 |
| Autocomplete | Query Suggest | `suggest` | 后续 |
| Webpage Scrape | 页面读取 | `read` | ✅ V1 |

关键原则：

> **能力按稳定的数据模型拆 SPI，而不是做一个万能 endpoint。**

---

# 9. 当前工程与 ChatGPT-like Search 的能力映射

```text
                    ChatGPT-like Web Layer
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
     Discovery            Page Access          Evidence
        │                    │                    │
   search()           image-search()          read()
        │                    │                    │
 SearchProvider      ImageSearchProvider      PageReader
        │                    │                    │
 Bing/Baidu/...     Bing/Baidu/Sogou/...      Jsoup
```

当前已经完成的是 Web Foundation。

仍未做的是：

```text
Search Intent Router
Query Rewrite / Query Planning
Search Quality Gate
Source Trust Ranking
Parallel Read
find_in_page
Evidence Chunk
Cross-source Verification
Citation Binding
Answer Synthesis
Deep Research Planner
```

这些应该建立在三原语之上，而不是塞入 Provider 内部。

---

# 10. 推荐的长期分层

```text
L4  Product / Agent
    Chat / Research / Coding Agent

L3  Research Orchestration
    Query Plan
    Multi Search
    Source Selection
    Evidence
    Citation

L2  Web Capability
    search
    image-search
    read
    future: news/place/product/...

L1  Provider
    Free Providers
    SearXNG
    Serper
    Firecrawl
    Playwright
```

这种分层可以保证：

```text
今天免费 HTML Provider
        ↓
明天 Serper
```

只替换 L1，不影响 Agent 协议。

---

# 11. V1/V2 对标目标

## V1 原型 / OpenReach v0.1.1

目标不是对标完整 ChatGPT Search，而是先获得：

```text
search
image-search
read
```

三个稳定 Web 原语。

## V2

增加：

```text
SearchRouter
Premium Provider
Read Quality Gate
Playwright
Source Metadata
```

## V3 / Research

增加：

```text
Query Planning
Parallel Search
Parallel Read
Evidence Extraction
Citation
Source Trust
Research Loop
```

此时才真正开始接近 ChatGPT Agentic Search 的产品形态。

---

# 12. 参考资料

OpenAI 官方：

- Web Search Guide: https://developers.openai.com/api/docs/guides/tools-web-search
- ChatGPT Search 介绍: https://openai.com/index/introducing-chatgpt-search/
- ChatGPT Search Help: https://help.openai.com/en/articles/9237897-chatgpt-search
- Deep Research: https://openai.com/index/introducing-deep-research/

对照产品：

- Serper.dev: https://serper.dev/

