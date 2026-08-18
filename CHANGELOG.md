# Changelog

OpenReach 的版本更新记录。

本文件从 **v1.0.1** 开始作为正式维护基线，后续版本按 `Added / Changed / Fixed / Security / Known Limitations / Roadmap` 持续记录，避免能力演进只散落在 README、设计文档或提交记录中。

> 版本说明：正式维护口径从 **v1.0.1** 起记录；v0.1.3 已同步 Maven Artifact、README 与官网版本入口；v0.1.2 的路由、安全与部署能力继续作为当前运行基线。`docs/设计方案/v1.0.1设计访问文档.md` 统一收敛首个正式基线设计、渠道与验收口径；早期市场调研继续保留历史语境。

---

## [v0.1.3] - 2026-08-16

### 版本定位

v0.1.3 将独立内部调用监控从前端原型升级为**真实请求采集 + SQLite 持久化 + 可升级存储抽象**。监控入口仍不加入官网 / 文档导航，核心 Search / Image Search / Read 不依赖监控存储可用性。

### Build / Test Fix

- 修复 `RequestTraceFilterTest` 两处 testCompile 失败：`FilterChain` 回调参数为 `ServletResponse`，不应直接调用 `setStatus(int)`。
- 删除与真实 IP 断言无关的显式 `setStatus(200)`，保持测试聚焦真实客户端 IP 解析，同时兼容标准 Servlet API 类型。

### Added

- 独立 `/monitor` 后台与服务端 Session 登录保护，默认账号密码 `openreach / openreach`；Docker Run / Compose 均支持通过 `OPENREACH_MONITOR_USERNAME`、`OPENREACH_MONITOR_PASSWORD` 在首次启动或升级重建时显式指定凭据；
- 今日 / 近 7 日 / 自定义日期范围统计，数据总览新增独立 IP 数（按统计周期对 `client_ip` 去重），总览、趋势、接口分布、失败下钻和请求明细统一使用真实监控 API；
- 新增 `/api/monitor/status|overview|trend|distribution|records` 查询接口，并新增 `/api/monitor/records/export` 流式 UTF-8 失败诊断日志导出接口；所有 Monitor API 仅登录后的监控 Session 可访问；
- `RequestTraceFilter` 对三个公开 Web API 真实采集请求时间、IP、输入参数、输出参数、HTTP 状态、成功/失败、耗时、Trace ID、Provider 和错误信息；
- 新增 `MonitorRecordStore` 存储 SPI，Controller / Service / Filter 不感知具体数据库；v0.1.3 默认实现 `SqliteMonitorRecordStore`，为后续 MySQL / PostgreSQL 实现保留稳定边界；
- SQLite 使用 WAL、busy timeout、`quick_check`、独立 Schema Version 和顺序 Migration；Schema V1 拆分 `monitor_request_record` 元数据表与 `monitor_request_payload` Payload 表；
- 监控写入使用有界队列 + 独立单 Writer，存储不可用、队列满或写入异常均不影响三个核心 Web API；
- 默认元数据保留 30 天、Payload 保留 7 天，并支持环境变量调整；请求/响应 Payload 默认最多持久化 64 KiB并执行敏感字段脱敏；
- Compose 新增一次性 `openreach-init`，在 Linux Host Bind 场景自动修正 `/app/data` 与 `/app/logs` 的 UID 10001 权限；主服务仍保持非 root、`read_only` 与 `cap_drop: ALL`；
- `/app/data` 正式成为持久化契约；SQLite 默认位于 `/app/data/monitor/openreach-monitor.db`，销毁 / 重建容器时宿主机 data 目录继续复用；
- 已有数据库发生未来 Schema 升级前会使用 SQLite `VACUUM INTO` 生成一致性快照，并按配置保留最近若干份；
- 配置了当前镜像尚未实现的存储类型时，监控子系统降级为不可用而不是阻止 OpenReach 核心服务启动。

### Changed

- 监控页面“请求记录监控”新增独立请求时间筛选，支持精确到分钟的开始/结束时间；默认未设置时继续继承顶部“今日 / 近 7 日 / 自定义日期”统计周期，应用后仅影响请求记录和失败请求导出，不改变总览、趋势与接口分布。后端 `/api/monitor/records`、`/api/monitor/records/export` 新增可选 `requestStartTimeMs/requestEndTimeMs` 成对覆盖参数，旧调用不传新参数时保持原行为；新增 Controller 与前端契约单测覆盖默认兼容、范围覆盖、缺失单端、非法区间及导出透传。
- `./bin/quick/qps-unit-test.sh` 与 `target/qps/openreach-qps-report.md` 全面中文化；Markdown 报告从“原始指标表”升级为“核心结论 / 测试范围与环境 / 各并发档结果 / 吞吐与延迟趋势 / 拐点容量信号 / 验收结果 / 指标说明 / 边界与下一步”的决策型性能报告；
- QPS 报告自动计算总成功率、峰值 QPS/并发、峰值 P95/P99、相邻并发档 QPS/P95 变化、吞吐回落或收益趋缓信号，并结合 `MIN_PEAK_QPS` 给出明确通过/未通过结论；
- `openreach-qps-report.csv` 继续保留原英文列名，确保已有脚本、Excel 和 CI 数据解析兼容；新增 `QpsReportRendererTest` 覆盖中文报告结构、峰值结论、失败判定和 CSV 兼容性。

