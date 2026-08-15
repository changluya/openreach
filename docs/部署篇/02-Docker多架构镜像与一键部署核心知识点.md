# Docker 多架构镜像与一键部署核心知识点

> 适用于 OpenReach 这类需要同时支持 Intel/AMD 服务器、Apple Silicon Mac、ARM Linux 服务器的开源项目。

> **当前 OpenReach 工程补充：** Dockerfile 已调整为 Runtime-only。文中所有直接 `docker buildx build` 示例，在实际工程中都应先执行 `./bin/quick/package.sh` 生成 `target/openreach-*.jar`；日常发布推荐直接使用 `./bin/quick/release.sh`，它会自动完成宿主机打包、测试、多架构构建、Push 与 Manifest 校验。

---

## 1. 为什么会有“架构不兼容”问题

Docker 镜像最终运行在宿主机 CPU 架构之上。

常见 CPU 架构主要有：

| 宿主机 | 常见架构 | Docker Platform |
|---|---|---|
| Intel / AMD PC、服务器 | x86_64 | `linux/amd64` |
| Apple M1 / M2 / M3 / M4 | ARM64 | `linux/arm64` |
| ARM Linux 服务器 | ARM64 | `linux/arm64` |

例如：

```bash
uname -m
```

可能返回：

```text
x86_64
```

或者：

```text
arm64
```

如果一个 Docker 镜像只提供 `linux/amd64`，而用户机器是 `linux/arm64`，Docker 就可能出现：

```text
no match for platform in manifest
```

因此，一个面向公开用户发布的 Docker 镜像，最好至少支持：

```text
linux/amd64
linux/arm64
```

---

## 2. 单架构镜像是什么

普通情况下执行：

```bash
docker build -t codercl/openreach:0.1.0 .
```

构建出来的镜像通常对应当前构建机器的架构。

例如在 Apple Silicon Mac 上构建：

```text
Mac M 系列
    ↓
linux/arm64
```

在普通 Intel / AMD Linux 上构建：

```text
x86_64
    ↓
linux/amd64
```

这意味着：

```text
同一个 Dockerfile
≠
天然就是多架构镜像
```

Dockerfile 是否能构建多架构，只是前提。

真正发布多架构镜像，还需要使用 Docker Buildx。

---

## 3. 什么是多架构镜像

用户看到的镜像名称可能只有一个：

```text
codercl/openreach:0.1.0
```

但它背后可以同时包含多个架构版本：

```text
codercl/openreach:0.1.0
        │
        ▼
   Manifest List
        │
        ├── linux/amd64
        │     └── sha256:aaa...
        │
        └── linux/arm64
              └── sha256:bbb...
```

这就是 Docker Multi-Platform Image。

核心点：

> 一个镜像 Tag，可以同时对应多个不同 CPU 架构的实际镜像。

用户不需要记住：

```text
openreach-amd64
openreach-arm64
```

也不推荐发布成：

```text
codercl/openreach:amd64
codercl/openreach:arm64
```

更标准的方式是：

```text
codercl/openreach:0.1.0
```

一个 Tag 自动适配多个架构。

---

## 4. Manifest List 是什么

Manifest List 可以理解成一个“镜像架构目录”。

Docker Registry 中：

```text
codercl/openreach:latest
```

并不一定直接指向某一个具体镜像。

它可能先指向：

```text
Manifest List
```

里面记录：

```text
linux/amd64 -> sha256:xxx
linux/arm64 -> sha256:yyy
```

客户端执行：

```bash
docker pull codercl/openreach:latest
```

Docker 会先读取 Manifest List。

然后检查当前机器架构：

```text
当前机器 = amd64
    ↓
拉取 amd64 镜像

当前机器 = arm64
    ↓
拉取 arm64 镜像
```

所以正常情况下，**客户不需要手工选择架构**。

---

## 5. 客户端为什么可以一条命令启动

最终希望用户只执行：

```bash
docker run -d   --name openreach   --restart unless-stopped   -p 8080:8080   codercl/openreach:latest
```

Docker 内部大致执行：

```text
docker run
   ↓
访问 Docker Registry
   ↓
读取 openreach:latest Manifest
   ↓
识别当前宿主机 CPU
   ↓
自动选择 amd64 / arm64
   ↓
下载对应镜像
   ↓
启动容器
```

