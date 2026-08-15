# Changelog

OpenReach 的版本更新记录。

本文件从 **v1.0.1** 开始作为正式维护基线，后续版本按 `Added / Changed / Fixed / Security / Known Limitations / Roadmap` 持续记录，避免能力演进只散落在 README、设计文档或提交记录中。

> 版本说明：正式维护口径从 **v1.0.1** 起记录；v1.0.2 已同步 Maven Artifact、README、运行文档与部署示例。`docs/设计方案/v1.0.1设计访问文档.md` 统一收敛首个正式基线设计、渠道与验收口径；早期市场调研继续保留历史语境。

---

## [v1.0.2] - 2026-08-15

### 版本定位

v1.0.2 在 v1.0.1 国内免费渠道基线之上，把 OpenReach 从“单一国内优先 Provider Chain”升级为**由 `region` 驱动的 CN / GLOBAL Web Access Router**，并继续坚持默认链 **零 API Key、零账号依赖、零商业 Search 调用费**。

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
- 页面直接按本文件的 v1.0.2 / v1.0.1 事实源展示能力变化、兼容边界和安全说明；
- 文档侧边栏与移动端文档切换同步增加“更新日志”；
- v1.0.1 中历史存在的 `/api/web/health` 在页面中明确标注为已于 v1.0.2 移除，避免用户按旧版本调用。

### Changed

- Maven Artifact 版本升级为 `1.0.2`；
- `SearchService` / `ImageSearchService` 改为 Route-aware Provider Chain；
- 自动链对空 Provider Order 给出明确配置错误；
- Provider Order 会 trim / lowercase，并跳过空名称，提升配置容错；
- `ImageSearchHttpClient` 的地区 Header 改为覆盖默认值，避免重复 `Accept-Language`；
- README、API、Curl、核心设计、当前能力、部署文档、OpenReach Skill 与 AgentHub Plugin 同步 `timeRange`、图片可下载语义和安全边界；
- `docs/agenthub/接口文档/OpenReach接口文档.md` 已按当前 DTO / Service / `application.yml` / AttackSurfaceFilter 重新对齐参数长度、64 KiB API Body、完整错误码、Provider 上游 Body 限制及图片下载验证配置；
- 官网 inline theme script 移为 `/assets/theme-init.js`，配合 `script-src 'self'` CSP；
- `check-project.sh` / `package.sh` 将 Skill Python Test 纳入正式门禁；新增 `build-skill-zip.sh`，打包前自动刷新官网 Skill ZIP，避免 Skill 源码与下载包不一致；
- 设计文档进一步按版本收敛：v1.0.1 统一为 `docs/设计方案/v1.0.1设计访问文档.md`，v1.0.2 的海外与安全设计统一为 `docs/设计方案/v1.0.2优化（安全+海外）文档.md`。

### Fixed

- 修正 v1.0.1 DuckDuckGo 简单 GET 模式的脆弱性，改为当前 no-JS HTML 表单模型；
- 修正海外请求虽传 `region` 但 Auto Chain 仍优先国内 Provider 的架构问题；
- 修正 Bing Web / Image 固定中国 Host、无法按地区切换的问题；
- 修正图片 HTTP 请求追加 Locale Header 可能形成重复 Header 的问题；
- 修正内置非空 `cn-provider-order` 可能遮蔽外部 v1.0.1 `provider-order` 的兼容隐患：v1.0.2 内置值保持空列表，由运行时继承旧字段；
- 空结果 / null 结果在 Auto Chain 中统一视为本 Provider 失败并继续 fallback；
- 修复 Search 缺少结构化时间范围的问题；
- 修复 Image Search 可能返回失效热链、HTML 防盗链页或伪图片 URL 的质量问题；
- 修复公网攻击面过宽：Health/未知 Controller 路径、Multipart 等现在由统一 Filter 默认拒绝；
- 服务端未知异常不再把原始异常消息直接返回客户端，降低实现细节泄露。
- 修复官网首页、快速文档与 API 文档复制按钮在 HTTP / 非安全上下文下无响应的问题：优先使用 Clipboard API，受浏览器安全策略限制时自动降级到 selection + `execCommand('copy')`，并增加复制成功/失败状态反馈。

