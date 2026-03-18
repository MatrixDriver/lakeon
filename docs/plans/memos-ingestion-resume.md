# MemOS LoCoMo Ingestion 恢复指南

## 当前状态 (2026-03-18 18:25)

- **Ingestion 进度**: 243/544 adds (45%), 465 条记忆在 dbay PG
- **已完成用户**: user_0, user_3, user_5, user_7 各完成了部分 sessions
- **数据安全**: 所有记忆在 dbay.cloud PG 中，不会丢失
- **分支**: `~/code/MemOS` on `feat/dbay-postgres-backend`

## 恢复步骤

### 1. 启动 4 个 MemOS API 实例

```bash
cd ~/code/MemOS
for port in 8000 8001 8002 8003; do
    python3 -m uvicorn memos.api.product_api:app --host 0.0.0.0 --port $port > /tmp/memos-api-$port.log 2>&1 &
done
sleep 12
# 验证
for port in 8000 8001 8002 8003; do
    no_proxy=localhost curl -s http://localhost:$port/docs > /dev/null 2>&1 && echo "Port $port: UP" || echo "Port $port: DOWN"
done
```

### 2. 继续 Ingestion

不需要清数据。脚本会根据 success_records 跳过已完成的 session，未完成的会重新灌（MemOS add 是追加式，重复不影响搜索质量）。

```bash
cd ~/code/MemOS
NO_PROXY=localhost,127.0.0.1 no_proxy=localhost,127.0.0.1 \
bash evaluation/scripts/locomo/parallel_ingest.sh dbay-v1 > /tmp/parallel-ingest.log 2>&1 &
echo "PID: $!"
```

### 3. 监控进度

```bash
# 查看总进度
TOTAL=0; for port in 8000 8001 8002 8003; do
    N=$(grep "POST /product/add.*200" /tmp/memos-api-$port.log 2>/dev/null | wc -l | tr -d ' ')
    echo "Port $port: $N adds"; TOTAL=$((TOTAL + N))
done; echo "Total: $TOTAL adds"

# 查看 DB 记忆数
no_proxy=pg.dbay.cloud PGPASSWORD="wukNl9D1RSyU6y3iD8YnOfsC" \
psql "postgresql://user_e65dc95a@pg.dbay.cloud:4432/memos-dbay-test?sslmode=require&options=endpoint%3Dmemos-dbay-test" \
-c "SELECT count(*) FROM memos.memories;"
```

### 4. Ingestion 完成后 → 运行 Search

```bash
cd ~/code/MemOS/evaluation
NO_PROXY=localhost,127.0.0.1 no_proxy=localhost,127.0.0.1 \
python3 scripts/locomo/locomo_search.py --lib memos-api --version dbay-v1 --workers 5 --top_k 15
```

### 5. 运行 Responses (用 Qwen3.5 no_think)

locomo_responses.py 需要传 enable_thinking=False。修改方法：在 `locomo_responses.py` 的 `locomo_response` 函数中，`client.chat.completions.create` 加 `extra_body={"enable_thinking": False}`。

或者直接改 .env 用 DeepSeek V3（不需要 no_think）：
```
CHAT_MODEL=deepseek-ai/DeepSeek-V3
```

```bash
cd ~/code/MemOS/evaluation
NO_PROXY=localhost,127.0.0.1 no_proxy=localhost,127.0.0.1 \
python3 scripts/locomo/locomo_responses.py --lib memos-api --version dbay-v1
```

### 6. 运行 Eval

同样需要处理 enable_thinking。locomo_eval.py 的 `locomo_grader` 函数也调 LLM。

```bash
cd ~/code/MemOS/evaluation
NO_PROXY=localhost,127.0.0.1 no_proxy=localhost,127.0.0.1 \
python3 scripts/locomo/locomo_eval.py --lib memos-api --version dbay-v1 --num_runs 1 --options lexical
```

### 7. 运行 Metrics

```bash
cd ~/code/MemOS/evaluation
python3 scripts/locomo/locomo_metric.py --lib memos-api --version dbay-v1
```

结果文件: `evaluation/results/locomo/memos-api-dbay-v1/memos-api_locomo_grades.json`

关键指标:
- `llm_judge_score` — 准确率
- `context_tokens` — 平均每题 context token 数（用于计算 token 下降比例）

## 连接信息

| 项目 | 值 |
|------|-----|
| dbay host | pg.dbay.cloud:4432 |
| dbay user | user_e65dc95a |
| dbay password | wukNl9D1RSyU6y3iD8YnOfsC |
| dbay db | memos-dbay-test |
| SiliconFlow key | (见 ~/code/lakeon/.env) |
| MemOS model | Qwen/Qwen3.5-35B-A3B (enable_thinking=false) |
| MemOS branch | feat/dbay-postgres-backend |

## 时间估算

| 阶段 | 剩余工作量 | 预计时间 |
|------|-----------|---------|
| Ingestion | ~301 adds | ~3h |
| Search | 1540 queries | ~15min |
| Responses | 1540 LLM calls | ~30min |
| Eval | 1540 LLM calls | ~30min |
| Metric | 计算 | <1min |
| **总计** | | **~4.5h** |