用户不需要关心：

```text
自己的 CPU 是什么
镜像架构是什么
应该下载哪个版本
```

这才是真正适合开源项目的一键部署体验。

---

## 6. 什么情况下才需要 `--platform`

正常用户不需要写：

```bash
--platform linux/amd64
```

或者：

```bash
--platform linux/arm64
```

只有特殊场景才需要。

例如：

### 场景一：ARM Mac 强制测试 AMD64

```bash
docker run   --platform linux/amd64   codercl/openreach:latest
```

这种情况下通常依赖 QEMU / 虚拟化模拟。

主要用于：

```text
兼容性测试
CI 调试
排查特定架构问题
```

不是推荐给普通用户的部署方式。

---

## 7. Buildx 是什么

Docker Buildx 是 Docker 用来进行高级构建的工具。

普通构建：

```bash
docker build .
```

通常偏向：

```text
当前机器架构
```

Buildx 可以：

```text
多架构构建
缓存
远程 Builder
并行构建
直接 Push Registry
```

OpenReach 发布正式镜像时推荐：

```bash
docker buildx build   --platform linux/amd64,linux/arm64   -t codercl/openreach:0.1.0   -t codercl/openreach:latest   --push   .
```

这里：

```text
--platform
```

指定目标架构。

```text
-t
```

设置镜像 Tag。

```text
--push
```

构建完成后直接推送到 Docker Hub。

---

## 8. 为什么多架构构建一般使用 `--push`

多架构构建会同时生成：

```text
amd64 image
arm64 image
manifest list
```

这些内容最适合直接写入 Registry。

因此常用：

```bash
docker buildx build   --platform linux/amd64,linux/arm64   --push   .
```

而不是：

```bash
docker buildx build ...
docker push ...
```

对于多架构镜像，Buildx 直接 `--push` 会更自然。

---

## 9. 基础镜像本身也必须支持多架构

这是非常容易忽略的一点。

即使你的项目准备发布：

```text
linux/amd64
linux/arm64
```

如果 Dockerfile 中：

```dockerfile
FROM xxx:tag
```

这个基础镜像只支持 amd64，那么 arm64 构建仍然会失败。

例如曾经出现：

```text
no match for platform in manifest
```

本质就是：

```text
目标构建：linux/arm64

基础镜像：
xxx:tag
只有 linux/amd64

        ↓

构建失败
```

所以选基础镜像时，要确认：

```text
amd64 ✅
arm64 ✅
```

OpenReach 更适合使用同时支持多个架构的 JDK/JRE 基础镜像。

---

## 10. Alpine、Ubuntu、Debian 与 CPU 架构不是一回事

很多人容易把：

```text
Alpine
Ubuntu
Debian
```

和：

```text
AMD64
ARM64
```

混在一起。

实际上是两个维度。

例如：

```text
eclipse-temurin:17-jre-alpine
```

描述的是：

```text
Java 17
+
JRE
+
Alpine Linux
```

但这个具体 Tag 是否支持：

```text
amd64
arm64
```

还要单独看它的 Manifest。

因此：

> Linux 发行版类型 ≠ CPU 架构。

---

## 11. 为什么不能简单写死 `--platform=linux/amd64`

面对 ARM 构建失败时，一个临时绕过方式可能是：

```bash
docker build   --platform linux/amd64   .
```

这样 Apple Silicon Mac 也可以尝试构建 AMD64 镜像。

但这不应该成为 OpenReach 的正式方案。

因为：

```text
强制 amd64
   ↓
ARM 用户运行时可能需要模拟
   ↓
性能下降
   ↓
兼容性复杂
```

更合理的是直接发布：

```text
amd64 原生镜像
+
arm64 原生镜像
```

---

## 12. 如何检查本机架构

可以执行：

```bash
uname -m
```

Docker 也可以：

```bash
docker info
```

或者：

```bash
docker version
```

Apple Silicon 通常是：

```text
arm64 / aarch64
```

普通 Intel / AMD 服务器通常是：

```text
x86_64 / amd64
```

---

## 13. 如何检查一个远程镜像支持哪些架构

发布完后建议执行：

