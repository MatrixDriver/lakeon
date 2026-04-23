# SRE Agent Phase 0a Deploy Runbook

End-to-end steps for the human user to take the code from this branch to a live Railway service that monitors dbay.cloud compute cold starts and DMs Jacky on feishu when anomalies occur.

## Prerequisites (do in advance — 30 min)

1. **Feishu self-built app** — one command from your Mac:
   ```bash
   cd /Users/jacky/code/lakeon/sre-agent
   uv run python scripts/onboard_feishu.py
   ```
   Scan the QR code with feishu mobile app → approve. App is created, credentials pushed to Railway, allowed users set to your feishu account.

   Manual fallback (if the above fails): go to https://open.feishu.cn/ → create a self-built app → enable Bot capability → copy app_id / app_secret / verification_token / encrypt_key → paste into Railway env vars manually.
2. **DBAY_LOGS_DSN** — Postgres connection string for `dbay-logs`. Format: `postgresql://user:pass@host:5432/dbay-logs`.
3. **OBS bucket** — create `dbay-agent-log` in Huawei Cloud OBS (region `cn-north-4` per existing deploy convention). Note access key / secret / endpoint.
4. **Deepseek API key** — from `~/.dbay/tokens.json` or memory.

## Step 22.0: Pre-flight probes (from your Mac)

### Probe dbay-logs connectivity

```bash
cd /Users/jacky/code/lakeon/.worktrees/sre-agent/sre-agent
export DBAY_LOGS_DSN="postgresql://..."
uv run python scripts/probe_dbay_logs.py
```

Expected: `OK: N log rows in last 1h, connect+query took <2000ms`

If FAIL: two fallback paths for network isolation:
- (a) Run the agent on a Huawei Cloud ECS instance in the same VPC as dbay-cce — gives it VPC-internal PG access. Adjust Dockerfile/railway.toml for ECS.
- (b) Run a reverse proxy inside dbay-cce that exposes an HTTP-tunnel to dbay-logs. More engineering, not recommended for Phase 0a.

### Deploy lakeon-api (the cold-start log we added)

This branch includes a change in `lakeon-api/src/main/java/com/lakeon/service/ComputeLifecycleService.java` adding the log line that the watcher parses. Deploy before launching the agent:

```bash
cd /Users/jacky/code/lakeon
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=5m
```

Then verify the log line appears by triggering a cold start (see simulate step) and:

```bash
psql "$DBAY_LOGS_DSN" -c "SELECT ts, msg FROM logs WHERE msg LIKE '%compute started in%' ORDER BY ts DESC LIMIT 3"
```

Expected: one or more rows within 10 minutes of your trigger.

## Step 22.1: Create Railway service

1. Railway dashboard → new service
2. Connect to the lakeon GitHub repo
3. Branch: `feat/sre-agent-phase-0a`
4. Root directory: `sre-agent/` (the `railway.toml` takes over from here)
5. **Add persistent volume**: In Railway dashboard → Service → Settings → Volumes → add new volume with mount path `/data/hermes`, size 10 GB. This step is **mandatory** — session logs, skill ledger, and OBS sync state are all stored here; without a volume they are lost on every redeploy. The `railway.toml` `[volumes]` block is intentionally omitted (Railway does not support declarative volume config); you must do this in the dashboard.
6. Environment variables (copy from `sre-agent/.env.example` and paste secrets):
   - `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL=https://api.deepseek.com`
   - `DBAY_LOGS_DSN`
   - `FEISHU_APP_ID`, `FEISHU_APP_SECRET`, `FEISHU_VERIFICATION_TOKEN`, `FEISHU_ENCRYPT_KEY`
   - `FEISHU_ALLOWED_USERS=ou_jackys_open_id` (per hermes convention)
   - `OBS_ACCESS_KEY`, `OBS_SECRET_KEY`, `OBS_BUCKET=dbay-agent-log`, `OBS_ENDPOINT=obs.cn-north-4.myhuaweicloud.com`

## Step 22.2: First deploy and probe

After the first build finishes, open Railway shell:

```bash
python /app/scripts/probe_dbay_logs.py
python /app/scripts/verify_env.py
```

Both must print OK. If either fails, stop and fix before proceeding.

## Step 22.3: Feishu handshake

