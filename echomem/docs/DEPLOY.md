# 部署 — echomem 文档站

> **Service 范围**：`lakeon/echomem/docs/` 子树作为独立 Railway service，与 lakeon-console / lakeon-admin 是同 repo 内的不同 service。

## 架构

```
lakeon/echomem/docs/        ← 独立 Railway service
├── Dockerfile              caddy:2.10-alpine + 白名单 COPY
├── Caddyfile               入口 redirect + 安全 headers + gzip
├── railway.json            Railway build/deploy 配置
├── .dockerignore           双重保险排除杂项
├── DEPLOY.md               本文档
├── overview/               项目概览（markdown 进 docsify）
├── architecture/           手写 HTML + SVG 架构图
├── roadmap/                手写 HTML + SVG 时间线 + phase 状态卡
├── plans/                  对外精简版 phase 计划（markdown 进 docsify）
└── docs-viewer/            docsify 渲染层（index.html, _sidebar.md, _navbar.md, README.md）
```

## 一次性 setup

1. push 当前 repo 到 GitHub
   ```bash
   git push origin main
   ```

2. 打开 [railway.com/new](https://railway.com/new)，选 lakeon repo

3. 关键 service 配置（Settings → Source）：
   - **Root Directory**: `lakeon/echomem/docs`
   - **Dockerfile Path**: `Dockerfile`（相对 Root Directory）
   - **Watch Paths**: `lakeon/echomem/docs/**`（仅 docs/ 改动触发重部署）

4. Railway 自动 build：
   - Build context = `echomem/docs/`
   - 时间 ~1 min（Caddy alpine ~50MB + 站点文件 <100KB）
   - Settings → Networking → **Generate Domain** → 拿 `*.up.railway.app` URL

5. 访问 URL，自动 302 redirect 到 `/architecture/`（最直观入口）

## 后续更新

每次 `git push origin main` 改 `lakeon/echomem/docs/**` → Railway 自动重部署。

改 `lakeon/console/`、`lakeon/api/` 等其他 service 的代码 **不会触发** docs service 重部署。

## URL 结构（部署后）

| 路径 | 内容 |
|---|---|
| `/` | 自动 302 redirect 到 `/architecture/` |
| `/architecture/` | 架构页（手写 HTML + SVG 分层图）|
| `/roadmap/` | 路线图（手写 HTML + SVG 时间线 + 五阶段卡片）|
| `/docs-viewer/` | docsify 文档中心首页 |
| `/docs-viewer/#/overview/` | 项目概览 |
| `/docs-viewer/#/plans/phase1-backbone` | Phase 1 对外精简计划 |
| `/docs-viewer/#/plans/phase2-derivatives` | Phase 2 |
| `/docs-viewer/#/plans/phase3-context-api` | Phase 3 |

## 故意不部署的内容

内部 spec 与完整 plan 留在 `lakeon/docs/superpowers/` 下不进镜像：
- `docs/superpowers/specs/2026-04-30-echomem-design.md`（706 行完整 spec）
- `docs/superpowers/plans/2026-04-30-echomem-phase1-backbone.md`（详细内部计划）
- `docs/superpowers/plans/2026-05-01-echomem-phase2-derivatives.md`
- `docs/superpowers/plans/2026-05-06-echomem-phase3-context-api.md`

对外站点用的是 `lakeon/echomem/docs/plans/` 下重新写过的精简版。

## 自定义域名（可选）

Railway dashboard → echomem-docs service → Settings → Networking → **Custom Domain**：
1. 输入域名（如 `echomem.lakeon.cloud`）
2. Railway 给一个 CNAME 目标
3. DNS 后台配 CNAME → 自动 issue TLS 证书

## 本地预览

```bash
cd lakeon/echomem/docs
docker build -t echomem-docs:dev .
docker run --rm -p 8080:8080 echomem-docs:dev
# 浏览器开 http://localhost:8080/
# 注意：如果系统设了 HTTP 代理，curl 要加 --noproxy '*'
```

## 监控

- Railway 自带 healthcheck，配置在 `railway.json` 的 `healthcheckPath: /architecture/`
- Caddy 访问日志走 stdout，Railway dashboard → Logs 查看
