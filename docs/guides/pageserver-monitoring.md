# Pageserver 监控与分片触发指南

## 关键指标

通过 `GET http://pageserver:9898/metrics` (Prometheus 格式) 获取以下指标：

### 1. 缓存压力（最重要）

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| `pageserver_page_cache_size_current_bytes` | 当前缓存大小 | 接近 max 时说明缓存满 |
| `pageserver_page_cache_size_max_bytes` | 缓存上限 (默认 64MB) | - |
| `pageserver_layer_completed_evictions` | 已淘汰的 layer 数 | 持续增长 = 缓存不够用 |
| `pageserver_disk_usage_based_eviction_evicted_bytes_total` | 淘汰的总字节数 | 持续增长 = 磁盘缓存也不够 |

**判断方法**：`evicted_layers / 5分钟` > 0 持续发生 = 缓存在互相淘汰

### 2. 远程读取（OBS 回源）

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| `pageserver_wait_ondemand_download_seconds_global_sum` | OBS 下载总耗时 | 持续增长 = 频繁回源 |
| `pageserver_wait_ondemand_download_seconds_global_count` | OBS 下载次数 | 每分钟 > 100 = 缓存严重不足 |
| `remote_storage_s3_request_seconds_sum` | S3/OBS 请求总耗时 | - |
| `remote_storage_s3_request_seconds_count` | S3/OBS 请求总数 | - |

**判断方法**：`ondemand_download_count / 分钟` 持续 > 50 = 需要扩缓存或分片

### 3. 租户数量

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| `pageserver_tenant_states_count{state="Active"}` | 活跃租户数 | > 50 考虑分片 |

### 4. 内存使用

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| `process_resident_memory_bytes` | Pageserver RSS 内存 | > 资源 limit 的 80% |

## 需要开始分片的信号

出现以下**任意 2 个**现象持续 30 分钟以上，就该做分片了：

1. **缓存淘汰率 > 0**：`pageserver_layer_completed_evictions` 持续增长
2. **OBS 回源频繁**：`ondemand_download_count` 每分钟 > 50
3. **查询延迟升高**：用户报告 SQL 查询变慢（> 5 秒）
4. **活跃租户 > 50**
5. **内存使用 > limit 的 80%**

## 在 SRE 控制台查看

SRE Admin API: `GET /api/v1/admin/system/health/pageserver` 已包含基础健康检查。

可通过 Prometheus 采集 pageserver 的 `/metrics` 端点：
```yaml
# Helm 已配置 annotations:
prometheus.io/scrape: "true"
prometheus.io/port: "9898"
prometheus.io/path: "/metrics"
```

## 快速检查命令

```bash
# 查看缓存状态
kubectl exec -n lakeon deploy/pageserver -- curl -s http://localhost:9898/metrics | grep page_cache_size

# 查看淘汰情况
kubectl exec -n lakeon deploy/pageserver -- curl -s http://localhost:9898/metrics | grep evict

# 查看 OBS 回源次数
kubectl exec -n lakeon deploy/pageserver -- curl -s http://localhost:9898/metrics | grep ondemand_download

# 查看活跃租户数
kubectl exec -n lakeon deploy/pageserver -- curl -s http://localhost:9898/metrics | grep tenant_states_count
```
