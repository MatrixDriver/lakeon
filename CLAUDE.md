# Lakeon Project

## Project Structure

```
lakeon-console/    # Vue 3 + Vite 前端控制台
lakeon-admin/      # Vue 3 + Vite SRE 运维控制台
lakeon-api/        # Spring Boot 3.3.5 (Java 17) 后端 API
lakeon-orchestrator/ # Python 3.11+ FastAPI 生产线编排引擎
dbay-cli/          # Python Typer CLI 工具
tests/e2e/         # pytest API E2E 测试
deploy/            # Helm + CCE 部署脚本
```

## Tech Stack

- **Frontend**: Vue 3, TypeScript 5.9, Vite, Pinia, Vue Flow, CodeMirror
- **Backend**: Spring Boot 3.3.5, Java 17, JPA, PostgreSQL
- **Orchestrator**: Python 3.11+, FastAPI, Ray, SQLAlchemy
- **CLI**: Python 3.11+, Typer, httpx
- **Testing**: Vitest (unit), Playwright (browser E2E), pytest (API E2E)

## Build & Test Commands

```bash
# 前端
cd lakeon-console
npm run dev          # 启动 dev server (localhost:5173)
npm run build        # vue-tsc -b && vite build
npm run test         # vitest 单元测试
npm run test:e2e     # Playwright 浏览器 E2E (自动启动 dev server)

# API E2E
python3 -m pytest tests/e2e/test_pipeline.py -v

# Type check (push 前自动执行)
cd lakeon-console && npx vue-tsc -b --noEmit
cd lakeon-admin && npx vue-tsc -b --noEmit
```

## Deployment

**默认站点: hwstaff** (SITE=hwstaff)。所有部署操作用 hwstaff，不用 jackylk。

```bash
# 构建并推送镜像
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
SITE=hwstaff bash deploy/cce/build-and-push-admin.sh

# 重启 CCE 部署
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon

# 前端 (console/admin) 部署在 Railway，git push 自动触发
```

## Commit Convention

`type(scope): description`，如：`feat(console): add pipeline list page`、`fix(api): handle null data_type`

## Key Conventions

- **Pre-push hook**: 自动跑 `vue-tsc -b` 检查两个前端项目，不过不能 push
- **E2E 测试**: 每个新特性都要加 E2E 测试 (API pytest + Playwright browser)
- **API endpoint**: 线上 `https://api.dbay.cloud:8443/api/v1`，dev proxy `localhost:8080`
- **Admin token**: `lakeon-sre-2026` (测试和 SRE 控制台用)
- **测试租户**: E2E 测试创建临时租户，测试后自动清理
- **不做抛弃型方案**: 瞄准目标方案分阶段做
- **设计偏好**: 优雅舒适暖色调 (港湾风格)，不要 emoji 和通用 AI 模板风格
