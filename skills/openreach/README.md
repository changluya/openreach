# OpenReach Skill

官网可直接下载的 OpenReach Python Skill。无需第三方 Python 依赖。

## 1. 初始化服务器

```bash
python3 scripts/openreach.py init 192.168.1.20
```

成功后会生成 `config.json`，后续自动使用该地址。

## 2. 连通性检查

```bash
python3 scripts/openreach.py doctor
```

## 3. 使用

```bash
python3 scripts/openreach.py search "AI Agent" --region auto --provider auto --limit 5
python3 scripts/openreach.py image-search "杭州西湖" --region auto --provider auto --limit 8
python3 scripts/openreach.py read "https://spring.io/projects/spring-boot/" --max-chars 20000
```

详细 Agent Search SOP 见 [SKILL.md](SKILL.md)。
