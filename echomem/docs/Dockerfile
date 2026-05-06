FROM caddy:2.10-alpine
# v4 — basicauth with echomem@2026

# echomem docs site - Build context = echomem/docs/
# Railway service 配置: Root Directory = echomem/docs/
#
# 白名单 COPY — 显式列出每个要部署的目录/文件
# (站点只暴露对外友好版本；内部 plan/spec 不进镜像)

WORKDIR /srv

# 痛点页（首页）
COPY pain-points/ /srv/pain-points/

# 站点首页 + 概览
COPY overview/ /srv/overview/

# 架构图 (手写 HTML + SVG)
COPY architecture/ /srv/architecture/

# Roadmap (手写 HTML + 甘特/现状卡片)
COPY roadmap/ /srv/roadmap/

# 怎么用（快速上手 + API 文档）
COPY how-to-use/ /srv/how-to-use/

# 改写后的对外版 phase plan
COPY plans/ /srv/plans/

# Docsify markdown 渲染层
COPY docs-viewer/ /srv/docs-viewer/

# Caddy 配置
COPY Caddyfile /etc/caddy/Caddyfile

EXPOSE 8080