### Read 失败请求归因与调用规范增强（2026-08-18）

- 复盘监控导出的 15 条 Read 失败：其中 10 条为调用方把 `172.16.114.23:8999` 内部附件/图片/日志 URL 交给公网 Read，确认属于调用方误用；SSRF 与 80/443 安全边界保持不变，不通过放开私网修复。
- Skill `read()` 增加调用前 fail-fast：明显私网/localhost、非 80/443、图片/压缩包二进制 URL 在客户端直接返回 `READ_TARGET_*`，避免产生无意义的后端 400 失败记录；AgentHub HTTP 插件同步补充同一调用边界与降级规则。
- `SafeHttpFetcher` 增加无 Cookie/无 Authorization 的浏览器导航兼容 Header；对 `408/425/500/502/503/504/520~524` 等幂等 GET 瞬时故障执行最多一次有界重试，`403/412/429` 等访问策略/限流状态不机械重试。
- 新增结构化 `UpstreamHttpException`：对 Read 上游 HTTP 错误继续保持 `502 + code=UPSTREAM_ERROR` 兼容，同时返回 `failureType/upstreamStatus/retryable`，Agent 可直接判断“换来源”还是“瞬时故障”。
- 新增对应 Java/Skill 回归测试；详细归因见 `docs/设计方案/v0.1.3-Read失败请求归因与调用规范优化.md`。

### Fixed

- 修复 Nginx + Docker 部署下监控“IP 地址”长期显示 `172.17.0.1`：原实现默认关闭代理头解析，即使 Nginx 正确透传真实来源也只会记录 Docker Bridge；现默认启用代理头解析，但仅信任 `127.0.0.1/32`、`::1/128`、`172.16.0.0/12` 等显式可信代理来源，并从 `X-Forwarded-For` 右向左剥离可信代理节点后得到真实客户端 IP。新增 `OPENREACH_MONITOR_TRUSTED_PROXY_CIDRS` 配置、Compose/.env 示例、Nginx 标准透传配置及真实 IP / 伪造 XFF / 多级代理 / IPv6 回归测试；历史 SQLite 记录不回填，仅新请求生效。
- 修复 `bin/quick/qps-unit-test.sh` 在部分 macOS Bash/Locale/解压链路下出现 `MIN_PEAK_QPS<乱码字符>: unbound variable`：所有 QPS 环境变量引用统一改为 `${VAR}` 显式边界，`MIN_PEAK_QPS` 后的全角括号改为 ASCII 括号，并对 `bin/` 脚本执行 UTF-8 / BOM / 零宽字符 / replacement character 扫描；默认参数与自定义 `MIN_PEAK_QPS=123.45` 均完成 Shell 冒烟验证。
- 深度复盘 2026-08-17 导出的 23 条失败请求：Sogou/360 原始 HTTP 302 与百度 legacy `http://www.baidu.com/link?...` Read 521 确认为可修复的兼容缺口；Brave 429、Baidu/DDG bot challenge、目标站 403、TLS SAN mismatch 保持真实上游失败语义，不通过关闭 TLS、破解挑战页或盲目重试掩盖；
- `SearchHttpClient` 新增受控 301/302/303/307/308 跟随、Provider 域名族 allowlist、重定向上限和 HTTPS 防降级；Sogou/360 增强 UTF-8/Referer，360 默认入口更新为 `https://www.so.com/index.php`；
- `BaiduSearchProvider` 优先提取 `data-landurl/data-url/mu` 真实落地地址；百度 legacy HTTP `/link` wrapper 在 Search 输出和 Read 入口均安全升级为 HTTPS，并继续经过 SSRF Guard 校验；
- `UpstreamFailureClassifier` 新增 `TLS_CERTIFICATE / HTTP_REDIRECT / HTTP_5XX` 精细分类，并补齐 Sogou/360 重定向、百度落地 URL、Read wrapper 规范化、TLS/521/302 以及配置默认值回归测试；详细分析见 `docs/设计方案/v0.1.3三方渠道失败请求深度分析与兼容优化.md`；
- 修复 `release.sh 0.1.3` 发布门禁中的 `RequestTraceFilterTest` 回归：`ContentCachingRequestWrapper` 仅在下游读取请求体后缓存内容，原测试的 Mock FilterChain 未消费 body，导致请求脱敏与 UTF-8 请求体断言误报失败；测试现模拟真实 Spring MVC `@RequestBody` 生命周期读取请求流，不修改生产 Filter 的请求消费语义；
- 修复 `MonitorController` 失败请求流式导出编译错误：日志写入辅助方法由宽泛的 `throws Exception` 收窄为 `throws IOException`，与 Spring `StreamingResponseBody` 的输出契约保持一致，避免 `java.lang.Exception` 未捕获导致 Maven 编译失败；
- 修复“导出失败请求”按钮无效：前端 URL 构造函数此前发生递归调用，点击时会直接触发栈溢出；现改为 `buildApiUrl` + `fetch` 后端导出接口 + Blob 下载，并补充 401 / 非 2xx 错误提示；后端导出改为 UTF-8 `.log` 诊断文件，按当前日期范围 / Endpoint / Keyword / failure 条件导出全部匹配记录，包含完整入参、返回值、Trace ID、IP、错误信息、状态码与耗时；
- 修复监控响应 Payload 中文乱码：JSON 响应未显式声明 charset 时不再误用 Servlet 默认 ISO-8859-1，而按 UTF-8 解码；Schema V2 会在升级前快照后尝试修复历史可识别 mojibake Payload；
- 失败数量卡片下钻后，请求记录区右侧显示“导出失败请求”，导出复用当前日期范围 / Endpoint / Keyword / failure 状态筛选，并流式输出全部匹配记录的完整入参、返回值、错误信息与 Trace ID，不受列表分页限制；
- 请求记录表“操作”列改为右侧 Sticky 固定列，解决窄视口、浏览器缩放或横向滚动时详情入口被遮挡的问题；
- 自定义日期范围选择器与真实 API 查询链路打通，不再依赖前端 90 天 Mock 数据。