From feishu, DM the bot: `ping`. Expected: LLM reply (via deepseek) within 30 seconds.

If no reply, check Railway logs for `[entrypoint] launching hermes gateway` and any hermes errors. Common issues:
- Feishu credentials wrong — hermes logs will show auth failure
- LLM provider schema mismatch — check hermes_config/config.yaml provider settings
- Bot not subscribed to events — feishu dev console → subscribe to `im.message.receive_v1`

## Step 22.4: Trigger a simulated cold start

From your Mac:

```bash
cd /Users/jacky/code/lakeon/.worktrees/sre-agent/sre-agent
export DBAY_ADMIN_TOKEN=lakeon-sre-2026
uv run python scripts/simulate_cold_start.py
```

This creates a test tenant + db, waits for auto-suspend (10 min default via `SIMULATE_IDLE_WAIT_SEC`), then connects to force a cold start.

To shorten the wait for a first test, set `SIMULATE_IDLE_WAIT_SEC=60` and ensure the instance is already suspended from a prior run; otherwise the database will still be warm and the cold-start latency will be under 5000 ms (watcher correctly ignores it).

## Step 22.5: Watcher fires

Within 2 minutes of the cold start, Railway logs should show:

```
[watcher] scan_once: found 1 slow start(s) for t_.../db_...
[diagnose] opened session sess_...
[report] sending feishu card to ou_...
```

Confirm on phone: a feishu card titled "Cold start \<ms\>ms @ \<tenant\>/\<db\>" with root cause and suggested actions.

If nothing fires within 3 minutes, check:
1. Railway cron is actually triggering (grep logs for `cold-start-watcher`)
2. The lakeon-api log line format matches the watcher regex (confirm a row exists in dbay-logs with `compute started in` text)
3. The cold start actually exceeded 5000 ms (if under 5 s, the watcher correctly ignores)

## Step 22.6: Interactive DM

DM the bot: `上次那个冷启动最后是什么根因`

Expected: bot calls `log.search_text(...)` and answers with the session's conclusion.

## Step 22.7: Verify OBS sync

From Railway shell:

```bash
ls /data/hermes/data/sessions/$(date +%Y)/$(date +%m)/$(date +%d)/
# Should show sess_... directory
```

From your Mac (after installing obsutil):

```bash
obsutil ls obs://dbay-agent-log/agent-log/$(date +%Y)/$(date +%m)/$(date +%d)/
# Should show sess_....tar.gz
```

## Step 22.8: Next morning (09:00) — outcome-checker

Check Railway logs for `outcome-checker`. The session opened earlier now has `outcome.md` with `did_work: true/false`. The skill ledger's `did_work_rate` will be updated.

## Step 22.9: Phase 0a completion report

Create `sre-agent/reports/phase-0a-report.md` summarizing:
- Incidents caught (count + examples)
- False positive rate
- Actions you took and whether they resolved the real problem
- Skill ledger stats snapshot (from `/data/hermes/data/skills-ledger/cold-start-watcher/stats.json`)
- Unexpected issues + workarounds
- Recommendation: proceed to Phase 0b, tweak Phase 0a, or rethink abstraction

## Troubleshooting

**hermes gateway crashes on start:**
- Railway logs will show stack trace
- Most likely: feishu credential format wrong, or OpenAI-compatible provider schema mismatch
- Quick fix: test hermes imports directly — `uv run python -c "import hermes; print('ok')"`

**LLM calls fail:**
- Deepseek rate limits trigger at high bursts during diagnosis (3+ calls per incident)
- Add exponential backoff in the LLM wrapper if this becomes an issue
- Fallback: switch `DEEPSEEK_BASE_URL` to `https://api.modelarts-maas.com/openai/v1` (Huawei Cloud MaaS) if needed

**OBS sync fails:**
- Check `.sync/obs.log` inside `/data/hermes/data/`
- Most common: OBS bucket policy does not grant the access key write permissions — add IAM policy or switch to bucket-owner-full-control ACL

**Railway egress IP changes:**
- dbay-logs PG security group must include Railway's current egress pool
- Railway uses multiple IPs, not a static address
- Workaround: update `pg_hba.conf` to accept Railway's documented egress CIDR, or use a strong password with broader host acceptance
