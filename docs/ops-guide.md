# 运维指南

本文档面向管理员，介绍 Lakeon (DBay) 在华为云 CCE 环境的日常运维操作。

## 前置条件

- `kubectl`、`helm`、`python3` 已安装
- kubeconfig 已配置：`~/.kube/cce-lakeon-config`
- 凭据文件已就位：`deploy/cce/.env.cce`（包含 OBS AK/SK、RDS 地址和密码）

## 日常操作

### 启动站点

```bash
./deploy/cce/start.sh
```

完整流程：开启 ECS 节点 + 启动 RDS → 修复 containerd → 创建 ELB/EIP → Helm 部署 → **冒烟测试**。

启动后会自动运行 6 项冒烟测试验证服务可用性。

### 关停站点

```bash
# 标准关停：关 ECS + RDS，保留 CCE 集群 + ELB + EIP（省 ~¥65/天）
./deploy/cce/stop.sh

# 极致省钱：额外删除 ELB + 释放 EIP（省 ~¥89/天）
./deploy/cce/stop.sh --full
```

关停顺序：缩容 K8s 工作负载 → Helm 卸载 → 关闭云资源。

### 仅重新部署（不重启云资源）

代码更新后只需重新部署，不用重启 ECS/RDS：

```bash
./deploy/cce/deploy.sh
```

自动从 `.env.cce` 加载凭据，执行 `helm upgrade`，并运行冒烟测试。

### 仅运行冒烟测试

```bash
./deploy/cce/smoke-test.sh
```

检查项：

| # | 检查项 | 说明 |
|---|--------|------|
| 1 | API Pod 运行中 | kubectl 检查 Pod 状态 |
| 2 | API HTTPS 可达 | curl 访问 api.dbay.cloud:8443 |
| 3 | SRE Dashboard 数据正常 | 验证 admin API 返回有效 JSON |
| 4 | Pageserver 健康 | 内部健康检查 |
| 5 | Proxy Pod 运行中 | kubectl 检查 Pod 状态 |
| 6 | PG 端口可达 | TCP 探测 4432 端口 |

## 镜像构建与推送

```bash
# 构建并推送 API 镜像
./deploy/cce/build-and-push-api.sh

# 构建并推送 Console 镜像
./deploy/cce/build-and-push-console.sh

# 构建并推送 Admin 镜像
./deploy/cce/build-and-push-admin.sh
```

推送后运行 `./deploy/cce/deploy.sh` 更新部署。

## 云资源管理

`hwcloud.py` 封装了华为云 API，用于管理底层云资源：

```bash
export KUBECONFIG=~/.kube/cce-lakeon-config

# 查看所有云资源状态
python3 deploy/cce/hwcloud.py status

# 单独启动/停止云资源（不含 K8s 部署）
python3 deploy/cce/hwcloud.py start-cloud
python3 deploy/cce/hwcloud.py stop-cloud

# 发现并缓存节点信息
python3 deploy/cce/hwcloud.py discover

# 列出云资源清单（JSON）
python3 deploy/cce/hwcloud.py list-resources
```

## 脚本关系

```
start.sh ─────┬─→ hwcloud.py start-cloud   （启动 ECS/RDS/ELB/EIP）
              ├─→ containerd 修复            （SSH 到节点）
              ├─→ helm upgrade               （自动加载 .env.cce）
              └─→ smoke-test.sh             （冒烟测试）

deploy.sh ────┬─→ helm upgrade               （自动加载 .env.cce）
              └─→ smoke-test.sh             （冒烟测试）

stop.sh ──────┬─→ kubectl scale 0            （缩容）
              ├─→ helm uninstall             （卸载）
              └─→ hwcloud.py stop-cloud      （关停云资源）
```

## 访问地址

| 服务 | 地址 |
|------|------|
| Web 控制台 | https://dbay.cloud （Railway） |
| SRE 管理台 | https://admin.dbay.cloud （Railway） |
| API (HTTPS) | https://api.dbay.cloud:8443 |
| PG 连接 | postgresql://<EIP>:4432 |

## 凭据文件

`deploy/cce/.env.cce`（已 gitignore，不入库）：

```bash
export OBS_AK=<OBS AccessKey>
export OBS_SK=<OBS SecretKey>
export RDS_PRIVATE_IP=<RDS 内网 IP>
export RDS_PASSWORD=<RDS 密码>
export CCE_NODE_PASSWORD=<节点密码>
```

## 故障排查

```bash
# 查看 API Pod 日志
KUBECONFIG=~/.kube/cce-lakeon-config kubectl logs -n lakeon -l app=lakeon-api --tail=50

# 查看 Pod 状态
kubectl get pods -n lakeon -o wide

# 查看 compute 节点
kubectl get pods -n lakeon-compute -o wide

# 检查 ConfigMap 中的数据库连接
kubectl get cm lakeon-api-config -n lakeon -o yaml | grep LAKEON_DB_DSN
```

常见问题：

- **API CrashLoopBackOff + `Connection to localhost:5432 refused`**：helm upgrade 时未传 RDS 参数。使用 `./deploy/cce/deploy.sh` 代替手动 helm 命令。
- **Console 登录失败（网络错误）**：检查 ELB 8443 监听器是否存在，API Pod 是否正常。
- **Compute Pod 启动失败（Operation not permitted）**：节点 containerd core ulimit 未修复，运行 `start.sh` 会自动修复。
