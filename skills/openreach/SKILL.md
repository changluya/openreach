# OpenReach Skill

> 用 OpenReach 为 Agent 提供统一的网页搜索、文搜图与网页读取能力。该 Skill 只依赖 Python 标准库。

## 1. Agent 首先只做一次初始化状态 `check`

正常 Agent 流程开始时，如果当前任务尚不知道 OpenReach 是否已初始化，只执行：

```bash
python3 scripts/openreach.py check
```

**这是初始化状态检查的唯一推荐动作。不要自行组合 `ls`、`doctor`、`init`、端口扫描或多次请求。**

`check` 的行为被严格限定为：

```text
1. 只检查 skills/openreach/config.json 是否存在
   ├─ 不存在 -> initialized=false，立即返回，网络请求次数 = 0
   └─ 存在   -> 只读取其中的 base_url

2. 仅发送 1 次无副作用接口探测
   POST /api/web/search
   body = {}

3. 预期 OpenReach 在本地参数校验阶段返回
   HTTP 400 + code=VALIDATION_ERROR
   -> initialized=true
```

这里故意使用空 `SearchRequest` 做探测：请求会在 OpenReach 参数校验层直接结束，**不会执行真实搜索，也不会访问任何上游搜索 Provider**。

### Agent 强约束

- `config.json` 不存在：报告“未初始化”，并**立即向用户索要 OpenReach 服务地址 `<OPENREACH_BASE_URL>`**。在用户明确提供前停止；禁止自动创建配置、猜测 `localhost`/内网地址、扫描 IP/端口或自行执行 `init`。
- `config.json` 无效：报告配置无效，并提示用户提供/确认正确的 `<OPENREACH_BASE_URL>`；**禁止自动修复、覆盖或删除**。
- 单次接口探测失败：只报告探测失败，**禁止自动重试、切换地址或连续探测其他接口**。
- `check` 失败 **不等于获得执行 `init` 的权限**。未初始化时应先向用户索要 `<OPENREACH_BASE_URL>`；只有用户明确提供该地址并要求初始化时，才执行 `init`。
- `check` 成功一次后，本次任务内直接使用 Search / Image Search / Read，**不要在每次 Tool 调用前重复 check**。
- `check` 判断的是 Skill 自己的持久化初始化状态，因此只认当前 Skill 的 `config.json`；不会因为环境变量或本机某个地址恰好可用就把“未初始化”误判为“已初始化”。

成功示例：

```json
{
  "initialized": true,
  "base_url": "<OPENREACH_BASE_URL>",
  "status": "READY",
  "probe": "POST /api/web/search",
  "httpStatus": 400,
  "code": "VALIDATION_ERROR",
  "networkProbes": 1
}
```

未初始化示例：

```json
{
  "initialized": false,
  "reason": "CONFIG_MISSING",
  "networkProbes": 0,
  "userActionRequired": true,
  "nextAction": "ASK_USER_FOR_SERVICE_ADDRESS",
  "message": "Ask the user to provide <OPENREACH_BASE_URL>."
}
```

## 2. `init` 只用于用户明确要求的首次初始化

只有用户明确提供 OpenReach 服务地址并要求完成初始化时，才执行：

```bash
python3 scripts/openreach.py init '<OPENREACH_BASE_URL>'
```

`init` 会验证目标服务后写入当前 Skill 目录的 `config.json`。初始化完成后，后续任务只需要用上一节的 `check` 判断状态，不要再次执行 `init`。

推荐让用户直接提供完整的 `<OPENREACH_BASE_URL>`（包含协议；非默认端口时也包含端口）。Skill 不提供任何示例 IP、默认主机或“可尝试地址”。

## 3. `doctor` 仅用于人工排障，不属于正常 Agent 前置流程

```bash
python3 scripts/openreach.py doctor
```

`doctor` 只做官网根路径 `GET /` 的连通性诊断。它保留给人工排查服务地址、端口、反向代理等问题。

**正常 Agent 不应执行 `check -> doctor -> search`。正确流程是 `check` 成功后直接调用业务 Tool。**

## 4. 三个核心 Tool

### search

```bash
python3 scripts/openreach.py search "Spring Boot AI Agent" \
  --region US \
  --provider auto \
  --time-range month \
  --limit 5
```

`region` **始终建议显式展示**。默认值为 `auto`；v0.1.2 中 `auto` 默认进入 CN Route。`CN / zh-CN` 等中国 alias 走国内免费链，`US / JP / SG / GB / GLOBAL / wt-wt` 等其他显式地区进入 GLOBAL 免费链；之后 `region` 继续作为 Provider 的 locale/country Hint。它不是商业级精确 Geo，但已是核心 Route 参数。

