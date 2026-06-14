# DBay LakebaseFS 使用指南

LakebaseFS 是一个通用的本地目录接入层：用户可以 mount 一个新的 DBay 目录，也可以把已有目录以 sync/import 的方式加入 DBay。用户需要声明目录性质，DBay 再根据该 profile 选择存储策略和云端后台处理。

> 服务端实现在 `lakeon-api/src/main/java/com/lakeon/lbfs/`
> 客户端实现在 `dbay-fuse/`

---

## 1. 前提

- macOS（mount 模式需要 macFUSE）或 Linux（mount 模式需要 fuse3）
- Rust 工具链（`rustup`）
- 已有 DBay 账号 + API key

## 2. 一次性设置

```bash
cd lakeon/dbay-fuse
cargo build --release

cat > ~/.dbay/config.json <<EOF
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key":  "lk_xxx"
}
EOF

./target/release/dbay-fuse whoami --kind files
```

## 3. 目录性质

`--kind` 只表达“这是什么目录”：

- `codex-home`
- `claude-home`
- `openclaw-home`
- `iceberg-table`
- `lance-table`
- `data-dir`
- `files`

`--storage` 表达“字节怎么存”：

- `auto`
- `inline-only`
- `object-first`
- `object-only`
- `table-native`

`--processing` 表达“云端做什么后台处理”：

- `none`
- `agent-home`
- `dataset`
- `iceberg`
- `lance`
- `small-file-memory`

通常用户只需要给 `--kind`，系统会推导默认 storage 和 processing。

## 4. Mount 一个新目录

```bash
./target/release/dbay-fuse mount ~/DBay --kind files
```

mount 模式会使用：

```text
~/.dbay/mnt/<folder>/      # 默认 mount 点（如果命令未给本地目录）
~/.dbay/state/<folder>/    # 本地 backing store/cache
~/.dbay/outbox/<folder>/   # 待上传队列
```

读写热路径保持本地优先：

1. 应用写入 mount 目录。
2. 内容先落本地 state。
3. outbox 后台上传到 DBay。

## 5. 同步已有目录

sync 模式不会复制第二份完整数据。用户原目录就是 local state，`~/.dbay/sync/<folder>/` 只保存 ledger、队列、临时文件和冲突记录。

```bash
./target/release/dbay-fuse sync ~/.codex --kind codex-home
./target/release/dbay-fuse sync ~/.claude --kind claude-home
./target/release/dbay-fuse sync ~/datasets/events --kind iceberg-table
./target/release/dbay-fuse sync ~/vectors/products.lance --kind lance-table
./target/release/dbay-fuse sync ~/reports --kind data-dir
./target/release/dbay-fuse sync ~/notes --kind files --storage inline-only --processing small-file-memory
```

当前 sync 命令会扫描目录并把变更排入 `~/.dbay/sync/<folder>/outbox`。后续版本会补上常驻 watcher 和云端 worker 状态展示。

## 6. 一次性导入

```bash
./target/release/dbay-fuse import ~/archive --kind data-dir
```

import 和 sync 使用同一套 planner，但产品语义是“一次性加入 DBay”，适合历史归档。

## 7. 检测建议

检测算法先作为 advisor，不直接替用户决定。

```bash
./target/release/dbay-fuse inspect ~/datasets/events
```

输出会包含推荐 kind、置信度和原因。

## 8. 故障恢复

### daemon 挂了

mount 模式的数据先落本地 state，outbox 会在重启后继续 replay。

```bash
./target/release/dbay-fuse mount ~/DBay --kind files
```

### 网络断了

outbox 会积压，网络恢复后继续上传。

```bash
./target/release/dbay-fuse outbox-status --folder dbay
```

### 冲突文件处理

冲突文件位于：

- `~/.dbay/conflicts/<原路径>.conflict-from-<host>-<ts>`
- `<state-dir>/<原路径>.conflict-pull-<host>-<ts>`
- `~/.dbay/conflicts/conflicts.log`

处理方式仍是手动 diff、merge、删除副本。后续会加 `conflicts list/clean`。

## 9. 服务端 folder profile

控制面现在有一等的 LakebaseFS folder registry。客户端仍会把 `lbfs_profile`
写入文件 properties，作为兼容和事件处理提示；服务端的 `lbfs_folders`
是后续 Console、设备绑定、worker 状态和对象存储策略的 source of truth。

```http
POST /api/v1/lbfs/folders
{
  "display_name": "warehouse",
  "directory_kind": "data-dir"
}
```

默认映射保持和 CLI 一致：

- `codex-home` / `claude-home` / `openclaw-home` -> `storage=auto`, `processing=agent-home`
- `data-dir` -> `storage=object-first`, `processing=dataset`
- `iceberg-table` -> `storage=table-native`, `processing=iceberg`
- `lance-table` -> `storage=table-native`, `processing=lance`
- `files` -> `storage=auto`, `processing=none`

现有 memory forwarder 只处理 `agent-home` 和 `small-file-memory`。没有
`lbfs_profile` 的老文件保持 legacy 行为，避免升级后停止已有 memory 派生。

## 10. 路线图

- 当前：通用 folder profile、mount、pull、sync/import skeleton、inspect advisor、服务端 folder registry、memory worker profile gate
- 下一步：sync watcher、OBS/object tier、profile-specific cloud workers 状态展示
- 后续：agent-home 提炼 Console、Data Agent、Iceberg/Lance catalog/版本映射