```bash
docker buildx imagetools inspect codercl/openreach:0.1.0
```

理想情况下应该看到：

```text
linux/amd64
linux/arm64
```

这一步非常重要。

不要只验证：

```bash
docker push 成功
```

因为 Push 成功并不代表：

```text
两个架构都存在
```

真正的多架构发布验收应该是：

```text
Manifest 存在
+
amd64 存在
+
arm64 存在
```

---

## 14. Docker Hub 在这个过程中扮演什么角色

Docker Hub 可以理解为：

```text
Docker 镜像仓库
```

类似：

```text
GitHub
    ↓
存代码

Docker Hub
    ↓
存 Docker 镜像
```

OpenReach 源代码：

```text
github.com/codercl/openreach
```

OpenReach Docker 镜像：

```text
docker.io/codercl/openreach
```

用户不需要 Clone 源码。

直接：

```bash
docker run codercl/openreach:latest
```

Docker 会自动从 Docker Hub 下载镜像。

---

## 15. Docker Hub Repository、Image、Tag 的关系

可以理解成：

```text
Docker Hub Account
└── codercl
    └── Repository
        └── openreach
            ├── Tag: latest
            ├── Tag: 0.1.0
            ├── Tag: 0.1.1
            └── Tag: 0.2.0
```

完整镜像名称：

```text
codercl/openreach:0.1.0
```

拆解：

```text
codercl
    ↓
Namespace / Docker ID

openreach
    ↓
Repository

0.1.0
    ↓
Tag
```

---

## 16. `latest` 到底是什么

`latest` 并不表示 Docker 自动识别“最新版本”。

它本质上只是一个普通 Tag：

```text
latest
```

是发布者人为维护的。

例如当前正式稳定版本：

```text
0.1.0
```

可以同时推：

```text
codercl/openreach:0.1.0
codercl/openreach:latest
```

以后发布：

```text
0.2.0
```

再把：

```text
latest
```

更新到 0.2.0 对应镜像。

---

## 17. 开源项目推荐的版本策略

建议 OpenReach 同时维护：

```text
精确版本：
0.1.0

稳定入口：
latest
```

普通用户：

```bash
docker run codercl/openreach:latest
```

生产环境：

```bash
docker run codercl/openreach:0.1.0
```

原因是生产环境更希望：

```text
版本固定
可复现
可回滚
```

而不是每次重启都可能拉到不同版本。

---

## 18. `docker run` 和 `docker compose` 的区别

### docker run

适合非常轻量的单服务：

```bash
docker run -d   -p 8080:8080   codercl/openreach:latest
```

优点：

```text
一条命令
最简单
```

---

### docker compose

适合后面 OpenReach 增加：

```text
数据库
浏览器服务
缓存
代理
监控
```

例如：

```yaml
services:
  openreach:
    image: codercl/openreach:latest
    ports:
      - "8080:8080"
```

用户：

```bash
docker compose up -d
```

OpenReach V1 当前是单服务，因此：

> README 主推荐 `docker run`，同时保留 Compose 作为复杂部署入口。

---

## 19. `build:` 和 `image:` 的区别

Compose 中：

```yaml
build:
  context: .
```

表示：

```text
用户需要源码
+
用户本地构建镜像
```

而：

```yaml
image: codercl/openreach:latest
```

表示：

```text
直接从 Registry 拉镜像
```

如果目标是“一键部署”，正式发布后更推荐：

```yaml
image: codercl/openreach:latest
```

而不是要求用户：

```text
git clone
docker build
```

---

## 20. Dockerfile 多阶段构建

Java 项目很适合使用 Multi-stage Build。

例如：

```text
Stage 1
Maven + JDK
    ↓
编译
测试
打 Jar

Stage 2
JRE
    ↓
复制 Jar
运行
```

好处：

```text
最终镜像不需要 Maven
不需要完整 JDK
体积更小
攻击面更小
```

OpenReach 当前就适合这种模式。

---

## 21. 为什么建议在 Docker 构建阶段跑测试

例如：

```dockerfile
RUN mvn clean package
```

默认情况下 Maven 会执行测试。

这样：

```text
docker build
    ↓
JUnit
    ↓
测试失败
    ↓
Docker Build 失败
```

