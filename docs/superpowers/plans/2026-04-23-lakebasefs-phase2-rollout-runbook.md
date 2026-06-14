# LakebaseFS Phase 2 Rollout Runbook

> Status: ready to execute. All code + tests committed. Images built+pushed to SWR. Deployment blocked at time of writing due to CCE control-plane unreachable over local proxy (TLS handshake timeout). Resume below when kubectl works.

## Pre-flight verification

```bash
export KUBECONFIG=~/.kube/cce-lakeon-config
kubectl get ns lakeon    # must succeed
```

If `kubectl` times out, check proxy / VPN before proceeding.

## Images already built and pushed to SWR

- `swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.2.7` — memory-svc with `POST /lbfs/derive`, `ingest_idempotent`, `delete_by_source_path`
- `swr.cn-north-4.myhuaweicloud.com/flex/lakeon-api:0.9.233` — full Phase 2 backend: entities, controllers, forwarder, migrations V38+V39

`deploy/cce/sites/hwstaff/values.yaml` already points to these tags.

## Step F1 — deploy memory-svc

```bash
SITE=hwstaff bash deploy/cce/deploy.sh --skip-test
```

The `deploy.sh` helm upgrade applies both memory-svc (new tag 0.2.7) and lakeon-api (new tag 0.9.233) in the same rollout. Flyway will auto-run V38 + V39 on first lakeon-api pod to come up.

**Verify:**

```bash
kubectl -n lakeon rollout status deploy/memory-svc   --timeout=300s
kubectl -n lakeon rollout status deploy/lakeon-api   --timeout=300s
kubectl -n lakeon get deploy lakeon-api -o jsonpath='{.spec.template.spec.containers[0].image}'
# → .../lakeon-api:0.9.233
```

## Step F2 — install CDC trigger + idempotency index on existing tenants

These scripts walk every READY tenant / memory-base and apply the schema addition. Both are idempotent (CREATE IF NOT EXISTS / CREATE OR REPLACE).

```bash
# A4: install lbfs_events table + trigger on every lbfs_<uuid> DB
SITE=hwstaff bash deploy/cce/migrate-lakebasefs-events.sh

# A5: install UNIQUE idempotency index on every memory_base DB
SITE=hwstaff bash deploy/cce/migrate-memories-idempotency-index.sh
```

Each script prints `=== summary: total=N ok=N fail=0 ===` on success; exit code 1 if any tenant failed (inspect script output and retry just that tenant).

## Step F3 — canary observation

The forwarder starts immediately because `@ConditionalOnProperty(matchIfMissing=true)`. Monitor for ~5 minutes:

```bash
kubectl -n lakeon logs -l app=lakeon-api --tail=200 -f | grep -iE "lakebasefs|forwarder"
```

Expected log lines:
- `uplink worker started`
- `rescan trigger consumed enqueued=...` (only if a tenant's FUSE daemon signals)
- `seeded backfill events count=...` (first time forwarder processes each tenant)
- `forwarder tenant=... target base provisioning; will retry` (first-time auto-provision of `lakebasefs-claude` base)

Watch for **ERROR** lines — any `memory-svc status=` failures mean /lbfs/derive isn't reachable; check `kubectl -n lakeon get svc memory-svc`.

## Step F4 — run full E2E suite

```bash
cd /Users/jacky/code/lakeon
python3 -m pytest tests/e2e/test_lbfs_phase2.py          -v  # 6 mechanism tests
python3 -m pytest tests/e2e/test_lbfs_phase2_quality.py  -v  # 10 quality tests (real corpus)
python3 -m pytest tests/e2e/test_derive_idempotent.py       -v  # 2 idempotency tests
python3 -m pytest tests/e2e/test_lbfs_idempotent.py      -v  # 2 server PUT idempotency (regression)
```

**All must pass per CLAUDE.md E2E discipline** — no skipped, no failed.

If any fail:
- `test_corpus_delete_removes_from_recall` flaky → usually timeout too tight, bump to 300s
- recall-hit-ground-truth misses → likely embedding quality issue (wrong base? wrong embedding model?); check `memory_bases` row for target has `embedding_dim=1024` matching memory-svc config
- `test_no_recall_pollution` unstable → repeat-recall nondeterminism; check embedding backend is deterministic

## Step F5 — Console

```bash
cd /Users/jacky/code/lakeon
git push origin main
# Railway auto-deploys; confirm via:
curl -sk https://console.dbay.cloud/ | head -c 200
```

Then open https://console.dbay.cloud/memory → verify:
- "LakebaseFS 目标" radio column visible
- `lakebasefs-claude` base (auto-created for your tenant) shows `[auto]` badge
- If target not yet set, banner at top "LakebaseFS 有 N 条待派生 memory..." appears

## Post-deploy smoke

1. Stats delta: `curl .../lbfs/stats` now returns derived memories count > 0 within ~1 minute of first forwarder cycle.
2. memory_recall on your lakeon tenant for "hwstaff 部署" → should return `feedback_deploy_hwstaff.md` among top-3.
3. Write a new `~/.claude/memory/feedback_new_thing.md` via Claude Code → wait 60s → `memory_recall "new thing"` returns it.

## Rollback

If something goes wrong at F1:

```bash
# Revert tag in values.yaml to previous (0.9.232 api, 0.2.6 memory)
# Then:
SITE=hwstaff bash deploy/cce/deploy.sh --skip-test
```

Forwarder can be disabled by setting `lakeon.lakebasefs.forwarder.enabled=false` in values.yaml (requires template support — currently ConditionalOnProperty passes directly to pod env; set in `deploy/helm/lakeon/templates/api-deployment.yaml` env section if needed).

The V38/V39 migrations are non-destructive (only ADD new tables) — no rollback needed on schema side.
