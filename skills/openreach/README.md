# OpenReach Skill

官网可直接下载的 OpenReach Python Skill。无需第三方 Python 依赖。

## 1. Agent 先只执行一次 `check`

```bash
python3 scripts/openreach.py check
```

`check` 是判断“是否已经初始化”的唯一推荐动作：

- 先只检查当前 Skill 的 `config.json` 是否存在；
- 不存在：立即返回 `initialized=false`，**0 次网络请求**，并要求 Agent 向用户索要 `<OPENREACH_BASE_URL>`；
- 存在：只读取其中 `base_url`，然后**仅 1 次** `POST /api/web/search` 空 JSON 探测；
- 探测预期得到 `HTTP 400 + VALIDATION_ERROR`，请求在参数校验层结束，不触发真实搜索或上游 Provider；
- `check` 从不创建、修改、修复或删除配置，也不自动重试、扫描地址或调用 `init`。

`check` 成功一次后，本次任务内直接使用业务 Tool，不要在每次 search/read 前重复检查。若未初始化，在用户提供服务地址之前停止，不猜测地址、不扫端口、不自动执行 `init`。

## 2. 首次初始化仅在用户明确要求时执行

```bash
python3 scripts/openreach.py init '<OPENREACH_BASE_URL>'
```

成功后生成 `config.json`，后续自动使用该地址。`<OPENREACH_BASE_URL>` 必须来自用户明确提供；Skill 不内置示例 IP 或默认主机。`check` 失败并不代表 Agent 可以自行 `init`。

## 3. `doctor` 只用于人工排障

```bash
python3 scripts/openreach.py doctor
```

它只做官网 `GET /` 连通性诊断，**不是正常 Agent 调用 Search / Image Search / Read 前的必做步骤**。

## 4. 使用

```bash
python3 scripts/openreach.py search "AI Agent" --region US --provider auto --time-range month --limit 5
python3 scripts/openreach.py image-search "杭州西湖" --region auto --provider auto --limit 8
python3 scripts/openreach.py read "https://spring.io/projects/spring-boot/" --max-chars 20000
```

详细 Agent Search SOP 见 [SKILL.md](SKILL.md)。

## v0.1.2 Route

`region=auto` 默认走 CN；`CN/zh-CN` 等走国内链，其他显式地区如 `US/JP/SG/GLOBAL` 走 GLOBAL 免费链。Web GLOBAL 默认 `brave -> duckduckgo -> bing`，Image GLOBAL 默认 `bing -> openverse -> wikimedia`。

## v0.1.2 时间范围、图片可下载与初始化检查

- Web Search：`--time-range any|day|week|month|year`，对应 HTTP `timeRange`。指定时间范围后只使用真正支持该过滤的 Provider。
- Image Search：返回的 `imageUrl` 均在响应生成时通过公网 SSRF、跳转、HTTP 状态与图片字节签名探测，可直接作为下载目标。
- Init Check：只检查 `config.json` + 1 次无副作用 API 探测；禁止多余初始化动作。
- Doctor：只读官网 `GET /`，仅人工排障使用，不额外开放 Health API。
- 安全边界：服务业务面仅包含 Search / Image Search / Read 三个 JSON POST API，不支持文件上传。


## 连接失败快速判断

如果 Search / Read / Image Search 统一出现 `All connection attempts failed`（或 Skill 报 `Cannot reach OpenReach at ...`），并且没有 OpenReach 后端 `traceId`，说明请求没有到 OpenReach。不要继续换 query、Provider 或目标 URL 重试；应检查已配置 `base_url` 是否从当前 Agent/Tool Runner 环境真实可达。容器/沙箱中的 `localhost` 指向当前容器自身，不代表 OpenReach。
