# 接口测试与 Curl 示例

> 默认服务地址：`http://localhost:8080`

本文档集中维护当前工程的接口测试样例。README 仅保留能力介绍和启动入口，具体 Curl 统一在这里维护。

---

## 1. 启动前检查

### 本地运行

```bash
mvn clean test
mvn spring-boot:run
```

### Docker 运行

使用已发布镜像：

```bash
docker run -d --name openreach -p 8080:8080 codercl/openreach:latest
```

从当前源码构建并验收：

```bash
./bin/quick/docker-verify.sh
```

如果希望手工 Compose：

```bash
./bin/quick/package.sh && docker compose -f docker-compose.build.yml up -d --build
```

---

## 2. Web Search

### 2.1 Auto 多渠道搜索

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "杭州 AI Agent 开源框架",
    "limit": 5,
    "region": "CN",
    "provider": "auto"
  }'
```

当前 Auto 顺序：

```text
bing → baidu → sogou → so360 → duckduckgo
```

### 2.2 显式指定百度

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "Spring Boot Agent",
    "limit": 5,
    "region": "CN",
    "provider": "baidu"
  }'
```

### 2.3 显式指定 Bing

```bash
curl -sS -X POST 'http://localhost:8080/api/web/search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "Spring Boot AI Agent",
    "limit": 5,
    "region": "CN",
    "provider": "bing"
  }'
```

---

## 3. Image Search

### 3.1 Auto 文搜图

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "杭州西湖夜景",
    "limit": 8,
    "region": "CN",
    "provider": "auto"
  }'
```

当前 Auto 顺序：

```text
bing → baidu → sogou → openverse
```

核心返回字段：

```text
imageUrl
thumbnailUrl
sourcePageUrl
provider
source
domain
width
height
imageFormat
license
licenseUrl
```

### 3.2 Openverse 开放许可图片

```bash
curl -sS -X POST 'http://localhost:8080/api/web/image-search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "Mount Fuji",
    "limit": 5,
    "region": "US",
    "provider": "openverse"
  }'
```

> `license` / `licenseUrl` 只有上游能够提供时才有值。即使搜索结果带 License，也应根据来源页面确认最终使用条件。

---

## 4. Web Read

### 4.1 读取普通网页

```bash
curl -sS -X POST 'http://localhost:8080/api/web/read' \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "https://spring.io/projects/spring-boot/",
    "maxChars": 20000
  }'
```

主要返回：

```text
url
finalUrl
title
content
contentType
reader
truncated
latencyMs
metadata
links
```

### 4.2 SSRF 防护检查

下面请求应该被拒绝，而不是访问本机：

```bash
curl -i -X POST 'http://localhost:8080/api/web/read' \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "http://127.0.0.1:8080",
    "maxChars": 5000
  }'
```

---

## 5. 核心 Agent 使用闭环

当前最核心的 Web Agent 使用方式：

```text
search(query)
   ↓
获得 SearchResult URL
   ↓
read(url)
   ↓
获得正文与来源信息
   ↓
Agent 综合回答 / Citation
```

手工验证时：

1. 先执行 `/api/web/search`；
2. 从返回结果复制一个公网 URL；
3. 传给 `/api/web/read`；
4. 确认返回正文长度、标题、最终 URL 和 metadata 符合预期。

图片场景：

```text
image-search(query)
   ↓
imageUrl + sourcePageUrl
   ↓
read(sourcePageUrl)
   ↓
获得图片上下文 / 来源证据
```

---

## 6. Smoke Test

服务启动后执行：

```bash
./bin/quick/smoke-test.sh
```

Smoke Test 用于公网实际可用性验证，但公网 Search Provider 会受网络、页面结构和限流影响，因此：

> **离线 JUnit 单测是正式代码验收 Gate；公网 Smoke Test 是运行环境验证，两者不要混为一谈。**
