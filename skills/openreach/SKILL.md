# OpenReach Skill

> 用 OpenReach 为 Agent 提供统一的网页搜索、文搜图与网页读取能力。该 Skill 只依赖 Python 标准库。

## 1. 使用前必须先初始化

从官网点击 **下载 Skill**，解压后进入 `openreach/` 目录。

只需要提供 OpenReach 服务所在机器的 IP：

```bash
python3 scripts/openreach.py init 192.168.1.20
```

默认会组成：

```text
http://192.168.1.20:8080
```

初始化流程会先调用 `doctor` 检查 `/api/web/health`。只有服务可连通时才会写入当前 Skill 目录的：

```text
config.json
```

之后所有命令自动读取该配置，不需要重复传 IP。

如果使用其他端口：

```bash
python3 scripts/openreach.py init 192.168.1.20 --port 18080
```

HTTPS：

```bash
python3 scripts/openreach.py init openreach.example.com --https --port 443
```

## 2. 前置连通性 Tool：doctor

在执行 Search / Image Search / Read 之前，优先确认 OpenReach 可用：

```bash
python3 scripts/openreach.py doctor
```

预期：

```json
{
  "base_url": "http://192.168.1.20:8080",
  "status": "UP",
  "service": "openreach"
}
```

如果 doctor 失败，不继续执行后续 Web 工具，先检查服务地址、端口、防火墙、反向代理或 OpenReach 进程。

## 3. 三个核心 Tool

### search

```bash
python3 scripts/openreach.py search "Spring Boot AI Agent" \
  --region auto \
  --provider auto \
  --limit 5
```

`region` **始终建议显式展示**。默认值为 `auto`；未传时也等价于 `auto`。如任务明确限定国家/地区，可传 `CN`、`US`、`JP` 等。不同 Provider 对 region 的映射能力不同，因此它是 best-effort 参数，不承诺商业级精确 Geo。

### image-search

```bash
python3 scripts/openreach.py image-search "杭州西湖夜景" \
  --region auto \
  --provider auto \
  --limit 8
```

### read

```bash
python3 scripts/openreach.py read \
  "https://spring.io/projects/spring-boot/" \
  --max-chars 20000
```

## 4. Python Tool 调用

```python
from skills.openreach import doctor, search, image_search, read

doctor()
results = search("OpenReach AI Agent", limit=5, region="auto", provider="auto")
page = read(results["items"][0]["url"], max_chars=20000)
images = image_search("OpenReach logo", limit=5, region="auto")
```

Base URL 读取优先级：

```text
函数 / CLI --base-url
    ↓
OPENREACH_BASE_URL 环境变量
    ↓
skills/openreach/config.json
    ↓
http://127.0.0.1:8080
```

## 5. ChatGPT-like Search SOP

> 这不是对 ChatGPT 私有服务端实现的复刻。它基于项目现有调研中公开可观察的 Agentic Search 思路，抽象为 OpenReach 三原语可以执行的通用 SOP。

### Step 0：判断是否需要 Web

涉及最新信息、外部事实、官网资料、新闻、产品文档、公众号公开文章、需要引用来源或模型自身不确定时，进入 Web Search 流程。

### Step 1：Query Planning / Rewrite

不要机械地把用户整句话只搜索一次。先识别：

```text
核心实体 + 目标事实 + 时间范围 + 地域 + 来源偏好
```

复杂问题拆成 2~4 个互补查询。例如：

```text
OpenReach GitHub
OpenReach API docs
OpenReach latest release
```

地域不明确时使用 `region=auto`；明确限定中国、日本、美国等场景时再指定区域。

### Step 2：Search 只负责“发现候选来源”

```bash
python3 scripts/openreach.py search "..." --region auto --provider auto --limit 8
```

不要把 Search snippet 直接当最终证据。先观察：标题、URL、摘要、来源域名、结果是否重复、是否来自原始站点。

### Step 3：优先选择可验证的一手来源

优先级建议：

```text
官方站点 / 官方文档 / 原始公告
    > 权威机构 / 主流媒体 / 原论文
    > 高质量技术社区 / 专业文章
    > 聚合页 / 二次转载
```

对于重要结论，尽量选择多个相互独立的来源，而不是只依赖一个搜索结果。

### Step 4：Read 打开原页面

对真正相关的候选 URL 调用：

```bash
python3 scripts/openreach.py read "https://..."
```

核心原则：

```text
Search Result ≠ Consulted Source ≠ Citation
```

“搜到一个 URL”并不代表已经读取过，也不代表它足以支持最终答案。

### Step 5：检查证据是否足够

如果页面正文没有回答目标问题、信息过旧、不同来源互相冲突或只有二手转述，应继续：

```text
改写 Query → 再 Search → 选择新来源 → 再 Read
```

而不是固定“只搜一次 + 永远读取 Top5”。

### Step 6：交叉验证与时效性

涉及最新状态时重点检查页面日期、事件发生时间和来源是否为当前版本。关键事实最好用 2 个以上来源互证；如果可靠来源冲突，应保留差异而不是强行合并。

### Step 7：形成答案与来源链

最终输出只引用真正支持结论的来源。建议在 Agent 上层维护：

```text
SearchResult
   ↓
SourceDocument（真正 read 过）
   ↓
Evidence
   ↓
Citation
```

### 图片任务

需要图片时独立调用 `image-search`，优先保留 `sourcePageUrl`，必要时继续 `read(sourcePageUrl)` 获取图片上下文与来源信息。

## 6. 公众号 / 官网等典型场景

- **官网 / 产品页**：Search 找官网与具体页面，再 Read 原页面。
- **技术文档 / GitHub**：Search 找官方文档、仓库说明、Release 页面，再 Read。
- **微信公众号公开文章**：Search 尝试发现已被搜索引擎收录的公开文章；若已知公开 URL，则直接 Read。是否可发现/读取受搜索引擎收录、微信访问策略和页面可访问状态影响。
- **新闻 / 行业调研**：多 Query + 多来源 Search，再 Read 原始报道并交叉验证。
- **内容配图**：image-search 获取图片、来源页和许可元数据（Provider 可提供时）。

## 7. 错误与安全边界

- 免费 Search Provider 属于 best-effort，上游页面改版、限流或网络出口都可能影响结果。
- `read` 面向公开 HTTP/HTTPS 页面，不用于绕过登录、验证码、访问控制或反爬机制。
- CLI 错误时退出码为 `2`，Agent 应把它视为 Tool 执行失败并进行降级/重试/换 Query，而不是伪造结果。