### Compatibility / Upgrade

- 推荐宿主机固定映射 `/data/openreach/data:/app/data`；标准升级只替换镜像 / 容器，不删除宿主机 data；
- Monitor 用户名 / 密码是容器运行配置，不写入 SQLite；升级重建时继续传入同一环境变量即可保持凭据，也可以在不迁移数据库的情况下直接更换；
- App Version 与 Monitor Schema Version 解耦，未来可跨应用版本按 Schema Version 顺序执行 migration；
- MySQL / PostgreSQL 尚未在 v0.1.3 提供具体实现，但存储 SPI、DTO、Service 与 API 已隔离，后续迁移无需重写监控业务层。

## [v0.1.2] - 2026-08-15

### 版本定位

v0.1.2 在 v1.0.1 国内免费渠道基线之上，把 OpenReach 从“单一国内优先 Provider Chain”升级为**由 `region` 驱动的 CN / GLOBAL Web Access Router**，并继续坚持默认链 **零 API Key、零账号依赖、零商业 Search 调用费**。

### Fixed

#### Search 时间链运行时自检 / 上游冷却与 Read 瞬时连接重试（2026-08-16）

- 针对真实 Bad Case `week/day` 只出现 `duckduckgo + brave` 的情况，restricted timeRange 遇到早期 v0.1.2 的两路配置时会自动迁移到当前已验证链：CN `baidu → bing → duckduckgo → brave`、GLOBAL `bing → brave → duckduckgo → baidu`；
- Search 启动时新增 `runtime_capabilities` 日志，明确打印已注册 Provider 以及 `day/week/month/year` 每档实际可用 Provider，快速识别“代码已更新但运行镜像/外部配置仍旧”的部署漂移；
- Search 最终失败消息增加实际 `chain=[...]`，与 `attempted/skipped` 一起返回，便于仅凭 Trace ID/错误消息还原运行时路由；
- DuckDuckGo CAPTCHA/Bot Challenge、Brave/其他 Provider HTTP 429、HTTP 403 会进入可配置短期 cooldown；后续请求直接跳过仍处于冷却期的 Provider，避免同一出口持续撞限流/验证页；
- Read 将旧单一 `timeout-ms` 拆为兼容式 `connect-timeout-ms` / `request-timeout-ms`，默认仅对幂等 GET 的网络 IO/连接超时执行 1 次有界重试（总 2 次），HTTP 4xx/5xx 不盲目重试；
- Read 上游日志增加 `attempt/maxAttempts`、`http_retry` 与最终 `attempts` 字段，区分瞬时连接异常与持续不可达；
- 新增真实 Bad Case 回归：CN `week` 的 DDG/Brave-only 旧链自动扩展、GLOBAL `day` 在 Bing fail + Brave 429 + DDG challenge 后继续 Baidu、429 Provider 下一请求进入 cooldown，以及 Read 首次 connect timeout/第二次成功的重试测试。

#### timeRange 全量范围兼容、免费 Web 实测增强与旧配置自愈（2026-08-16）

- 修复 `day/week/month/year` 在旧 CN Provider Chain 下可能退化为 `bing/baidu/sogou/so360` 全部 capability skip、最终仅剩 DuckDuckGo 单点并因 bot challenge 返回 502 的问题；
- **不接付费 API、不引入 API Key**：继续使用 Bing / 百度公开 Web 搜索页面。
- 百度真实结果页验证 `gpc=stf=<start>,<end>|stftype=1`，且 `timefactor=21/22/23/24` 分别对应 1 天 / 7 天 / 30 天 / 365 天；`BaiduSearchProvider` 现支持 `day/week/month/year`；
- Bing 免费 Web 当前可验证 `filters=ex1:"ez1/ez2/ez3"`，对应过去 24 小时 / 一周 / 一月；`BingSearchProvider` 支持 `day/week/month`，`year` 因未验证到稳定免费 Web 参数而主动判定 unsupported，不伪造支持；
- `SearchProvider` 新增范围级 `supportsTimeRange(SearchTimeRange)` default 能力判断，兼容已有第三方 Provider；
- restricted timeRange 默认链更新为 CN `baidu → bing → duckduckgo → brave`、GLOBAL `bing → brave → duckduckgo → baidu`；SearchService 会按**具体范围**自动补入真正可用的 Provider；
- restricted timeRange 请求保留 `provider_chain` 日志，直接打印本次候选链；
- 新增百度四档 epoch/timefactor 参数测试、百度安全验证页 `BOT_CHALLENGE` 识别测试、Bing `ez1/ez2/ez3` 与 year fail-fast 测试，以及 SearchService “Bing month 可用 / year 自动跳过”回归测试。

#### QPS benchmark 编译回归修复（2026-08-16）

