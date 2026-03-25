# OBS STS 租户隔离设计

> 状态：待实现
> 日期：2026-03-25

---

## 背景

当前 Job Pod（知识库、数据集导出、数据湖作业）通过 `obs-credentials` K8s Secret 注入全桶 AK/SK。用户代码可以用这些凭据访问 `dbay-mainstore` 桶内任意路径——包括其他租户的数据集、知识库文件。数据集详情页的代码示例也暴露了桶名和 endpoint。

---

## 设计决策

| 决策项 | 选择 | 原因 |
|--------|------|------|
| 凭据类型 | 华为云 IAM STS 临时凭据 | 有效期可控，可附加 IAM Policy 限定前缀 |
| 有效期 | 15 分钟 | 足够 Job 运行，超时 Job 本身也会失败 |
| 路径隔离 | `datasets/tn_{tenantId}/*` + `knowledge/tn_{tenantId}/*` | 租户只能访问自己的数据 |
| 注入方式 | 环境变量直接注入（非 Secret 引用） | STS 凭据是临时的，不需要持久化为 Secret |
| API Pod | 不改动 | 服务端凭据不暴露给用户 |

---

## 后端

### 1. 新增 `ObsStsService`

调用华为云 IAM STS API 获取带路径策略的临时凭据。

**API 端点**：
```
POST https://iam.{region}.myhuaweicloud.com/v3.0/OS-CREDENTIAL/securitytokens
```

**IAM Policy**（按租户 ID 限定 OBS 对象前缀）：
```json
{
  "Version": "1.1",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "obs:object:GetObject",
      "obs:object:PutObject",
      "obs:object:DeleteObject"
    ],
    "Resource": [
      "obs:*:*:object:{bucket}/datasets/tn_{tenantId}/*",
      "obs:*:*:object:{bucket}/knowledge/tn_{tenantId}/*"
    ]
  }]
}
```

**认证方式**：使用现有 `LakeonProperties.ObsConfig` 中的 AK/SK 调用 IAM API（需要有 `iam:tokens:assume` 权限）。

**返回**：
```java
public record StsCredentials(
    String accessKey,
    String secretKey,
    String sessionToken,
    Instant expiresAt
) {}
```

**缓存**：按 `tenantId` 缓存，距过期 2 分钟内刷新。避免每个 Job 都调 IAM API。

### 2. `JobPodManager` 改造

当前：从 `obs-credentials` Secret 引用 AK/SK（`secretKeyRef`）。

改为：
1. `launchJobPod()` 新增 `tenantId` 参数
2. 调用 `ObsStsService.getCredentials(tenantId)` 获取临时凭据
3. 通过环境变量直接注入（非 Secret 引用）：
   - `OBS_ACCESS_KEY` = STS accessKey
   - `OBS_SECRET_KEY` = STS secretKey
   - `OBS_SESSION_TOKEN` = STS sessionToken（新增）
4. 删除对 `obs-credentials` Secret 的 `secretKeyRef` 引用

### 3. Python 脚本改造

**`knowledge/job/main.py`** 和 **`knowledge/job/export_parquet.py`**：

boto3 客户端添加 `aws_session_token` 参数：
```python
s3 = boto3.client("s3",
    endpoint_url=obs_endpoint,
    aws_access_key_id=os.environ["OBS_ACCESS_KEY"],
    aws_secret_access_key=os.environ["OBS_SECRET_KEY"],
    aws_session_token=os.environ.get("OBS_SESSION_TOKEN"),  # 新增
    region_name=os.environ.get("OBS_REGION", "cn-north-4"),
    config=BotoConfig(
        s3={"addressing_style": "virtual"},
        signature_version="s3",
    ))
```

注意：STS 临时凭据需要在每个 boto3 客户端创建处都加上 `aws_session_token`。

### 4. Datalake Job Pod 凭据注入

**`DatalakeService`**（或 `PythonJobRunner`）：创建 CCI Job 时注入 STS 临时凭据作为环境变量，让用户脚本可以通过 `DATASET_PATH_*` 读取自己的数据集。

环境变量注入：
- `OBS_ACCESS_KEY` = STS accessKey
- `OBS_SECRET_KEY` = STS secretKey
- `OBS_SESSION_TOKEN` = STS sessionToken
- `OBS_ENDPOINT` = props.getObs().getEndpoint()
- `OBS_BUCKET` = props.getObs().getBucket()
- `OBS_REGION` = props.getObs().getRegion()

### 5. 代码示例修复

**`DatasetService.buildCodeSnippets()`**：不再暴露桶名和 endpoint。

所有代码示例改为使用 `DATASET_PATH` 环境变量（与作业运行时一致）：

Pandas 示例：
```python
import os, pandas as pd
df = pd.read_parquet(os.environ["DATASET_PATH"])
```

Ray 示例：
```python
import os, ray
ds = ray.data.read_parquet(os.environ["DATASET_PATH"])
```

DuckDB 示例：
```python
import os, duckdb
conn = duckdb.connect()
df = conn.execute(f"SELECT * FROM '{os.environ[\"DATASET_PATH\"]}'").df()
```

预签名下载 URL 保留（已有过期时间，安全）。

---

## 影响范围

| 文件 | 改动 |
|------|------|
| 新建 `ObsStsService.java` | 华为 IAM STS 调用 + 租户策略 + 缓存 |
| `JobPodManager.java` | Secret 引用 → STS 环境变量注入 |
| `JobService.java` | 传递 tenantId 给 JobPodManager |
| `DatalakeService.java` 或 `PythonJobRunner.java` | 注入 STS 凭据到 CCI Job |
| `knowledge/job/main.py` | 加 `aws_session_token` |
| `knowledge/job/export_parquet.py` | 加 `aws_session_token` |
| `DatasetService.java` | buildCodeSnippets 不暴露桶名 |

---

## 不改动

- API Pod 自身的 OBS 凭据（`deployment-api.yaml` 中的 Secret 引用）——服务端用，不暴露给用户
- 预签名 URL 生成逻辑（已有过期时间，安全）
- `obs-credentials` Secret 本身——API Pod 仍需要，但 Job Pod 不再使用

---

## 非目标

- 每租户独立 OBS 桶
- OBS 桶策略（Bucket Policy）—— STS 已足够，不需要桶级别策略
- API Pod 的凭据轮换（可后续做）
