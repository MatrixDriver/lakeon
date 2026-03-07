# 华为云 API 设计优化建议报告 (2026-03-06)

> **报告背景**：针对 Lakeon 平台在阶段 3（容器化部署与云集成）实施过程中，AI Agent 在调用华为云 OpenAPI 时遇到的核心故障与体验痛点进行的总结。旨在通过反馈提升华为云 API 的规范性、易用性及对 AI 开发工具的友好度。

---

## 1. 认证与鉴权 (Authentication & Authorization)

### 1.1 签名算法门槛过高
*   **问题描述**：华为云 V3 签名逻辑（HMAC-SHA256）极其复杂，涉及 Canonical Request 的构建、String to Sign 的拼接以及多级派生密钥。AI Agent 手动拼装时极易在换行符或 Header 排序上出错。
*   **涉及 API**：全服务通用（如 `hwcloud.py` 中的 `api` 签名函数）。
*   **业界最佳实践参考**：**AWS Signature V4** 虽然也是行业标准，但 AWS 提供了极其详尽的 [Test Suite](https://docs.aws.amazon.com/general/latest/gr/signature-v4-test-suite.html)（包含原始请求与最终签名的对照样例）。此外，**Stripe** 和 **GitHub** 推荐在开发者快速测试阶段使用简单的 API Key（Bearer Token），将复杂的 HMAC 握手留给 SDK 内部。

### 1.2 资源 ID 与 Project ID 的强耦合
*   **问题描述**：几乎所有调用都需要 `project_id`，但 AK/SK 认证初期无法直接获取当前区域的有效 Project ID，需要先遍历项目再筛选。
*   **涉及 API**：`GET https://iam.myhuaweicloud.com/v3/projects`
*   **业界最佳实践参考**：**AWS** 的大部分 API 允许在请求中省略 Account ID，系统通过身份凭据（STS/IAM Token）自动推断上下文。**Google Cloud** 虽然在 URL 中强制包含 Project ID，但其 CLI 和 SDK 会自动感知当前活跃项目（Default Project Context），无需开发者硬编码。

---

## 2. SWR 镜像仓储服务 (Container Registry)

### 2.1 Manifest 解析兼容性问题 (致命痛点)
*   **问题描述**：使用现代 Docker Buildx 构建的多架构（Multi-arch）镜像推送到 SWR 时，频繁报错 `Invalid image, fail to parse 'manifest.json'`。
*   **涉及 API**：`docker push swr.cn-south-1.myhuaweicloud.com/...` (SWR 后台解析逻辑)
*   **业界最佳实践参考**：**GitHub Container Registry (GHCR)** 和 **Docker Hub** 完美支持 [OCI Index (manifest list)](https://github.com/opencontainers/image-spec/blob/main/image-index.md)。它们能够自动识别并存储跨平台镜像，客户端无需回退到 `linux/amd64` 单平台构建。

### 2.2 登录 Token 的有效期与自动化限制
*   **问题描述**：SWR 登录令牌有效期仅 24 小时，且获取算法与标准 API 鉴权不一致，导致 K8s 的 ImagePullSecret 难以自动化维护。
*   **涉及 API**：`GET https://swr.cn-south-1.myhuaweicloud.com/v2/manage/utils/get-login-info`
*   **业界最佳实践参考**：**Azure Container Registry (ACR)** 支持使用 **Service Principal** 或 **Managed Identity** 直接鉴权，无需生成临时 Token。K8s 只要配置了对应的 AAD 身份，拉取镜像过程对开发者是全透明的。

---

## 3. 网络与安全组编排 (Networking & Security)

### 3.1 默认安全组的静默隔离
*   **问题描述**：CCE 集群安全组默认关闭了 5432 等端口。在内网直连 RDS 时，流量被静默拦截，缺乏反馈。
*   **涉及 API**：`POST https://vpc.cn-south-1.myhuaweicloud.com/v1/{pid}/security-group-rules`
*   **业界最佳实践参考**：**AWS VPC Reachability Analyzer** 允许用户输入源和目的资源，API 会直接返回哪一条规则（Security Group 或 Network ACL）拦截了请求。**GCP** 则提供防火墙规则模拟器，在规则生效前即可预测流量可达性。

### 3.2 ELB API 版本的割裂
*   **问题描述**：ELB 同时暴露 v2 和 v3 接口，且级联删除等关键参数支持不统一（例如 v2 支持 `cascade` 参数而 v3 有所变化）。
*   **涉及 API**：`DELETE https://elb.cn-south-1.myhuaweicloud.com/v2/{pid}/elb/loadbalancers/{id}?cascade=true`
*   **业界最佳实践参考**：**Stripe API** 的版本控制被誉为行业精髓，它通过 `Stripe-Version` Header 控制版本，并在后台进行透明的请求转换（Transformation），确保同一功能的旧版本 endpoint 能以定义良好的行为持续工作，而非让开发者在 v2 和 v3 之间二选一。

---

## 4. 状态轨迹与异步反馈 (Async & Polling)

### 4.1 缺乏统一的状态感知端点
*   **问题描述**：创建节点或集群是耗时操作，开发者只能不断轮询。
*   **涉及 API**：`GET https://cce.cn-south-1.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/nodes`
*   **业界最佳实践参考**：**Google Cloud** 广泛采用了 **Long Running Operations (LRO)** 模式，异步调用的响应会直接返回一个 `Operation` 对象路径，通过该路径可以统一查询进度、错误信息，并支持设置超时机制。

### 4.2 错误响应的颗粒度缺失
*   **问题描述**：权限不足时仅返回 403，不通知缺失的具体 Action，导致无法自动补全权限。
*   **涉及 API**：全服务通用（如 `hwcloud.py` 中的 `get_project_id`）。
*   **业界最佳实践参考**：**AWS IAM** 在拒绝请求时会返回一个 `DecodedMessage`（加密字符串），开发者解密后可以看到详细的权限评估逻辑，包括具体的 Policy 名和被拒绝的操作项。**Stripe** 则在错误 JSON 中包含 `code`（枚举值）和 `doc_url`，引导开发者直接查看文档解决。

---

## 5. 对 AI Agent 的特殊优化建议 (Agent-Friendly API)

### 5.1 参数命名一致性
*   **问题现状**：同一资源在不同服务中被称为 `id`, `uid` 或 `instance_id`。
*   **业界最佳实践参考**：**Microsoft Graph API** 强制执行统一的命名规范（如所有唯一标识符统一为 `id`），极大降低了 AI 模型猜测参数名的成本。

### 5.2 强化 Raw HTTP 范例
*   **问题现状**：文档重度依赖 SDK，缺乏纯粹的 HTTP Request 示例。
*   **业界最佳实践参考**：**DigitalOcean API** 文档在每个 Endpoint 旁边都会并列展示 **Curl**, **Go**, **Python** 示例，且 Curl 示例包含完整的 Payload 结构，这对不使用特定语言 SDK 的 Agent 极其友好。

---
**总结人员**：Lakeon 实施团队 (Antigravity Agent)
**日期**：2026-03-06