- 修复 `WebCapabilityProperties.ImageSearch` 误包含 Web Search 专属 `cn/globalTimeRangeProviderOrder` getter/setter，导致 `mvn compile` 报 `cannot find symbol` 的问题；
- `timeRange` Provider Chain 配置继续仅保留在 `WebCapabilityProperties.Search`；
- 增加配置边界回归测试，明确 Image Search 不应暴露 Search-only timeRange Provider Order API。

#### Release / CI 发布门禁修复（2026-08-16）

- 修复官网 Changelog 与静态资源发布门禁不一致：Security 区补齐“公网攻击面 Allowlist”说明；
- 更新日志导航断言允许 `<a>` 携带 `class` 等合法属性，降低官网样式调整导致的脆弱测试误报；
- `release.sh` 增加版本归一化与 `pom.xml` 版本一致性检查，发布前输出镜像与代理上下文。

### Changed

#### Skill 初始化状态检查收敛

- 未初始化时，`check` 现在明确返回 `ASK_USER_FOR_SERVICE_ADDRESS`，要求 Agent 向用户索要 `<OPENREACH_BASE_URL>`；用户未提供前必须停止，不猜测主机/IP、不扫描端口、不自动 `init`。
- Skill 文档、CLI 帮助、根 README 与官网 Skill 示例已移除具体示例 IP，统一使用 `<OPENREACH_BASE_URL>` 占位符，避免被 Agent 误判为默认地址。

OpenReach Skill 新增只读 `check`：Agent 判断是否完成初始化时，不再组合 `doctor/init` 等多余动作。`check` 固定执行“配置文件存在性 + 最多一次无副作用 API 探测”：

- `config.json` 不存在：立即 `initialized=false`，0 次网络请求；
- 配置存在：只读取其中 `base_url`，仅发送 1 次 `POST /api/web/search` 空 JSON；
- 预期 `400 / VALIDATION_ERROR`，请求在参数校验层结束，不触发真实搜索/上游 Provider；
- 不创建、不修改、不修复配置，不重试、不扫描地址、不自动执行 `init`；
- `doctor` 降级为人工排障命令，不属于正常 Agent 前置流程；
- check 成功一次后，同一任务内不重复执行。

Skill Python Test 从 6 个扩展到 **12 个**，新增覆盖“配置缺失零网络请求 / 缺失配置不读取环境变量或本机 fallback / 配置存在严格单探测 / 配置损坏不探测且不修复 / 无配置时拒绝猜默认 Host / CLI 帮助不出现示例私网 IP”。

### Added

#### 请求 Trace、上游诊断日志与 Docker 日志持久化（2026-08-16 增补）

- 新增 `RequestTraceFilter` / MDC Trace Context；Trace ID 采用 `UTC 毫秒时间戳 + 随机后缀`，响应头统一返回 `X-OpenReach-Trace-Id`；
- Spring MVC 错误 JSON 与安全 Filter 提前拒绝 JSON 都增加 `traceId`；
- Search / Image Search / Read 增加统一 `[OPENREACH-*]` 日志前缀，记录 Provider start/skip/success/fail、上游 HTTP status/host/latency、redirect 与失败分类；
- 新增 `UpstreamFailureClassifier`，区分 `HTTP_403 / HTTP_429 / BOT_CHALLENGE / TIMEOUT / PARSE_EMPTY / IO_ERROR` 等；
- Search Auto Chain 的最终错误增加 `attempted / skipped`，明确区分“Provider 未执行”和“Provider 已请求但失败”；
- 新增独立 `timeRange` Provider Chain，并在本轮免费 Web 实测后扩展为 CN 默认 `baidu → bing → duckduckgo → brave`、GLOBAL 默认 `bing → brave → duckduckgo → baidu`；运行期再按具体范围过滤能力，避免时间搜索退化成单 Provider；
- 新增 `logback-spring.xml`，输出 `openreach.log / openreach-api.log / openreach-upstream.log`，按日期 + 大小滚动压缩归档；
- Docker 默认日志目录 `/app/logs`，Compose 支持 `OPENREACH_LOG_DIR` 映射到宿主机，容器重建后日志仍保留；Docker stdout json-file 限制 `20MB × 3`；
- Read 新增可配置 `accept-language` Header，提升普通网页静态读取兼容性；
- 新增 `bin/quick/logs.sh`，支持按 API / Upstream / Error / Trace ID 快速定位持久化日志；
- 新增 `docs/设计方案/v0.1.2请求异常诊断与日志可观测优化方案.md`，记录 403 / bot challenge / timeRange skip 根因分析以及 Rate Limiter、Circuit Breaker、Header Profile、ProxySelector / Browser Reader 后续演进方案。

#### 并发 QPS 压测与容量评估

- 新增 `OpenReachApiQpsBenchmarkTest`：真实 Spring Boot HTTP 链路 + 内存 Provider 的 opt-in 并发基准测试。
- 新增 `bin/quick/qps-unit-test.sh`，输出 QPS、Avg、P50/P95/P99/Max、成功率和 HTTP 状态码分布。
- 新增 `bin/quick/qps-test.sh`，可对已经启动的 OpenReach 进行真实上游并发压测，并保留失败 Trace ID。
- 压测报告落盘到 `target/qps/`，便于不同版本和部署规格长期对比。
- 新增 `docs/设计方案/v0.1.2并发QPS压测与容量评估方案.md`。

#### 1. CN / GLOBAL 统一路由层

