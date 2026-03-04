# Lakeon 部署注意事项 (Windows/Docker Desktop 环境)

这份文档总结了在 Windows 环境下部署 Lakeon 平台时遇到的常见坑位及解决方案，旨在帮助贡献者和其他 Agent 快速避坑。

## 1. 基础设施环境 (WSL 2 & Kubernetes)

### ⚠️ Kubernetes 启动卡死 (Starting...)
*   **现象**：Docker Desktop 的 Kubernetes 状态长时间显示为 "Starting"，`kubectl` 无法连接。
*   **根本原因**：WSL 2 默认资源分配不足，导致 K8s 核心组件（API Server, etcd）启动超时或因 OOM 被杀。
*   **解决方案**：
    1.  在用户目录下创建/修改 `%USERPROFILE%\.wslconfig` 文件。
    2.  强制分配资源：
        ```ini
        [wsl2]
        memory=8GB    # 建议至少 6GB，推荐 8GB
        processors=4  # 建议至少 4 核
        localhostForwarding=true
        ```
    3.  运行 `wsl --shutdown` 并重启 Docker Desktop。
    4.  **关闭 "Resource Saver" 模式**：在 Docker Settings -> Resources 中取消勾选 "Enable Resource Saver"，该模式经常导致 K8s 初始化死锁。

## 2. 工具链依赖

### ⚠️ 命令映射与权限
*   **现象**：`kubectl`, `helm` 或 `docker` 无法通过普通命令行调用。
*   **解决方案**：
    *   Docker 自带工具通常位于 `C:\Program Files\Docker\Docker\resources\bin`，需手动加入系统 PATH。
    *   推荐使用 **WinGet** 补齐缺失工具：
        ```powershell
        winget install Helm.Helm
        winget install jqlang.jq
        ```

## 3. 数据库初始化 (Metadata DB)

### ⚠️ API 启动循环崩溃 (CrashLoopBackOff)
*   **现象**：`lakeon-api` 启动失败，日志显示 `Schema-validation: missing table [operation_logs]`。
*   **根本原因**：Helm Chart 的 `configmap-metadata-db.yaml` 中可能遗漏了部分业务表的初始化 SQL，而 API 开启了 `hibernate.ddl-auto: validate`。
*   **解决方案**：
    *   手动连接到 `metadata-db` 补全表结构，或更新 Helm Chart 中的初始化脚本。
    *   **避坑提示**：开发环境下建议将 `spring.jpa.hibernate.ddl-auto` 修改为 `update`。

## 4. 镜像构建与版本

### ⚠️ 环境变量丢失导致的构建失败
*   **现象**：`docker build` 报错 `docker-credential-desktop: executable file not found`。
*   **解决方案**：在 Windows PowerShell 中临时构建时，务必确保 `PATH` 中包含 Docker 的 bin 目录，否则 Docker 插件无法调用凭据管理器。

## 5. 开发调试建议

### 端口映射清单
| 服务名称 | 集群端口 | 推荐转发端口 | 说明 |
| :--- | :--- | :--- | :--- |
| lakeon-api | 8080 | 8080 | 控制面业务接口 |
| lakeon-console | 80 | 8081 | Web 管理后台 |
| proxy | 4432 | 4432 | PG 协议接入点 |
| minio | 9001 | 9001 | 存储管理后台 (minioadmin/minioadmin) |

### PowerShell 转义提示
在 Windows 下调用 API 时，JSON 字符串的转义非常容易出错。建议使用以下模板：
```powershell
curl.exe -X POST http://localhost:8080/api/v1/tenants `
  -H "Content-Type: application/json" `
  -d '{\"name\": \"tenant-test\"}'
```

---
*Created by Antigravity Agent @ 2026-03-04*
