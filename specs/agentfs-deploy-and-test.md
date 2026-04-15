# AgentFS · Deploy & Smoke Test Runbook

Server-side code compiles (`mvn compile` clean). Actual end-to-end test
requires the new endpoints to be reachable. Two paths:

## Option A · Deploy to api.dbay.cloud (recommended for team)

Pipeline builds `lakeon-api` into a Docker image and deploys to the prod
cluster. After rollout:

```bash
# From the dbay-fuse side
cd ~/code/lakeon/dbay-fuse
pkill -f "dbay-fuse mount" ; sleep 1
rm -rf ~/.dbay/outbox/claude       # clean pending
RUST_LOG=info ./target/debug/dbay-fuse mount --agent claude &

# trigger a write
echo "smoke-test $(date)" > ~/.dbay/mnt/claude/agentfs-smoke.md

# wait for uplink drain (watchdog 500ms + worker 2s poll)
sleep 5

# verify upstream table
psql $LAKEON_DB_DSN -c \
  "SELECT path, kind, size, etag FROM agent_files WHERE path='/agentfs-smoke.md';"

# verify client side thinks it's done
./target/debug/dbay-fuse outbox-status --agent claude
# → pending count: 0  (everything ack'd)
```

## Option B · Local dev loop

Only needed if you don't want to touch prod.

```bash
# 1. Local Postgres
docker run -d --name lakeon-pg -p 5432:5432 \
  -e POSTGRES_DB=lakeon -e POSTGRES_USER=lakeon -e POSTGRES_PASSWORD=lakeon \
  postgres:16

# 2. Start lakeon-api against it (Flyway will create agent_files table)
cd ~/code/lakeon/lakeon-api
mvn spring-boot:run

# 3. Seed a tenant + api key (SQL depends on tenant auth impl)
psql -h localhost -U lakeon lakeon -f /path/to/seed-tenant.sql

# 4. Point the fuse daemon at local API
export DBAY_BASE_URL=http://localhost:8080
export DBAY_API_KEY=lk_testkey_...
cd ~/code/lakeon/dbay-fuse
./target/debug/dbay-fuse mount --agent claude
```

## Verification checklist

For each op, verify both client (outbox ack'd) AND server (row present):

| FUSE op              | Expected server state                     |
|----------------------|-------------------------------------------|
| `echo x > foo.md`    | `agent_files` row with kind=file          |
| `cat foo.md`         | (no server op; served from local state/)  |
| `echo x >> foo.md`   | single `agent_files` row, new etag        |
| `rm foo.md`          | row deleted                               |
| `mkdir d`            | `agent_files` row with kind=dir           |
| `mv foo.md bar.md`   | row with path=/bar.md, old path gone      |

## Known gaps (post-deploy)

1. **Batch endpoint** is implemented but not exercised by dbay-fuse yet
2. **Properties PATCH** is server-only; no client code writes properties yet
3. **CDC → Memory**: no worker consumes agent_files changes to populate memory_items. Next phase.
4. **Multi-writer append conflict**: server serializes, but client doesn't retry on 409 yet

These don't block the basic flow (put/get/delete/rename/mkdir/list).
