# 症状速查表

## 冷启动慢 / 唤醒失败 / 连接超时

| 可能 | log 信号 | 工具 |
|---|---|---|
| pageserver re-attach 太慢 | pageserver log "re-attach took Nms" | `log_search(component="pageserver", keyword="re-attach", since="5m")` |
| compute pod 创建失败 | k8s event InvalidName / CrashLoopBackOff | `pod_create_failures(since="30m")` |
| WAL replay backlog | pageserver log "wal_lag" | `log_search(component="pageserver", keyword="wal_lag")` |
| 镜像拉取慢 | k8s event ImagePullBackOff | `pod_create_failures(since="30m")` → 看 ImagePullBackOff 类别 |
| node 调度失败 | k8s FailedScheduling | `pod_create_failures` |

## 数据不一致(X 创建后 Y 找不到)

| 可能 | 工具 |
|---|---|
| KB 标 READY 但 db_id NULL | `data_consistency_check(rule="kb_implies_db_id")` |
| 写入 enqueued 但 drain 超时 | `data_consistency_check(rule="enqueued_implies_drained")` |
| DB 标 READY 但 compute_host 空 | `data_consistency_check(rule="db_ready_implies_pod_running")` |
| Wiki KB 缺 schema | `data_consistency_check(rule="schema_seeded")` |

## 多 tenant 同时出事

| 症状 | 工具 |
|---|---|
| 单一 fault domain 击穿 | `multi_tenant_blast_radius(window="15m")` |
| 上游依赖挂 | 上面 + `log_errors(component=<suspected-upstream>)` |
| 共享配置错 | 检查最近是否有 env / config deploy |

## 任务卡死

| 症状 | 工具 |
|---|---|
| wiki/agentfs/kb 任务 in_progress 超时 | `stuck_task_query(threshold_minutes=10)` |
| 特定类型任务 | `stuck_task_query(type="WIKI_UPDATE")` |

## Cost / Usage 异常

目前还没做成 SRE 工具 — 如果用户问成本问题,回复"暂时没接 cost 工具,建议去 admin console / dashboard 看"。
