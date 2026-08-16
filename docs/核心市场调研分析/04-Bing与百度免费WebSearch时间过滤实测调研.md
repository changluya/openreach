# Bing 与百度免费 Web Search 时间过滤实测调研

> 日期：2026-08-16  
> 范围：仅研究 **无需 API Key、无需付费账号** 的公开 Web 搜索页面参数，不接 Bing/Azure/Baidu AI Search 等商业 API。

## 1. 结论

| Provider | day | week | month | year | 本次处理 |
|---|---:|---:|---:|---:|---|
| 百度免费 Web | ✅ | ✅ | ✅ | ✅ | 全量接入 |
| Bing 免费 Web | ✅ | ✅ | ✅ | ⚠️ 未验证稳定参数 | day/week/month 接入；year 主动跳过 |

核心原则：**只有真实上游执行时间过滤的能力才声明支持，不能为了减少 502 而静默忽略 `timeRange`。**

## 2. 百度：真实结果页参数验证

从当前可访问的百度搜索结果页 URL 可以直接观察到：

```text
gpc=stf=<startEpoch>,<endEpoch>|stftype=1
tfflag=1
timefactor=<21|22|23|24>
```

对公开结果页中的 start/end 做差得到：

| timefactor | start/end 差值 | OpenReach 映射 |
|---:|---:|---|
| 21 | 1 天 | day |
| 22 | 7 天 | week |
| 23 | 30 天 | month |
| 24 | 365 天 | year |

因此本次 `BaiduSearchProvider` 直接以当前 epoch second 为结束时间，按 1/7/30/365 天回推开始时间，并同时发送 `gpc + tfflag + timefactor`。

示例结构：

```text
/s?word=AI+融资+投资+最新
  &ie=utf-8
  &rn=10
  &gpc=stf%3D<start>%2C<end>%7Cstftype%3D1
  &tfflag=1
  &timefactor=21
```

这仍然是百度公开网页搜索，不需要 Key。

## 3. Bing：免费网页参数验证

Bing 官方 Search Blog 明确存在 Past 24 hours / Past week / Past month 网页筛选。2026-07 的 Microsoft Q&A 仍给出了当前网页结果 URL 的实际参数：

```text
Past 24 hours -> filters=ex1:"ez1"
Past week     -> filters=ex1:"ez2"
Past month    -> filters=ex1:"ez3"
```

本轮没有找到足够可靠的 Bing **免费 Web** `year` 参数证据。虽然 Bing 的其他产品/接口存在 Past year/freshness year 语义，但这不能直接证明普通 Web SERP 有一个稳定、可长期依赖的 year URL 参数。

因此：

```text
Bing DAY   = supported
Bing WEEK  = supported
Bing MONTH = supported
Bing YEAR  = unsupported（不猜 ez4，不静默忽略）
```

另外，2026-07 的 Bing 国内版用户反馈显示时间筛选 UI/服务存在地区差异。为降低 `cn.bing.com` 忽略筛选的风险，OpenReach 在 Bing restricted timeRange 场景会使用 `www.bing.com/search`，但继续携带原 region 的 `cc/setlang` Hint；普通 `timeRange=any` 仍保持原 CN/GLOBAL Host 路由。

## 4. 为什么要把 capability 从 boolean 升级成 range-aware

旧接口：

```java
boolean supportsTimeRange();
```

无法表达 Bing 这种部分支持：

```text
day/week/month = true
year = false
```

因此新增兼容式 default 方法：

```java
boolean supportsTimeRange(SearchTimeRange timeRange);
```

已有第三方 Provider 无需改代码；如果原来只覆盖 `supportsTimeRange()`，新方法会自动继承旧语义。Bing 单独覆盖 range-aware 方法。

## 5. 新的默认时间过滤链

```text
CN:
  baidu -> bing -> duckduckgo -> brave

GLOBAL:
  bing -> brave -> duckduckgo -> baidu
```

这是候选顺序，不表示每个范围都会执行所有 Provider。SearchService 会在运行时调用：

```java
provider.supportsTimeRange(timeRange)
```

例如 `timeRange=year`：

```text
CN: baidu -> bing(skip) -> duckduckgo -> brave
```

如果百度已经达到 `limit`，后面的 Provider 不会继续请求。

## 6. 单测覆盖

新增/增强：

- `BaiduSearchProviderTest`
  - day/week/month/year 都声明支持；
  - 固定 endEpoch，精确断言 1/7/30/365 天区间；
  - 精确断言 `timefactor=21/22/23/24`；
  - 百度安全验证 / captcha 页面识别为 bot challenge，避免误记成 parse empty；
  - `any` 不附加时间参数。
- `BingSearchProviderTest`
  - day/week/month 支持；year 不支持；
  - 精确断言 `ez1/ez2/ez3`；
  - CN restricted range 改走 `www.bing.com`；
  - year 显式调用 fail-fast，不允许静默忽略。
- `SearchServiceTest`
  - range-aware Provider 能力路由；
  - month 可以选择 Bing；
  - year 自动跳过 Bing 并继续支持 year 的 Provider。

## 7. 风险边界

Bing / 百度当前仍是免费公开 Web SERP，属于 best-effort：

- URL 参数或 DOM 可能改版；
- 搜索站点可能根据 IP、地区、请求频率、Cookie、UA 等返回不同页面；
- 可能发生 403 / 429 / bot challenge；
- 时间过滤是上游搜索引擎语义，不等于结果中的每条内容都一定带可机器验证的发布时间。

因此后续线上要结合 OpenReach 已有 `traceId + upstream log` 观察 Provider 成功率，并保留多 Provider fallback。

## 8. 调研来源

- Bing Search Blog: `What a Difference a Day Makes`（Past 24 hours / week / month）
- Microsoft Q&A（2026-07）: Bing 网页 URL `filters=ex1:"ez1/ez2/ez3"` 的当前使用反馈
- 百度当前公开搜索结果页：可观察 `gpc=stf=...|stftype=1`、`tfflag=1`、`timefactor=21/22/23/24`

> 注意：Microsoft Q&A 中的具体参数说明属于微软站点上的社区答复；因此本实现仍按“免费 Web best-effort”定位，不把它当作稳定商业 API 契约。