### Tests

当前源码静态统计：

```text
Java @Test              105
OpenReach Skill Python   12
合计                    117
```

v1.0.2 新增/扩展覆盖重点：

- `SearchRouteResolver`：auto、default-route、CN aliases、非 CN → GLOBAL、非法默认值 fail-safe；
- `ProviderChainResolver`：CN legacy fallback、显式 CN Chain、GLOBAL Web/Image Chain 独立解析；
- `RegionLocaleSupport`：CN / US / JP / worldwide locale 映射；
- `WebCapabilityProperties`：v1.0.2 默认链、CN legacy 配置 fallback 与显式 CN 链覆盖；
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
# Java @Test = 105, Skill Python Test = 12
```

Java 正式门禁仍是：

```bash
mvn clean test
```

当前交付容器未预装 Maven，因此本次官网 / 文档维护无法诚实宣称 Java `mvn clean test` 的 `BUILD SUCCESS`；已执行 Skill 全量 Python Test 与官网静态资源/路由/Allowlist 校验。标准 JDK 17 + Maven 环境仍应把 `mvn clean test` 作为最终 Release Gate。

### Known Limitations

- Brave / DuckDuckGo / Bing 免费 Web SERP 仍是 best-effort，可能受到 DOM 改版、403/429、CAPTCHA、出口网络与地区策略影响；
- v1.0.2 不做 Search Pagination、商业级 Geo、账号池、代理池或 CAPTCHA 绕过；
- Brave Images 因当前公开页面依赖较脆弱的内嵌 JS/Svelte 状态，未进入默认链；
- Read 主链保持本地 SafeHttpFetcher + Jsoup，不引入远端 Key/账号依赖。

### Docs

- [`docs/设计方案/v1.0.2优化（安全+海外）文档.md`](docs/设计方案/v1.0.2优化（安全+海外）文档.md)
- [`docs/设计方案/v1.0.1设计访问文档.md`](docs/设计方案/v1.0.1设计访问文档.md)
- [`docs/核心市场调研分析/03-海外免费渠道深度调研与v1.0.2接入建议.md`](docs/核心市场调研分析/03-海外免费渠道深度调研与v1.0.2接入建议.md)

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

- **38 个 Java `@Test` Case**；
- **5 个 OpenReach Skill Python Test Case**；
- 共 **43 个测试 Case**。

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

#### v1.0.2 - 海外零 Key 免费渠道增强

下一阶段重点严格限定为 **无需 API Key / 无需账号 / 零调用费** 的能力：

- Web Search：新增 Brave Web，强化 DuckDuckGo，现有 Bing Provider 全球化；
- Image Search：Bing Images 全球化，新增 Wikimedia Commons，继续保留 Openverse；Brave Images 经过进一步实现评估后降为观察项；
- Routing：新增 CN / GLOBAL Region-aware Provider Chain，保持现有 API / DTO 不变；
- Read：评估 Jina Reader 作为默认关闭的无 Key 远端 fallback，本地 Jsoup 永远第一路；
- 任何需要 API Key / Token / 注册账号或依赖“免费额度”的搜索服务均不进入 v1.0.2 设计范围；
- 海外 Provider 必须通过 Fixture 单测和独立在线 Smoke Test 后才能进入默认链。

详细文档见：

- [`docs/核心市场调研分析/03-海外免费渠道深度调研与v1.0.2接入建议.md`](docs/核心市场调研分析/03-海外免费渠道深度调研与v1.0.2接入建议.md)
- [`docs/设计方案/v1.0.2优化（安全+海外）文档.md`](docs/设计方案/v1.0.2优化（安全+海外）文档.md)

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