`time-range` 对应 HTTP `timeRange`，支持 `any/day/week/month/year`，默认 `any`。需要“最近一天/一周/一月/一年”的最新性约束时应显式传入；`provider=auto` 会自动跳过不能真实执行时间过滤的 Provider，不能把该参数仅当作提示词。

### image-search

```bash
python3 scripts/openreach.py image-search "杭州西湖夜景" \
  --region auto \
  --provider auto \
  --limit 8
```

返回的 `items[].imageUrl` 在响应生成前已经经过公网 URL/重定向校验与真实图片字节探测。Agent 可以把 `imageUrl` 直接作为下载目标；失效热链、403/404、HTML 防盗链页、伪图片和 SVG 会被过滤。网络状态可能在响应后变化，因此仍建议下载失败时重新执行 image-search，而不是伪造素材。

### read

```bash
python3 scripts/openreach.py read \
  "https://spring.io/projects/spring-boot/" \
  --max-chars 20000
```

## 5. Python Tool 调用

```python
from skills.openreach import check_initialized, search, image_search, read

state = check_initialized()  # 每个任务只需一次；成功后不要重复 check
results = search("OpenReach AI Agent", limit=5, region="US", provider="auto", time_range="month")
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
未配置 -> 明确报错并要求用户提供 <OPENREACH_BASE_URL>；不猜测任何默认地址
```

## 6. ChatGPT-like Search SOP

> 这不是对 ChatGPT 私有服务端实现的复刻。它基于项目现有调研中公开可观察的 Agentic Search 思路，抽象为 OpenReach 三原语可以执行的通用 SOP。

### Step 0：判断是否需要 Web

涉及最新信息、外部事实、官网资料、新闻、产品文档、公众号公开文章、需要引用来源或模型自身不确定时，进入 Web Search 流程。

### Step 1：Query Planning / Rewrite

不要机械地把用户整句话只搜索一次。先识别：

```text
核心实体 + 目标事实 + 时间范围 + 地域 + 来源偏好
```

如果问题明确要求“最近一天/本周/本月/今年”，优先把它结构化为 `time_range=day/week/month/year`，不要只把时间词留在 query 文本里。

复杂问题拆成 2~4 个互补查询。例如：

```text
OpenReach GitHub
OpenReach API docs
OpenReach latest release
```

地域不明确时使用 `region=auto`（默认 CN）；明确限定日本、美国、新加坡等非 CN 地区时应显式传对应 `region`，从而进入 GLOBAL Route。

### Step 2：Search 只负责“发现候选来源”

```bash
python3 scripts/openreach.py search "..." --region US --provider auto --time-range month --limit 8
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

需要图片时独立调用 `image-search`。`imageUrl` 已通过即时可下载校验；同时优先保留 `sourcePageUrl`，必要时继续 `read(sourcePageUrl)` 获取图片上下文、出处与许可信息。

## 7. 公众号 / 官网等典型场景

- **官网 / 产品页**：Search 找官网与具体页面，再 Read 原页面。
- **技术文档 / GitHub**：Search 找官方文档、仓库说明、Release 页面，再 Read。
- **微信公众号公开文章**：Search 尝试发现已被搜索引擎收录的公开文章；若已知公开 URL，则直接 Read。是否可发现/读取受搜索引擎收录、微信访问策略和页面可访问状态影响。
- **新闻 / 行业调研**：多 Query + 多来源 Search，再 Read 原始报道并交叉验证。
- **内容配图**：image-search 获取图片、来源页和许可元数据（Provider 可提供时）。

## 8. 错误与安全边界

- 免费 Search Provider 属于 best-effort，上游页面改版、限流或网络出口都可能影响结果。
- `read` 面向公开 HTTP/HTTPS 页面，不用于绕过登录、验证码、访问控制或反爬机制。
- 服务公网只开放三个 JSON POST API 与官网只读静态资源；Skill 不应尝试文件上传、任意 Method、Actuator/debug 等未暴露能力。
- Read 与图片原图探测只允许公网 HTTP/HTTPS 80/443，并对跳转目标重新做 SSRF 校验。
- CLI 错误时退出码为 `2`，Agent 应把它视为 Tool 执行失败并进行降级/重试/换 Query，而不是伪造结果。


## 连接失败快速判断

如果 Search / Read / Image Search 统一出现 `All connection attempts failed`（或 Skill 报 `Cannot reach OpenReach at ...`），并且没有 OpenReach 后端 `traceId`，说明请求没有到 OpenReach。不要继续换 query、Provider 或目标 URL 重试；应检查已配置 `base_url` 是否从当前 Agent/Tool Runner 环境真实可达。容器/沙箱中的 `localhost` 指向当前容器自身，不代表 OpenReach。