形成一个最基础的发布 Gate：

> 测试不过，不生成正式镜像。

对于开源项目非常有价值。

---

## 22. Buildx 在 ARM Mac 上构建 AMD64 的原理

假设发布机器是：

```text
Apple Silicon
linux/arm64
```

但要求同时构建：

```text
linux/amd64
linux/arm64
```

其中：

```text
arm64
```

可以原生执行。

而：

```text
amd64
```

通常可能通过 QEMU / binfmt 模拟执行。

因此：

```text
跨架构构建
```

一般会比原生架构慢。

这属于正常现象。

---

## 23. 本地构建和正式发布应该分开理解

开发阶段可以：

```bash
docker build -t openreach:dev .
```

目的：

```text
快速验证当前机器
```

正式发布阶段：

```bash
docker buildx build   --platform linux/amd64,linux/arm64   ...
  --push
```

目的：

```text
生成公开多架构镜像
```

不要把：

```text
本地 docker build 成功
```

等同于：

```text
正式多架构镜像发布完成
```

---

## 24. OpenReach 推荐的完整发布流程

建议未来形成：

```text
代码提交
   ↓
单元测试
   ↓
Git Tag
   ↓
Docker Buildx
   ↓
┌───────────────┐
│ linux/amd64   │
│ linux/arm64   │
└───────────────┘
   ↓
Manifest List
   ↓
Docker Hub
   ↓
0.1.0 + latest
```

对应命令：

```bash
docker buildx build   --platform linux/amd64,linux/arm64   -t codercl/openreach:0.1.0   -t codercl/openreach:latest   --push   .
```

---

## 25. 客户最终应该看到什么

普通用户不应该看到复杂的 Buildx、Manifest、CPU 架构知识。

README 应该只需要：

```bash
docker run -d   --name openreach   --restart unless-stopped   -p 8080:8080   codercl/openreach:latest
```

高级部署文档再解释：

```text
AMD64
ARM64
Buildx
Manifest List
Registry
```

这符合开源项目的体验原则：

> 发布者承担复杂度，使用者获得简单接口。

---

## 26. 推荐 OpenReach 支持的架构范围

初期没有必要支持所有 CPU。

建议：

| Platform | 优先级 | 原因 |
|---|---:|---|
| `linux/amd64` | 必须 | 主流云服务器 / Intel AMD |
| `linux/arm64` | 必须 | Apple Silicon / ARM Server |
| `linux/arm/v7` | 暂不支持 | 使用场景较少 |
| Windows Container | 暂不支持 | 当前 OpenReach 面向 Linux Container |

所以 V1 最合理的是：

```text
linux/amd64
linux/arm64
```

---

## 27. 常见误区总结

### 误区 1

```text
Dockerfile 能构建
=
支持所有架构
```

错误。

基础镜像和构建过程都必须兼容目标架构。

---

### 误区 2

```text
ARM 用户自己加 --platform
```

不推荐。

应该由发布者提供 ARM 原生镜像。

---

### 误区 3

```text
amd64 和 arm64 发布两个不同镜像名
```

可以做到，但用户体验差。

更推荐一个 Tag + Manifest List。

---

### 误区 4

```text
latest 自动代表最新版本
```

错误。

`latest` 是发布者维护的普通 Tag。

---

### 误区 5

```text
docker push 成功
=
多架构发布成功
```

错误。

还需要检查：

```bash
docker buildx imagetools inspect ...
```

确认两个平台都存在。

---

## 28. OpenReach 推荐最终形态

```text
                     OpenReach
                         │
                         ▼
                   Docker Buildx
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
        linux/amd64             linux/arm64
              │                     │
              └──────────┬──────────┘
                         ▼
                  Manifest List
                         │
                         ▼
                 Docker Registry
                         │
              codercl/openreach
                         │
             ┌───────────┴───────────┐
             ▼                       ▼
           0.1.0                   latest
                         │
                         ▼
                    docker run
                         │
                         ▼
             自动识别客户端 CPU 架构
```

核心思想只有一句话：

> **OpenReach 发布一个多架构镜像，Docker 根据客户机器架构自动选择正确的镜像版本，客户不需要手动判断 AMD64 或 ARM64。**