新增：

```text
routing/SearchRoute.java
routing/SearchRouteResolver.java
routing/ProviderChainResolver.java
routing/RegionLocaleSupport.java
```

路由规则：

```text
region = auto / blank
  -> openreach.web.routing.default-route
  -> 默认 CN，保持 v1.0.1 兼容

CN / zh-CN / zh_CN / cn-zh / zh-Hans-CN / china
  -> CN

其他显式地区（US / JP / SG / GB / GLOBAL / wt-wt ...）
  -> GLOBAL
```

`region` 的 Route Selection 与 Provider Locale Hint 分层实现；`ProviderChainResolver` 再统一完成 Route → Web/Image Provider Chain 选择，Service 不再感知 CN/GLOBAL 配置字段，Provider 也不各自实现“是否海外”的判断。

#### 2. 海外 Web Search 免费链

GLOBAL 默认链：

```text
brave -> duckduckgo -> bing
```

新增 `BraveSearchProvider`，使用无需 API Key 的公开 Web SERP；DuckDuckGo 强化为 HTML no-JS POST Form，并增加 Region/Cookie/Header 与 bot challenge fail-fast；Bing Provider 根据 Route 在 `cn.bing.com` 与 `www.bing.com` 之间切换，不复制 Parser。

#### 3. 海外 Image Search 免费链

GLOBAL 默认链：

```text
bing -> openverse -> wikimedia
```

新增 `WikimediaImageSearchProvider`，通过 MediaWiki Action API 输出原图、缩略图、来源页、尺寸、格式以及可用的 License / License URL。Bing Images 同样支持 CN / GLOBAL Host 切换；Openverse 继续作为开放许可图片补充源。

#### 4. Route-aware 配置

新增：

```yaml
openreach.web.routing.default-route
openreach.web.search.cn-provider-order
openreach.web.search.global-provider-order
openreach.web.search.bing-global-url
openreach.web.search.brave-url
openreach.web.image-search.cn-provider-order
openreach.web.image-search.global-provider-order
openreach.web.image-search.bing-global-url
openreach.web.image-search.wikimedia-url
openreach.web.image-search.wikimedia-user-agent
```

旧 `provider-order` 保留：当 `cn-provider-order` 为空时，CN Route 自动回退到旧配置。内置 `application.yml` 的 `cn-provider-order` 默认即为空，避免外部 v1.0.1 仅覆盖 `provider-order` 时被新字段遮蔽，确保真实平滑升级。

#### 5. Search `timeRange`

`POST /api/web/search` 新增可选字段：

```text
timeRange = any | day | week | month | year
```

同时兼容 `d/w/m/y`、`pd/pw/pm/py`、`qdr:d/qdr:w/qdr:m/qdr:y`。内部统一规范化为 `SearchTimeRange`；`SearchProvider` 通过 default capability 方法增量扩展，保留 v1.0.1 三参数 SPI。

- Brave：`pd / pw / pm / py`；
- DuckDuckGo：`d / w / m / y`；
- `provider=auto` 时自动跳过不支持真实时间过滤的 Provider；
- 显式 Provider 不支持时返回 `BAD_REQUEST`，不静默忽略。

#### 6. Image 原图可下载质量门禁

新增 `ImageDownloadVerifier` / `SecureImageDownloadVerifier`。Image Provider 返回的 URL 先作为候选，只有通过以下验证才进入最终响应：

```text
public HTTP/HTTPS 80/443
+ SSRF Guard
+ redirect re-check
+ HTTP 2xx
+ non HTML/XML/SVG
+ passive image magic bytes
```

支持 JPEG / PNG / GIF / WebP / BMP / TIFF / ICO / AVIF / HEIC 等签名。`auto` 遇到“有搜索结果但全部不可下载”的 Provider 会继续 fallback。

#### 7. 公网攻击面 Allowlist

新增 `AttackSurfaceFilter`，业务面严格收敛为：

```text
POST /api/web/search
POST /api/web/image-search
POST /api/web/read
```

只读官网/Docs/Assets/Skill ZIP 之外全部默认拒绝；移除公开 `/api/web/health`，Skill `doctor` 改用只读 `GET /`。同时禁用 Multipart、文件上传、未知端点、危险 Method、Path Traversal 与超大 API Body，并增加 CSP / nosniff / frame deny 等 Header。

#### 8. 官网 Release Notes

- 官网右上角新增“更新日志”入口，并新增独立 `/changelog` 页面；
- 页面直接按本文件的 v0.1.2 / v1.0.1 事实源展示能力变化、兼容边界和安全说明；
- 文档侧边栏与移动端文档切换同步增加“更新日志”；
- v1.0.1 中历史存在的 `/api/web/health` 在页面中明确标注为已于 v0.1.2 移除，避免用户按旧版本调用。

### Changed

