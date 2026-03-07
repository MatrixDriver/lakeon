# 阶段 2 验证总结：本地 K8s + OBS + 华为云 RDS

本阶段目标是验证 Lakeon 控制面连接公网 RDS PostgreSQL 的连通性、权限及持久化能力。

## 1. 遇到问题及解决办法

### 1.1 数据库协议层连接拒绝 (`server closed the connection unexpectedly`)
*   **现象**：`curl` 检测端口 5432 是通的，但 `psql` 连接时立即被断开，没有任何报错信息。
*   **原因**：
    1.  **公网白名单**：华为云 RDS 除了安全组外，还有独立的“实例白名单”控制。
    2.  **子网 ACL**：VPC 级别的子网控制列表拦截了出入流量。
    3.  **SSL 强制连接**：华为云 RDS 默认开启了 SSL，未提供证书的连接会被直接掐断。
*   **解决建议**：
    *   确保公网白名单包含本地出口 IP (`18.140.65.198`)。
    *   开放子网 ACL 规则。
    *   连接命令中必须带上 `--sslmode=verify-ca` 并指向 `ca.pem` 证书路径。

### 1.2 跨权限操作受阻 (`permission denied for schema public`)
*   **现象**：使用 `root` 账号创建 `lakeon` 数据库后，执行初始化脚本报错无法在 `public` schema 下建表。
*   **原因**：PostgreSQL 15+ 版本增强了安全性，移除了 `public` schema 的默认创建权限。
*   **解决办法**：执行 `GRANT ALL ON SCHEMA public TO lakeon;`。

### 1.3 自动化测试环境兼容性 (Windows/WSL/Bash)
*   **现象**：`integration-test-win.sh` 在执行过程中报错 `\r` 命令找不到，或者无法连接 K8s 集群。
*   **原因**：
    1.  **换行符**：Git 检出时可能将文件转为了 CRLF。
    2.  **Kubeconfig**：Bash/WSL 环境下无法自动继承 Windows 宿主机的 Kubeconfig 路径。
*   **解决办法**：
    *   使用 `tr -d '\r'` 修复脚本格式。
    *   对于 Kubernetes 的验证，手动通过 `kubectl exec` 在 Pod 内部进行冒烟测试。

## 2. 最终验证状态

| 验证项 | 状态 | 备注 |
| :--- | :--- | :--- |
| RDS 连通性 | ✅ 通过 | 端口连通，SSL 握手正常 |
| 权限体系 | ✅ 通过 | lakeon 账号具备库级管理权限 |
| 数据库初始化 | ✅ 通过 | V1, V2, V3 脚本执行成功，表结构已建立 |
| API -> RDS 链路 | ✅ 通过 | 冒烟测试确认 API 写入的数据已进入 RDS |
| 数据持久性 | ✅ 通过 | Pod 重启后，RDS 数据保持完好 |

## 3. 下一阶段 (Stage 3) 关键提示
*   在 CCE 集群中部署时，OBS 和 RDS 将通过 **VPC 内网**访问，届时需将 `values.yaml` 中的 `metadataDb.host` 改为 RDS 的**内网 IP**，并关闭公网访问。
