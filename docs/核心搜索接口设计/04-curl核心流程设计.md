# Curl 核心流程设计（v0.1.4）

## 1. 目标

`POST /api/web/curl` 是 OpenReach v0.1.4 新增的**安全只读公网 HTTP 能力**，用于补齐 Search / Read 不适合直接处理的机器可读资源，例如 GitHub REST API、`raw.githubusercontent.com` 源码、公开 JSON/XML/YAML/text API。

它不是 shell `curl` 代理，也不允许任意 Method。v0.1.4 仅支持 `GET / HEAD`，不携带登录态，不做文件上传，不支持私网访问。

## 2. 核心链路

```text
Agent
  -> POST /api/web/curl
  -> AttackSurfaceFilter(JSON + 64KiB request limit)
  -> CurlTargetGuard
       1. HTTP/HTTPS only
       2. public IP only
       3. port 80/443 only
       4. reject OpenReach itself
       5. reject configured self aliases
  -> CurlService
       1. GET/HEAD only
       2. reject credential/proxy spoofing headers
       3. no Cookie/Authorization
       4. redirect -> CurlTargetGuard again
       5. textual response only
       6. response bytes/chars bounded
  -> CurlResponse
```

## 3. “禁止请求自己”设计

Curl 在共享 SSRF 防护之外增加了自请求阻断：

- 当前入站请求的 `Host`；
- Servlet `serverName` / `localName` / `localAddr`；
- 当前自身 Host/serverName 解析出的公网 IP（可阻断指向同一入口 IP 的其他别名）；
- OpenReach 进程/容器所有本地网卡地址；
- `OPENREACH_CURL_BLOCKED_HOSTS` 配置的额外公网域名/别名（支持 `*.example.com`）。

因此即使 OpenReach 通过公网域名、Nginx 或 LB 暴露，也不能用 Curl 直接回打同一服务。若同一部署存在多个公网别名，应通过 `OPENREACH_CURL_BLOCKED_HOSTS` 全量补充。

## 4. GitHub 源码阅读 SOP

```text
Search: site:github.com <topic/repository>
  -> 找到 owner/repo
  -> Curl: https://api.github.com/repos/<owner>/<repo>
  -> Curl: https://api.github.com/repos/<owner>/<repo>/contents/<path>
  -> 或直接 Curl: https://raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>
  -> Agent 阅读 JSON / raw source
```

GitHub 匿名 API 存在官方限流；OpenReach 不绕过限流，也不内置 Token。需要认证调用时应由上层专用 GitHub Connector 处理，而不是把 `Authorization` 注入 OpenReach Curl。

## 5. API 示例

```bash
curl -sS -X POST 'http://localhost:8080/api/web/curl' \
  -H 'Content-Type: application/json' \
  -d '{
    "url":"https://api.github.com/repos/spring-projects/spring-boot",
    "method":"GET",
    "maxChars":100000
  }'
```

读取 raw 源码：

```bash
curl -sS -X POST 'http://localhost:8080/api/web/curl' \
  -H 'Content-Type: application/json' \
  -d '{
    "url":"https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.adoc",
    "method":"GET"
  }'
```

## 6. 明确禁止

- OpenReach 自身 URL / 当前部署公网域名；
- `localhost`、回环、RFC1918、link-local、metadata、保留地址；
- 非 80/443；
- POST/PUT/PATCH/DELETE 等写方法；
- `Authorization`、`Cookie`、`Host`、`X-Forwarded-*` 等头；
- 二进制下载；
- 登录绕过、验证码/WAF 绕过、端口扫描或内网代理。