- Maven Artifact 版本升级为 `0.1.2`；
- `SearchService` / `ImageSearchService` 改为 Route-aware Provider Chain；
- 自动链对空 Provider Order 给出明确配置错误；
- Provider Order 会 trim / lowercase，并跳过空名称，提升配置容错；
- `ImageSearchHttpClient` 的地区 Header 改为覆盖默认值，避免重复 `Accept-Language`；
- README、API、Curl、核心设计、当前能力、部署文档、OpenReach Skill 与 AgentHub Plugin 同步 `timeRange`、图片可下载语义和安全边界；
- `docs/agenthub/接口文档/OpenReach接口文档.md` 已按当前 DTO / Service / `application.yml` / AttackSurfaceFilter 重新对齐参数长度、64 KiB API Body、完整错误码、Provider 上游 Body 限制及图片下载验证配置；
- 官网 inline theme script 移为 `/assets/theme-init.js`，配合 `script-src 'self'` CSP；
- `check-project.sh` / `package.sh` 将 Skill Python Test 纳入正式门禁；新增 `build-skill-zip.sh`，打包前自动刷新官网 Skill ZIP，避免 Skill 源码与下载包不一致；
- 设计文档进一步按版本收敛：v1.0.1 统一为 `docs/设计方案/v1.0.1设计访问文档.md`，v0.1.2 的海外与安全设计统一为 `docs/设计方案/v0.1.2优化（安全+海外）文档.md`。

### Fixed

- 修正 v1.0.1 DuckDuckGo 简单 GET 模式的脆弱性，改为当前 no-JS HTML 表单模型；
- 修正海外请求虽传 `region` 但 Auto Chain 仍优先国内 Provider 的架构问题；
- 修正 Bing Web / Image 固定中国 Host、无法按地区切换的问题；
- 修正图片 HTTP 请求追加 Locale Header 可能形成重复 Header 的问题；
- 修正内置非空 `cn-provider-order` 可能遮蔽外部 v1.0.1 `provider-order` 的兼容隐患：v0.1.2 内置值保持空列表，由运行时继承旧字段；
- 空结果 / null 结果在 Auto Chain 中统一视为本 Provider 失败并继续 fallback；
- 修复 Search 缺少结构化时间范围的问题；
- 修复 Image Search 可能返回失效热链、HTML 防盗链页或伪图片 URL 的质量问题；
- 修复公网攻击面过宽：Health/未知 Controller 路径、Multipart 等现在由统一 Filter 默认拒绝；
- 服务端未知异常不再把原始异常消息直接返回客户端，降低实现细节泄露。
- 修复官网首页、快速文档与 API 文档复制按钮在 HTTP / 非安全上下文下无响应的问题：优先使用 Clipboard API，受浏览器安全策略限制时自动降级到 selection + `execCommand('copy')`，并增加复制成功/失败状态反馈。

### Tests

当前源码静态统计：

```text
Java @Test              138
OpenReach Skill Python   12
合计                    150
```

v0.1.2 新增/扩展覆盖重点：

- `SearchRouteResolver`：auto、default-route、CN aliases、非 CN → GLOBAL、非法默认值 fail-safe；
- `ProviderChainResolver`：CN legacy fallback、显式 CN Chain、GLOBAL Web/Image Chain 独立解析；
- `RegionLocaleSupport`：CN / US / JP / worldwide locale 映射；
- `WebCapabilityProperties`：v0.1.2 默认链、CN legacy 配置 fallback 与显式 CN 链覆盖；
- Search / Image Service：CN/GLOBAL 链、旧配置兼容、显式 Provider 覆盖 Route、空链、空 Provider 名称、null/empty 结果继续 fallback、dedupe；
- Bing Web / Image：CN 与 Global Host/`cc` URI 构建；
- Brave：DOM Fixture 与内部链接过滤；
- DuckDuckGo：旧结果 Fixture、Challenge 检测、时间参数映射；
- `SearchTimeRange`：标准值、alias、非法值；SearchService capability-aware skip / fail-fast；
- Image Download：不可下载过滤、fallback、显式 Provider fail-fast、candidate over-fetch、常见图片签名；
- AttackSurface：仅三 API、禁 Health/Actuator/Upload、禁 Multipart、危险 Method、Body Limit、Path Traversal、安全 Header；
- `BoundedBodyReader`：Search/Image Provider 上游 Body 正常读取与超限拒绝；
- SSRF：非 80/443、CGNAT、TEST-NET、控制字符、图片 Probe localhost/metadata 拒绝；
- Wikimedia：imageinfo、source、thumbnail、尺寸、格式、License 与缺失字段容错；
- 官网 Release Notes：`/changelog` 静态资源打包、Controller Forward、AttackSurface Allowlist、只读 Method 与主导航入口回归；
- 官网 Copy：Clipboard API 安全上下文路径、HTTP legacy fallback、成功/失败反馈，以及所有 `data-copy` selector 必须指向有效目标节点；
- v1.0.1 Read / SSRF / Spring / Static Resources 等测试继续保留回归。

本次官网 / 文档维护实际执行：

```text
python3 -m unittest discover -s skills/openreach/tests -p 'test_*.py' -v
# Ran 12 tests ... OK

HTML / 本地链接 / CSS 括号 / changelog Route + Allowlist 静态校验
# PASS

源码静态计数
# Java @Test = 138, Skill Python Test = 12
```

Java 正式门禁仍是：

```bash
mvn clean test
```

当前交付容器未预装 Maven，因此本次官网 / 文档维护无法诚实宣称 Java `mvn clean test` 的 `BUILD SUCCESS`；已执行 Skill 全量 Python Test 与官网静态资源/路由/Allowlist 校验。标准 JDK 17 + Maven 环境仍应把 `mvn clean test` 作为最终 Release Gate。

### Known Limitations

