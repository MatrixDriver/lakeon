# dbay-fuse 性能测试结果

测试条件：
- 平台：macOS (Apple Silicon)，macFUSE
- 后端：DBay LakebaseFS @ api.dbay.cloud:8443（实际网络）
- 每个 op 跑 N=30 次
- 客户端版本：B + fsync_lite + HTTP batch + truncate fix（commit ~`<待补>`）
- 服务端版本：lakeon-api 0.9.227

## 列含义

- **Native**：原生本地 FS 单次操作耗时（mean）
- **FUSE user**：通过 dbay-fuse 单次操作耗时（mean），CC 系统调用返回看到的延迟
- **倍率**：FUSE user / Native
- **cloud e2e (30 ops 总)**：连续 30 次操作的"墙钟时间"——从第 1 次开始到第 30 次在 DBay 可见
- **摊销 ms/op**：cloud e2e / 30，平均每个操作到达云的延迟

## 结果

| 操作 | Native (单次) | FUSE user (单次) | 倍率 | cloud e2e 30 ops 总 | 摊销 ms/op |
|---|---|---|---|---|---|
| **写文件** | | | | | |
| create_small (200B) | 0.17 ms | 0.75 ms | 4.4× | 1137 ms | 37.9 |
| create_large_64K | 0.22 ms | 1.21 ms | 5.6× | 1065 ms | 35.5 |
| append (40B) | 0.15 ms | 0.62 ms | 4.1× | 1168 ms | 38.9 |
| overwrite | 0.48 ms | 0.54 ms | 1.1× | 1223 ms | 40.8 |
| truncate | 0.24 ms | 0.59 ms | 2.4× | 1176 ms | 39.2 |
| **删除/重命名** | | | | | |
| unlink | 0.14 ms | 0.95 ms | 6.6× | 1093 ms | 36.4 |
| rename_file | 0.12 ms | 1.07 ms | 9.0× | 1116 ms | 37.2 |
| rename_dir | 0.15 ms | 1.27 ms | 8.4× | 1046 ms | 34.9 |
| **目录** | | | | | |
| mkdir | 0.13 ms | 0.97 ms | 7.4× | 1114 ms | 37.1 |
| rmdir | 0.06 ms | 0.56 ms | 9.4× | 1104 ms | 36.8 |
| **读 (不进云)** | | | | | |
| stat | 0.01 ms | 0.01 ms | 1.6× | n/a | n/a |
| read_small (200B) | 0.22 ms | 0.24 ms | 1.1× | n/a | n/a |
| read_large (64KB) | 0.23 ms | 0.21 ms | 0.9× | n/a | n/a |
| readdir(5 entries) | 0.06 ms | 1.67 ms | 29.9× | n/a | n/a |
| readdir(100 entries) | 0.19 ms | 1.19 ms | 6.1× | n/a | n/a |

## 用户感知

- **所有单次操作 ≤1.7ms**——比 LLM 推理快 1000+ 倍，CC 完全感知不到
- **倍率最大处** (readdir(5) 30×、rename 9×) 都是因为绝对值太小（亚毫秒）跟 native 比的相对值，**不是性能问题**
- read 类 (stat / read / read_large) 跟 native **持平甚至略快**（FUSE attr cache + state dir 命中）

## 云持久化感知

- 写类操作 **35-41 ms/op 摊销**，30 个连续操作 ~1.1 秒全部上云
- 跨设备 / 多 agent 协作：A 写完后 B 大约 1-2 秒能 recall

## 优化里程碑

| 阶段 | append user (ms) | append e2e (30 ops) | 关键优化 |
|---|---|---|---|
| A — full Put per close | 27 | ~7 s | baseline (问题暴露) |
| B — append delta upload | 21 | 1.6 s | 只发增量字节 |
| B + fsync_lite | 1.3 | 1.3 s | 绕开 macOS F_FULLFSYNC (200×) |
| B + fsync_lite + HTTP batch | **0.62** | **1.17 s** | uplink 一次 HTTP 多 op |

## 修过的 bug

1. **macOS F_FULLFSYNC**：Rust `sync_all/sync_data` 默认走 F_FULLFSYNC (~11ms)，换成 `libc::fsync` 后 ~0.05ms
2. **macFUSE O_APPEND quirk**：append 模式下 FUSE 收到 offset=0 而非文件末尾，改为 `seek SeekFrom::End(0)`
3. **Server batch endpoint 缺 append op**：补 `case "append"` 到 LakebaseFSController.batch
4. **Server batch delete 不幂等**：catch NotFoundException 改为 `ok_absent`，rm -f 语义
5. **Truncate 不刷云**：close 触发 watchdog::Closed 立即清状态导致 idle-flush 永不触发；改 flush_fh 总是询问 plan_flush 而不只看 fh.dirty

## 复现

```bash
# 在 ~/code/lakeon/dbay-fuse:
cargo build --release
./target/release/dbay-fuse mount --agent claude &  # 后台跑
python3 bench/bench-full-e2e.py
```

需要：daemon 运行中，`~/.dbay/config.json` 已配 api_key，DBay 可达。
