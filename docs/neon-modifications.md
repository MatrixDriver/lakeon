# Neon 修改建议

记录使用 Neon 官方镜像过程中遇到的兼容性问题，用于评估是否需要维护自己的 Neon Fork 版本。

---

## 问题 1：compute_ctl setrlimit 硬失败

### 现象

Compute pod 启动时 CrashLoopBackOff，日志：

```
Error: Operation not permitted (os error 1)
```

### 根因

`compute_ctl` 源码启动时调用 `setrlimit(RLIMIT_CORE, RLIM_INFINITY)`，要求 core dump 大小为无限制。但 CCE 节点的 containerd 默认 `LimitCORE` 硬限制为 5GB。容器内进程无法将 rlimit 设置为超过宿主机 containerd 给定的硬限制，内核拒绝后 `compute_ctl` 直接退出。

这段代码的目的只是"确保 crash 时能生成完整 core dump 用于调试"，对正常运行没有意义。

### 当前 Workaround

每个 CCE 节点需要手动（或通过 DaemonSet）修改 containerd 配置：

```bash
mkdir -p /etc/systemd/system/containerd.service.d
printf '[Service]\nLimitCORE=infinity\n' > /etc/systemd/system/containerd.service.d/ulimit-core.conf
systemctl daemon-reload && systemctl restart containerd
```

**痛点**：每次新建节点、节点池扩容、集群重建都需要重新配置。

### 建议修改

将 `setrlimit(RLIMIT_CORE, RLIM_INFINITY)` 改为 best-effort（失败时 warn 并继续，而非退出）：

```rust
// 修改前（硬失败）
setrlimit(Resource::CORE, Rlimit { cur: INFINITY, max: INFINITY })?;

// 修改后（best-effort）
if let Err(e) = setrlimit(Resource::CORE, Rlimit { cur: INFINITY, max: INFINITY }) {
    warn!("Failed to set RLIMIT_CORE to infinity: {}, continuing anyway", e);
}
```

**影响范围**：仅影响 crash 时的 core dump 大小，不影响正常运行。

### 相关文件

- Neon 源码：`compute_tools/src/bin/compute_ctl.rs`（搜索 `setrlimit` 或 `RLIMIT_CORE`）

---

## 问题 2：S3 客户端强制 path-style 寻址

### 现象

Neon 连接华为云 OBS 时，使用 path-style URL（`endpoint/bucket`），但 OBS 要求 virtual-hosted-style（`bucket.endpoint`）。

### 根因

Neon 源码中，当设置了自定义 S3 endpoint 时，硬编码 `force_path_style(true)`，无法关闭。

### 当前 Workaround

不在 Neon 组件配置中设置 endpoint，改用 `AWS_ENDPOINT_URL` 环境变量传递 OBS 地址，绕过 path-style 强制逻辑。

### 建议修改

增加配置项控制 path-style 行为，或在自定义 endpoint 时默认使用 virtual-hosted-style。

---

## 决策框架

当问题积累到一定数量时，考虑维护 Lakeon 自己的 Neon Fork：

| 维度 | 用官方镜像 | 维护 Fork |
|------|-----------|----------|
| 维护成本 | 低，但需要 workaround | 高，需要跟进上游更新 |
| 兼容性 | 依赖 workaround，环境变化易失效 | 从源头解决，一劳永逸 |
| 升级路径 | 直接拉取新版本 | 需要 rebase/merge 上游变更 |
| 适用阶段 | 开发/POC 阶段 | 生产阶段 |

**建议**：当 workaround 数量超过 3 个，或有 workaround 无法在生产环境可靠运行时，启动 Fork。