- Brave / DuckDuckGo / Bing 免费 Web SERP 仍是 best-effort，可能受到 DOM 改版、403/429、CAPTCHA、出口网络与地区策略影响；
- v0.1.2 不做 Search Pagination、商业级 Geo、账号池、代理池或 CAPTCHA 绕过；
- Brave Images 因当前公开页面依赖较脆弱的内嵌 JS/Svelte 状态，未进入默认链；
- Read 主链保持本地 SafeHttpFetcher + Jsoup，不引入远端 Key/账号依赖。

### Docs

- [`docs/设计方案/v0.1.2优化（安全+海外）文档.md`](docs/设计方案/v0.1.2优化（安全+海外）文档.md)
- [`docs/设计方案/v1.0.1设计访问文档.md`](docs/设计方案/v1.0.1设计访问文档.md)
- [`docs/核心市场调研分析/03-海外免费渠道深度调研与v0.1.2接入建议.md`](docs/核心市场调研分析/03-海外免费渠道深度调研与v0.1.2接入建议.md)

---

## [v1.0.1] - 2026-08-15

### 版本定位

v1.0.1 是 OpenReach 的首个正式维护基线版本，核心定位为：

> **面向 AI Agent 的开源 Web 访问基础设施，以免费渠道优先，通过统一 SPI、自动降级和安全读取能力，对外稳定提供 Search、Image Search、Read 三个基础 Web 原语。**

当前版本优先解决国内开发、自托管和轻量 Agent 联网场景，不依赖 Serper、Tavily 等商业 Search API Key 即可启动。

### Added

#### 1. Web Search

提供：

```text
POST /api/web/search
```

当前已接入 5 路免费 Web Search Provider：

| Provider | 标识 | 当前定位 | 接入方式 |
|---|---|---|---|
| Bing 中国 | `bing` | 默认第一路 | 公开搜索结果页 best-effort 解析 |
| 百度 | `baidu` | 中文核心 fallback | 公开搜索结果页 best-effort 解析 |
| 搜狗 | `sogou` | 国内 fallback | 公开搜索结果页 best-effort 解析 |
| 360 搜索 | `so360` | 国内 fallback | 公开搜索结果页 best-effort 解析 |
| DuckDuckGo | `duckduckgo` | 海外兜底 | HTML Search 页面解析 |

支持：

- `provider=auto` 自动选择；
- 显式指定 Provider，便于单渠道诊断；
- 多 Provider 顺序降级；
- 上游超时、空结果、解析失败后继续后续 Provider；
- URL 去重；
- 聚合后统一 Rank；
- `limit` 限制；
- `region` 统一参数模型，为后续国际化路由预留扩展点。

默认 Web Search 降级链：

```text
bing -> baidu -> sogou -> so360 -> duckduckgo
```

#### 2. Image Search / 文搜图

提供：

```text
POST /api/web/image-search
```

当前已接入 4 路免费 Image Search Provider：

| Provider | 标识 | 当前定位 | 接入方式 |
|---|---|---|---|
| Bing Images | `bing` | 默认第一路 | Bing Images 页面/异步结果解析 |
| 百度图片 | `baidu` | 中文图片核心 fallback | `acjson` + Cookie warmup |
| 搜狗图片 | `sogou` | 国内 fallback | 页面初始化状态解析 |
| Openverse | `openverse` | 开放许可图片补充源 | 官方公开 API |

统一输出包括：

- 原图 URL；
- 缩略图 URL；
- 来源页面 URL；
- Provider / Source / Domain；
- 图片宽高；
- 图片格式；
- License / License URL（上游可提供时）。

默认 Image Search 降级链：

```text
bing -> baidu -> sogou -> openverse
```

#### 3. Web Read

提供：

```text
POST /api/web/read
```

当前 Read 链路由以下组件组成：

```text
WebReadService
  -> PageReader SPI
  -> JsoupPageReader
  -> SafeHttpFetcher
  -> UrlSafetyGuard
  -> HtmlContentExtractor
```

支持：

- 公开 HTML / SSR 页面读取；
- HTTP 跳转跟随与最终 URL 返回；
- Title 提取；
- 正文抽取；
- Description 等 Metadata；
- 页面 Links 提取；
- `maxChars` 输出限制；
- 服务端响应体大小限制；
- 超时限制。

#### 4. Provider SPI 与自动降级架构

已形成三套独立扩展接口：

```text
SearchProvider
ImageSearchProvider
PageReader
```

上层 Agent 只依赖统一能力接口，不直接绑定具体搜索厂商。

该设计为后续新增海外 Provider、自托管 SearXNG、商业 Search API、Browser Reader 等能力保留扩展空间。

#### 5. Health Check

提供：

```text
GET /api/web/health
```

Health 接口不依赖真实外部搜索 Provider，可用于：

- Docker 健康检查；
- 部署验活；
- OpenReach Skill `init` / `doctor`；
- AgentHub 接入前连通性验证。

#### 6. 内置官网与文档站点

Spring Boot 进程内置静态官网与文档资源：

```text
/          OpenReach 官网
/docs/     快速使用文档
```

无需额外部署前端服务。

#### 7. OpenReach Agent Skill

工程内提供：

```text
skills/openreach/
```

使用 Python 标准库封装：

- `init`；
- `doctor`；
- `search`；
- `image-search`；
- `read`。

支持一次配置 OpenReach Base URL 后复用，适合 Coding Agent / Sandbox Agent / CLI Agent 场景。

#### 8. AgentHub HTTP Plugin

提供 AgentHub 可导入的 HTTP Plugin 描述：

