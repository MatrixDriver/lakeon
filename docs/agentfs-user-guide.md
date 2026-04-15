# DBay AgentFS 使用指南

让 Claude Code / OpenClaw 等 agent 的工作目录（`~/.claude/projects`、`~/.claude/memory`、`CLAUDE.md` 等）透明上云到 DBay，跨设备可用。

> 架构细节见 `specs/agentfs-openapi.yaml` 和 `specs/universal-memory.md`
> 服务端实现在 `lakeon-api/src/main/java/com/lakeon/agentfs/`
> 客户端实现在 `dbay-fuse/`

---

## 1. 前提

- macOS（装了 [macFUSE](https://osxfuse.github.io/)）或 Linux（装了 fuse3）
- Rust 工具链（`rustup`）
- 已有 DBay 账号 + API key（从 https://console.dbay.cloud 注册或从 `~/.dbay/config.json` 里读）

## 2. 一次性设置

```bash
# 克隆仓库
git clone <lakeon repo>
cd lakeon/dbay-fuse

# 编译
cargo build --release

# 准备 ~/.dbay/config.json
cat > ~/.dbay/config.json <<EOF
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key":  "lk_xxx"
}
EOF

# 验证
./target/release/dbay-fuse whoami --agent claude
# 期望输出：api_key、base_url、base_id 都有值
```

## 3. 首次启用（接管 CC 目录）

**重要**：第一次接管 `projects/` 之前请先 **退出所有 Claude Code 窗口**，避免撕裂正在写的 session 文件。

```bash
# A. 启动 FUSE daemon（后台或 tmux）
./target/release/dbay-fuse mount --agent claude &
# 或更稳定的方式：tmux new-session -d -s dbay-fuse './target/release/dbay-fuse mount --agent claude'

# B. 接管逐个目录（先小的，观察一下再做大的）
./target/release/dbay-fuse takeover --agent claude --nodes CLAUDE.md         # 最小，268B
./target/release/dbay-fuse takeover --agent claude --nodes memory            # 几个 md
./target/release/dbay-fuse takeover --agent claude --nodes projects          # 大头，几十 MB

# C. 校验 symlink 指向 FUSE mount
ls -la ~/.claude/CLAUDE.md
# lrwxr-xr-x  ... ~/.claude/CLAUDE.md -> /Users/you/.dbay/mnt/claude/CLAUDE.md

# D. 校验能读回原内容
cat ~/.claude/CLAUDE.md | head
```

## 4. 日常使用

起 Claude Code 就完了，没有额外步骤。所有写入会：

1. 立即落到本地 `~/.dbay/state/claude/`（POSIX 保证 durability）
2. 异步推送到 DBay（通过 outbox）
3. DBay Postgres `agent_files` 表持久化

检查状态：

```bash
# 查看还有多少 pending upload
./target/release/dbay-fuse outbox-status --agent claude
# 输出：pending count: 0  → 全部上云

# 查看服务端有哪些文件
KEY=$(jq -r .api_key < ~/.dbay/config.json)
curl -sk "https://api.dbay.cloud:8443/api/v1/agentfs/list?recursive=true" \
  -H "Authorization: Bearer $KEY" | python3 -m json.tool
```

## 5. 回滚（完全撤销接管）

```bash
./target/release/dbay-fuse release --agent claude
# 会：
# 1. 把 symlinks 删掉
# 2. 从 ~/.dbay/backups/claude-<ts>/ 恢复原始目录
# 3. daemon 不会被杀，但符号链接指向的 FUSE mount 不再被用

# 然后卸载 FUSE
pkill -f "dbay-fuse mount"
umount ~/.dbay/mnt/claude
```

## 6. 故障恢复

### 6.1 daemon 挂了

不用怕，数据没丢：
- 写时先落本地 `~/.dbay/state/claude/`
- outbox 记录未上云的变更，**重启 daemon 会自动 replay**

```bash
./target/release/dbay-fuse mount --agent claude &
```

### 6.2 网络断了

outbox 会一直积压，再连上就自动消费。期间 agent 仍可读写本地。

查看积压：
```bash
./target/release/dbay-fuse outbox-status --agent claude
# 如果 pending count 一直不降，看 daemon 日志排查
```

### 6.3 symlink 指向的 FUSE mount 不存在

症状：`ls ~/.claude/` 显示 `CLAUDE.md` 是 broken link。

修法：重启 daemon
```bash
./target/release/dbay-fuse mount --agent claude &
```

## 7. 目录布局

```
~/.dbay/
  ├── config.json           # api key + base binding
  ├── mnt/claude/           # FUSE 挂载点（CC 的 projects/memory/CLAUDE.md 实际读写）
  ├── state/claude/         # 本地真数据（passthrough 的 backing store）
  ├── outbox/claude/
  │    ├── pending.log      # 待上云的操作日志（JSONL append-only）
  │    └── blobs/<sha>/     # 文件内容 content-addressed 存储
  └── backups/claude-<ts>/  # takeover 时的原始备份（release 用到）
```

## 8. 性能预期

| 操作 | 延迟 | 为什么 |
|---|---|---|
| 读 | <1ms | 本地 state 直读，FUSE 无网络 |
| 写（小文件） | <1ms 返回 + 异步 | 写本地 + outbox append，返回立即；DBay 同步后台做 |
| 写（大文件） | 取决于本地 fs | 上限是本地磁盘写速 |
| 上云延迟 | 500ms–几秒 | watchdog idle + uplink poll + 网络 RTT |

**对比**：直接调 DBay memory API 每次 RTT 1.2s+。本方案把这个延迟藏在后台。

## 9. 约束与已知问题

1. **单用户一个 store**：同一 API key 下所有 agent 写入到同一个 `agent_files` 命名空间（按 path prefix 区分）
2. **不保证跨设备严格一致**：两台机器同时写同一文件会走 last-write-wins；极少数场景可能丢。下次发版会加 ETag 冲突检测。
3. **多窗口并发 append**：同一 session.jsonl 两个 CC 窗口同时写，本地 state 用 OS 文件锁 OK，但上云的 ETag 版本会冲突 → 现阶段行为：后 push 的覆盖前 push。`/agentfs/files/append` 服务端端点已实现，但客户端还没对 session.jsonl 走 append 路径。
4. **Memory 索引有延迟**：文件写进 AgentFS 后，Memory 系统（memory_ingest）不会立即看到——需要独立的派生 worker 消费（**还没实装**）。
5. **takeover projects/ 前务必退出 CC**：正在写的 session 被换底盘会导致 append 失败或数据截断。

## 10. 卸载

```bash
./target/release/dbay-fuse release --agent claude
pkill -f "dbay-fuse mount"
umount ~/.dbay/mnt/claude
# 想彻底清干净：
rm -rf ~/.dbay/{mnt,state,outbox,backups}
# 服务端的 agent_files 表数据不会自动删——如果要删：
curl -sk -X POST https://api.dbay.cloud:8443/api/v1/agentfs/files/delete \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"path":"/projects"}'   # 逐个或写脚本
```

## 11. 调试

```bash
# 详细日志
RUST_LOG=debug ./target/release/dbay-fuse mount --agent claude 2>&1 | tee /tmp/dbay.log

# 看服务端 pod 日志
export KUBECONFIG=$HOME/.kube/cce-lakeon-config
kubectl -n lakeon logs -l app=lakeon-api --tail=50 | grep -i agentfs

# 检查 Postgres 里的数据
# （需要 RDS 访问权限）
```

## 12. 路线图

- ✅ Phase 1（当前）：passthrough + outbox，POSIX 兼容
- ⏳ Phase 2：Memory 派生 worker（CDC → memory_ingest），让 `feedback_*.md` 等文件自动进 recall 索引
- ⏳ Phase 3：虚拟 CLAUDE.md（从 memory_items 实时拼装）
- ⏳ Phase 4：OpenClaw adapter（workspace/ 映射）
- ⏳ Phase 5：Cache-miss 下行拉取（离线切换到新机器时自动 sync）
