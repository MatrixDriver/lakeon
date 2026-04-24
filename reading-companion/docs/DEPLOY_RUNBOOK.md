# reading-companion Deployment Runbook

## Prerequisites

- Railway account with access to dbay project
- Feishu open platform account
- Existing OBS bucket `dbay-agent-log` (shared with sre-agent)

## Step 1 — Register a NEW feishu self-built app

1. Go to https://open.feishu.cn/app → 创建自建应用 → name: "dbay reading companion"
2. After creation, get: app_id, app_secret
3. Application capability: enable 机器人 → fill in bot avatar/name (e.g. "📖 读了什么")
4. Permissions:
   - `im:message:send_as_bot` (send DM as bot)
5. Version & release → 创建版本 → submit → 等管理员审核
6. Add Jacky as a tester so the bot can DM him before public release
7. Note Jacky's open_id under THIS app (different open_id than the SRE bot — open_ids are per-app):
   - On the open platform admin → Application → 通讯录 → search Jacky → copy open_id

## Step 2 — Create Railway service

1. Railway dashboard → dbay project → New Service → Deploy from GitHub repo `lakeon`
2. Settings → Source:
   - Branch: main
   - **Root Directory: `/`** (NOT `/reading-companion` — the workspace needs visibility into `packages/`)
3. Settings → Volumes:
   - Mount path: `/data/hermes`
   - Size: 5GB (reading data is small; ~10 MB / month)
4. Settings → Networking: not needed (no inbound HTTP)
5. Variables:
   ```
   DEEPSEEK_API_KEY=<copy from sre-agent service>
   DEEPSEEK_BASE_URL=<copy from sre-agent service>
   FEISHU_APP_ID=cli_xxx (from Step 1)
   FEISHU_APP_SECRET=xxx (from Step 1)
   FEISHU_ALLOWED_USERS=ou_jackys_open_id_under_reading_bot (from Step 1)
   OBS_ACCESS_KEY=<copy from sre-agent>
   OBS_SECRET_KEY=<copy from sre-agent>
   OBS_BUCKET=dbay-agent-log
   OBS_ENDPOINT=obs.cn-north-4.myhuaweicloud.com
   OBS_PREFIX=agent-log/reading/
   HERMES_HOME=/data/hermes
   ```

## Step 3 — Trigger first deploy

1. Push any commit on main → Railway auto-deploys
2. Watch build logs:
   - `apt-get install` succeeds (libxml2/libxslt1.1)
   - `uv pip install /app/packages/agent-session-log` succeeds
   - `uv pip install /app/packages/hermes-agent-utils` succeeds
   - `uv pip install /app/reading-companion` succeeds
3. Watch deploy logs:
   - `[entrypoint] verifying env...` → `OK — all 9 required env vars set`
   - `[entrypoint] launching reading-companion main.py...`
   - `[runner] starting obs_sync_loop: ...`
   - `[cron] loop started with 1 task(s)`

## Step 4 — Smoke test from local

```bash
# From your laptop
cd /Users/jacky/code/lakeon
.venv/bin/python -m skills.reading.url_handler.cli \
  --url "https://en.wikipedia.org/wiki/Stub_(distributed_computing)" \
  --no-push
```

Set `HERMES_HOME=/tmp/reading_smoke` to avoid polluting production data.

Expected: session created locally; `--no-push` skips feishu DM.

## Step 5 — Production smoke test from Railway shell

```bash
railway run --service reading-companion -- \
  python -m skills.reading.url_handler.cli \
  --url "https://en.wikipedia.org/wiki/Stub_(distributed_computing)"
```

(no `--no-push` this time → DM should arrive on the new bot)

Expected: feishu DM with 📖 card appears within 30s.

## Step 6 — Wait for 22:00 reflection

At 22:00 Asia/Shanghai (= 14:00 UTC), Railway logs should show:

```
[cron] firing 0 14 * * * → run_daily_reflection
[daily_reflection] reflect_today starting
[daily_reflection] wrote session sess_...
[daily_reflection] feishu DM sent to ou_...
```

If you've fed at least one URL today, the DM arrives. Otherwise:

```
[daily_reflection] skipped: no reading sessions in last 24h
```

## Step 7 — Verify OBS isolation

```bash
# OBS bucket should now have two prefixes
obsutil ls obs://dbay-agent-log/agent-log/sre/    # sre-agent's sessions
obsutil ls obs://dbay-agent-log/agent-log/reading/ # reading-companion's sessions
```

Each side should list its own session tar.gz files only.

## Rollback

If reading-companion misbehaves:
1. Railway dashboard → reading-companion service → Pause Deployments
2. Existing data on Volume + OBS is preserved
3. SRE service is unaffected (separate process, separate Volume, only shares OBS bucket)