```text
docs/agenthub/skills/openreach-http-plugin.json
```

可将 Search、Image Search、Read 直接作为 Agent Tool 使用。

#### 9. Docker 与一键部署

当前已提供：

- `Dockerfile`；
- `docker-compose.yml`；
- 本地构建 Compose；
- 代理构建 Compose；
- `bin/quick/` 快速脚本；
- Runtime-only 镜像模型；
- amd64 / arm64 多架构发布设计；
- Docker Hub 构建、发布、Smoke Test、Manifest 验证说明。

核心脚本包括：

```text
package.sh
check-project.sh
docker-build.sh
docker-verify.sh
docker-publish.sh
release.sh
smoke-test.sh
```

### Security

#### Read 链路 SSRF 防护

`UrlSafetyGuard` 已覆盖核心 SSRF 边界，包括：

- 非 HTTP/HTTPS Scheme 拦截；
- localhost / loopback 拦截；
- 私网地址拦截；
- DNS 解析后地址校验；
- Redirect 后再次校验；
- 最大 Redirect 数限制；
- 最大响应体限制。

OpenReach 不建设代理池、账号池、验证码绕过或 CAPTCHA 绕过能力。

### Engineering Quality

当前源码中维护：

- **138 个 Java `@Test` 方法**（其中 1 个 QPS Benchmark 默认 opt-in，不进入普通性能门禁）；
- **12 个 OpenReach Skill Python Test Case**；
- 共 **150 个声明测试 Case**；普通 Gate 默认跳过 1 个硬件相关 QPS Benchmark。

Java 测试覆盖：

- Spring Context；
- Region 默认行为；
- 官网 / 静态资源；
- Health API；
- Web Search Provider Parser；
- SearchService fallback / 聚合 / 去重 / 显式 Provider；
- Image Search Provider Parser；
- ImageSearchService fallback / 去重 / 显式 Provider；
- HTML 正文提取；
- SSRF 核心边界。

正式验收门禁仍为：

```bash
mvn clean test
```

必须满足：

```text
Failures = 0
Errors   = 0
BUILD SUCCESS
```

> 本次 CHANGELOG 整理环境中未安装 Maven，因此未在该环境重新执行 `mvn clean test`；上述测试数量按当前源码静态统计。正式 Release 前仍应在项目标准 JDK 17 + Maven 环境执行完整 Gate。

### Known Limitations

当前免费搜索 Provider 多数基于公开 Web 搜索结果做 best-effort 解析，因此不承诺商业 SLA，可能受到以下因素影响：

- 搜索引擎 DOM / JSON Schema 改版；
- A/B 页面结构；
- 403 / 429；
- CAPTCHA；
- 网络出口差异；
- 地区策略；
- 上游临时不可用。

当前尚未完整支持：

- Search Pagination；
- 跨 Provider 统一 Freshness；
- 商业级精确 Geo；
- Shopping / Places / Knowledge Graph 等垂直搜索；
- JavaScript 动态页面 Browser Read；
- PDF / Office Reader；
- 高 QPS / SLA 型 Search API。

### Roadmap

#### v0.1.2 - 海外零 Key 免费渠道增强

### Tool Runner 连接故障与参数适配修复

- 修复 AgentHub HTTP Plugin 把 `http://localhost:8080` 作为默认 `baseUrl` 的跨容器误导：模板改为 `{{BASE_URL}}`，要求导入时显式配置 Tool Runner 可访问地址。
- 新增 `bin/quick/connectivity-test.sh`，从调用方环境按 URL -> DNS -> TCP -> GET `/` -> 无上游 API Probe 定位 `All connection attempts failed`。
- Web Search 请求 JSON 同时兼容 `timeRange` / `time_range`；Web Read 同时兼容 `maxChars` / `max_chars`，适配 Agent Tool 常见 snake_case schema。
- 新增 DTO alias 与 AgentHub Plugin 配置回归测试；补充 Tool Runner / Docker Network 排障文档。


下一阶段重点严格限定为 **无需 API Key / 无需账号 / 零调用费** 的能力：

- Web Search：新增 Brave Web，强化 DuckDuckGo，现有 Bing Provider 全球化；
- Image Search：Bing Images 全球化，新增 Wikimedia Commons，继续保留 Openverse；Brave Images 经过进一步实现评估后降为观察项；
- Routing：新增 CN / GLOBAL Region-aware Provider Chain，保持现有 API / DTO 不变；
- Read：评估 Jina Reader 作为默认关闭的无 Key 远端 fallback，本地 Jsoup 永远第一路；
- 任何需要 API Key / Token / 注册账号或依赖“免费额度”的搜索服务均不进入 v0.1.2 设计范围；
- 海外 Provider 必须通过 Fixture 单测和独立在线 Smoke Test 后才能进入默认链。

详细文档见：

- [`docs/核心市场调研分析/03-海外免费渠道深度调研与v0.1.2接入建议.md`](docs/核心市场调研分析/03-海外免费渠道深度调研与v0.1.2接入建议.md)
- [`docs/设计方案/v0.1.2优化（安全+海外）文档.md`](docs/设计方案/v0.1.2优化（安全+海外）文档.md)

---

## 后续维护规范

建议后续每个版本至少维护：

```text
版本号
发布日期
版本定位
Added
Changed
Fixed
Security
Engineering Quality
Known Limitations
Roadmap
```

若某版本仅为修复，可只保留实际发生变化的栏目。

