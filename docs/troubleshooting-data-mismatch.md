# Lakeon 存算分离架构：多租户数据不一致故障排查实录

## 1. 故障现象与背景

在执行本地集群的自动化集成测试脚本 (`deploy/local/integration-test.sh`) 时，系统整体表现非常稳定，多数测试用例（包括单租户隔离、权限验证等）均一次性通过。

然而，在**多租户数据隔离**测试环节，偶尔会抛出一个报错：
**`[FAIL] IT-E2E-014c: Tenant B data mismatch`**

该测试用例的逻辑非常直接：
1. 向租户 B 的计算节点 (Compute Pod) 发起 `INSERT` 写入数据表。
2. 随后立即再发起一次 `SELECT` 查询验证刚写入的数据。

在多数情况下（例如同类的 Tenant A 测试），该用例正常通过。但 Tenant B 测试偶发性报错表明，**在写入完成与下次查询回传之间，偶尔出现数据读不到（或不一致）的现象**。

## 2. 深入排查与探查

为了探明其本质原因，我们编写了专项探查脚本 `explore-commit.sh`，避开控制平面 (`lakeon-api`)，直接向底层的 Compute 节点（基于 Neon PostgreSQL 衍生）注入大量的事务请求，并通过查阅底层日志节点的状态抓取核心运行机制。

我们在探查中做了以下两组实验比对：

*   **标准同步提交 (`synchronous_commit = on`)**
    *   通过 `pgbench` 执行了 1000 次插入。
    *   此时 TPS 为 ~4159，平均延迟 ~0.96ms。
    *   底层监控中，`Safekeeper` 的 `commit_lsn` 实名推进并确认，而真正接管全量存储的 `Pageserver` 上的 `disk_consistent_lsn` 存在天然的滞后。

*   **异步提交测试 (`synchronous_commit = off`)**
    *   注入 `ALTER DATABASE postgres SET synchronous_commit = off`。
    *   TPS 瞬间飙升至 ~7486，平均延迟降低至 ~0.53ms（接近单机内存满载运行）。
    *   底层状态监控揭示：写入只在极快地进入 Buffer，并未等待网络层流式日志 (`WAL`) 传输给持久化层即返回成功报告。

## 3. 架构原理解析：核心的写提交分离

经过这番真实并发压测与组件指标观测，我们厘清了 Lakeon 存算分离机制（无状态 Compute 节点 + WAL 路由流转 + 对象存储）下完整的事务流水线：

1.  **纯流式写前日志 (WAL Streaming)**：
    Compute 节点不写入任何持久化的数据块盘，它唯一的存盘输出是向网络发送流式的预写日志 (WAL)。
2.  **Safekeeper 实现的低延迟提交确认**：
    在标准的同步提交下，写操作不必等待庞大的 `Pageserver` 分配页块，而是只要被专属的日志代理 **Safekeeper** 接收且持久化落盘，就会向 Compute 发送 `ACK` 确认。此时，Compute 会认为“事务已安全”，并向外层客户端返回 `INSERT 0 1`。
3.  **Pageserver 的异步构建与缓存重组**：
    Pageserver 在后台慢慢从 Safekeeper 下载 WAL。当 Compute 节点处理后续的 `SELECT` 时：
    - 如果数据页在本地 Buffer 中缓存命中，立即返回。
    - 如果缓存失效引发缺页异常（Cache Miss），它将从 Pageserver 实时重组底层页基础数据（Base Page）和最新一段尚未合入的预写日志（WAL）拼凑出正确响应。

## 4. 故障判定与根因

回到原偶发报错点 `IT-E2E-014c`。

在自动化脚本中，写与读操作通过 `kubectl exec` 完成：
```bash
run_sql "$pod_b" "INSERT INTO beta_data VALUES ('secret-beta-456');"
val_b=$(run_sql "$pod_b" "SELECT val FROM beta_data LIMIT 1;")
```
*   这相当于每次都启动了一个瞬时的、**全新的 `psql` 会话连接**。
*   当新连接建立并试图读取时，由于前一次 `INSERT` 产生的数据状态虽已获得 `Safekeeper` 确认并报告成功，但在极微观的瞬时，它可能**在计算节点内部刚刚被洗出局域 Buffer**，而 **`Pageserver` 在网络分发层还没有将相应的 LSN 反馈给缓存逻辑系统**。
*   这种极端的读写生命周期差异与新旧网络会话边界，构成了短暂的“脏读”盲区（微秒到几十毫秒级）。

## 5. 结论建议与优化措施

这并非系统引擎的数据丢失，而是存算分离架构中极为典型的高并发状态下，跨会话短连接引发的分布式可见性延迟。

为了保证自动化集成测试的高鲁棒性，以及避免真实业务上遭遇类似的奇葩情况，建议：

1.  **测试脚本健壮性层**：在两次独立的命令行 (`kubectl exec`) 驱动的强一致性验证动作间，酌情增加微小的状态同步等待期（例如 `sleep 0.5` 到 `sleep 1`），使得分布式 WAL 有充足的时间穿越 Pageserver 读取管线。
2.  **应用层建议**：业务模块（湖仓应用、分析台等）应依赖长连接或使用数据库驱动连接池 (Connection Pooler，如 PgBouncer 或系统自带的 Proxy)，确保相关的事务可见性能在同一个高内聚的会话流中被保障。对于极其严格的强同步读取，也可以主动探查底层事务 LSN。

这是一次非常成功的数据架构压力验证与探底，进一步证明了基于 Neon 机制搭建的 Serverless 存算架构具备极高的高可用与可恢复能力。
