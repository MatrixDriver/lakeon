# Phase 0a Open Issues + Lessons Learned

## Part 1 — Phase 0a 部署调试暴露的 11 个 bug（已全部修复）

这些 bug 全部是**只有在真实 Railway 部署中才能暴露**的——单元测试和集成测试都通过的情况下，生产环境依然逐一踩进去。按发现顺序归档，给 Phase 0b 做前车之鉴。

### Bug 1: Railway rootDirectory 与 Dockerfile context 不匹配

- **症状**：Build daemon 报 `"/sre-agent/entrypoint.sh": not found`
- **根因**：Railway service dashboard 上 rootDirectory 被设为 `/sre-agent`，这会覆盖 `railway.toml` 里声明的 build context。Dockerfile 里 `COPY sre-agent/entrypoint.sh ...` 的绝对路径在 build context = `sre-agent/` 时解析成 `sre-agent/sre-agent/entrypoint.sh`，自然找不到
- **教训**：Railway UI 配置比 railway.toml 优先级高；必须两边对齐。Phase 0b 如果新建 service，rootDirectory 要和 Dockerfile 的 COPY 路径约定严格对齐
- **Commit**: `63bd677c` / 最终 `d9faecf7`

### Bug 2: 私有 lakeon repo 的 git+URL 安装失败

- **症状**：Railway build 时 `uv pip install "dbay-sre-mcp @ git+https://github.com/MatrixDriver/lakeon.git#subdirectory=dbay-sre-mcp"` 报 `failed to fetch branch or tag main`
- **根因**：Railway build container 没有 GitHub 凭证，无法 clone 私有仓
- **教训**：跨目录依赖（sre-agent 依赖 dbay-sre-mcp）不能假设 build 环境能克隆仓库。**正确做法是公开包（PyPI 或者注册成公开 package registry）**
- **修复**：把 `dbay-sre-mcp` 发布到 PyPI（0.1.0），Dockerfile 改 `pip install dbay-sre-mcp==0.1.0`。顺带的好处是任何外部 agent 都能用这个 MCP server。**Phase 0b 的 agent_session_log 库也建议尽早上 PyPI**，让别人（和我们自己）在任何 runtime 里都能 `pip install agent-session-log`
- **Commit**: `fb093170`

### Bug 3: Dockerfile 漏 COPY main.py

- **症状**：容器启动 `python: can't open file '/app/main.py': No such file or directory`，crash-loop
- **根因**：Task a77dacae 的 subagent 加了 `main.py` 作为新入口、改了 entrypoint.sh 指向它，但后续 Dockerfile 重构时我没 COPY `main.py` 到镜像
- **教训**：Dockerfile COPY 清单必须和 entrypoint 引用的文件严格对齐，不能依赖"install 会顺带带入"。推荐加一个 CI 检查：遍历 entrypoint 脚本和 package 声明引用的文件，确认都在 Dockerfile 里有 COPY
- **Commit**: `78a4567e`

### Bug 4: `hermes gateway start --config` flag 不存在

- **症状**：`hermes: error: unrecognized arguments: --config /app/hermes_config/config.yaml`
- **根因**：Task a77dacae subagent 按 plan 模板写了 `--config` flag，但 hermes CLI 根本没这个 flag。Hermes 读配置的方式是从 `$HERMES_HOME/config.yaml` 自动加载
- **教训**：任何和外部 CLI 的 interface 假设（参数名、子命令、env 变量）都必须查源码或实测验证，不能照搬 plan 模板里的猜测
- **修复**：去掉 `--config`，在 main.py 启动 hermes 子进程前把 packaged config.yaml 拷贝到 `$HERMES_HOME/config.yaml`
- **Commit**: `2b71360c`

### Bug 5: `hermes gateway start` 要求作为容器主进程

- **症状**：hermes 启动时打印 `The gateway runs as the container's main process. Or run the gateway directly: hermes gateway run` 然后退出
- **根因**：`start` 是 systemd-service 语义（后台启动），`run` 是前台 exec 语义。我们作为 subprocess 启动需要前台版本
- **教训**：`start/run/serve/daemon` 这些子命令语义在不同 CLI 工具里完全不一样；千万不要凭直觉假设。Phase 0b 接入任何新工具时必须先 `--help` 或读源码
- **Commit**: `bfd1ac7c`

### Bug 6: `DBAY_LOGS_DSN` vs `LOG_DB_DSN` 命名不一致

- **症状**：Watcher 跑起来后报 `No log DB DSN configured. Set LOG_DB_DSN env var`
- **根因**：我们的 runbook 和 Railway 用 `DBAY_LOGS_DSN`（语义化名字），但 `dbay-sre-mcp` 内部写死了 `LOG_DB_DSN`。两边不一致
- **教训**：下游 package 的 env 名应该在上游（runbook + Railway）对齐，或者在 main.py 做 bridge。Phase 0b 如果新增任何依赖包，它们用的 env 名称要第一时间列进 verify_env.py 的约定
- **修复**：main.py 加一个开头 env bridge `os.environ["LOG_DB_DSN"] = os.environ["DBAY_LOGS_DSN"]`
- **Commit**: `c62d2cfc`

### Bug 7: `hermes-agent[feishu]` extra 没装

- **症状**：hermes 启动后警告 `No adapter available for feishu`，深入看是 `Feishu: lark-oapi not installed`
- **根因**：我们装的是 `hermes-agent`（主包）而不是 `hermes-agent[feishu]`（带 lark-oapi + qrcode 的 extra）
- **教训**：hermes 的 platform adapter 是按 extra 组织的。**给每个 platform 对照 hermes 的 pyproject.toml extras 表装对**。同样的问题会出现在其他 platform（Discord、Telegram、Slack 等）——都是独立 extras
- **修复**：Dockerfile `pip install "hermes-agent[feishu,mcp] @ git+..."`（[mcp] 是后面 Bug 11 加的）
- **Commit**: `ee8b0dc3`（feishu），`<next-commit>`（mcp）

### Bug 8: skills 没 seed 到 `$HERMES_HOME/skills/`

- **症状**：bot 承认有 `cold-start-watcher` skill 但 `skill_view` 返回空内容，因为 hermes 只从 `$HERMES_HOME/skills/` 读 SKILL.md
- **根因**：我们把 skills COPY 到 `/app/skills/`（main.py 需要作为 Python package 导入），但 hermes 自己的 skill scanner 不看这个路径
- **教训**：hermes runtime 的 skill/config/data 全部以 `$HERMES_HOME` 为根。任何要让 LLM 看到的静态资源都必须 seed 到这个目录，不能依赖 `/app/`
- **修复**：main.py 启动时 `shutil.copytree("/app/skills/", "$HERMES_HOME/skills/", overwrite=True)`
- **Commit**: `ad07c092`

### Bug 9: LLM 默认不读 SKILL.md 内容，要 DESCRIPTION.md 才注入 system prompt

- **症状**：bot 能列出 skill 名字但以为它们"只有名字没有实际配置，监控并未启动"
- **根因**：hermes 的 system prompt 里只注入 skill 的 frontmatter `description`（summary 级）；完整 SKILL.md 只在 LLM 主动调 `skill_view` 时才拉取。单靠 SKILL.md body 无法让 LLM 在每次对话都知道架构
- **教训**：category-level `DESCRIPTION.md` 是 hermes 专门为"对整类 skill 给 LLM 介绍上下文"设计的文件。Phase 0b 的 reading companion 等任何新 skill 族都应该配一个 DESCRIPTION.md，而不是指望 LLM 去 skill_view
- **修复**：写 `skills/sre/DESCRIPTION.md` 详述架构 + 数据落盘 + 行为约定
- **Commit**: `8c5c0e8d`

### Bug 10: SKILL.md frontmatter `name` 用连字符，目录用下划线 → `skill_view` 永远失败

- **症状**：LLM 调 `skill_view("cold-start-watcher")` 报 "skill not found"；bot 解读成"skills 是空壳"继续否认监控
- **根因**：hermes `skill_view` 按 `parent_dir.name == name` 查找。我们目录叫 `cold_start_watcher`（Python module 命名约定），frontmatter 写的是 `cold-start-watcher`（hyphenated），永远不匹配
- **教训**：hermes 的 skill 查找约定要求 frontmatter name 严格等于目录名。Phase 0b 的任何新 skill 都按这个约定——连字符 vs 下划线不能混用
- **修复**：统一用下划线：`name: cold_start_watcher`
- **Commit**: `2a3ce106`

### Bug 11: OBS bucket 名字猜错（`lakeon-neon` 实际是 `dbay-mainstore`）

- **症状**：sync_loop 启动后所有 upload 返回 `OBS put failed 404: None`
- **根因**：我从 lakeon helm values.yaml 看到 `bucket: "lakeon-neon"` 就当成生产真实值。但生产 env var 被 override 成 `dbay-mainstore`，values.yaml 是模板默认而已
- **教训**：**生产配置永远以 runtime 环境（K8s ConfigMap / env var）为准，不要只看代码里的模板默认**。Phase 0b 任何华为云资源的名字（bucket / VPC / security group），都要通过 CLI/API 查真实列表而不是读代码猜
- **修复**：
  1. `obs.listBuckets()` 找到真实的 `dbay-mainstore`
  2. `railway variables --set OBS_BUCKET=dbay-mainstore`
  3. redeploy，sync_loop 拿新 bucket，成功 upload
- **Commit**: N/A（只改 env var，无代码改动）

### Bonus Bug 12: Railway subprocess stdout 被 buffered，看不到子进程报错

- **症状**：sync_loop subprocess 不打印任何日志，根本看不到 OBS put 报 404 的错误
- **根因**：`subprocess.Popen(cmd)` 默认 inherit 父进程 stdout/stderr 但 Python 输出是 line-buffered；容器环境里得到 full buffering
- **教训**：Docker 容器里的所有 Python subprocess 都应该 `PYTHONUNBUFFERED=1`，不然报错会"消失"得无影无踪
- **修复**：`_start_subprocess` 里 `env = {**os.environ, "PYTHONUNBUFFERED": "1"}; subprocess.Popen(cmd, env=env)`
- **Commit**: `27f2a096`

---

## Part 2 — Phase 0b 实施前的持续跟进项

### 关于 LLM 可调用的 MCP 工具

Bug 12 修完后，我给 hermes LLM 接入了 `dbay-sre-mcp` 作为 MCP server（config.yaml 的 `mcp_servers.dbay_sre`）。之前 bot 答不了"今天冷启动时延是多少"是因为它只能读 `/data/hermes/data/` 目录，没法直接查 `dbay-logs` PG。现在可以。

Phase 0b 的 reading companion 类似地要接 `fetch-mcp` 等 MCP server 给 LLM 调 URL 抓取能力。

### 数据层 API 冻结

`agent_session_log==0.0.1`（main 分支里）的 API 已经被 Phase 0a 生产 dogfood 过：
- `Session.new` / `append_turn` / `branch` / `resolve_branches` / `conclude` / `close` / `record_outcome`
- `LogStore.list_sessions` / `get_session` / `search_text` / `replay`
- `SkillLedger.record_invocation` / `record_outcome` / `stats`
- `ObsSync.upload_session` / `upload_pending`

Phase 0b 必须使用这套 API，**不允许破坏性改动**。新增方法 OK。

### Phase 0b 交叉验证的具体项

1. reading companion 读 URL → Session.new(type="reading", ...) → append_turn / conclude / close — 应该一行都不用改 API
2. 每晚 22:00 daily-reflection 读当日 reading sessions → LogStore.list_sessions(type="reading", since="1d") + search_text
3. Cross-session 关联（"和你 4/12 读的 X 有关"）→ search_text 现在只扫 conclusion，可能 Phase 0b 要扩展扫 events 的 content（**这是期望的非破坏性扩展**——search_text 加 `include_events=True` flag，默认 False 保持 SRE 行为）

### 剩余未验证的 Phase 0a 行为（非 blocker）

- **实时冷启动端到端告警**：需 `scripts/simulate_cold_start.py` 触发真 incident。Jacky 可以随时跑，不阻塞 Phase 0b
- **outcome-checker 次晨 09:00 触发**：需等次日自动验证

---

## Part 3 — 先前就标记的遗留项

### P-0a-2. LLM retry 粗糙

当前 retry 是 2 次指数退避。生产级需要加抖动、circuit breaker、per-session cost cap。Phase 1+ 完善。

### P-0a-3. Session filesystem 单写者假设

`FilesystemStore` 文档中明示：假设单进程写。如果 Railway 以后横向扩展，commit log 会 race。Phase 0a 部署时 Railway service 明确是 1 replica。

### P-0a-4. Branch reload 并发时可能重复 branch_open

边缘场景：并行两次 Session.load + branch() 可能写第二条 branch_open 到 main。单进程假设下不触发。Phase 1 加文件锁。

---

## Part 4 — 2026-04-24 首个真·端到端闭环暴露的 bug（已全部修复）

Phase 0a 代码级 bug 全修完后，第一次拉通"cold-start alert → watcher → LLM 诊断 → 飞书推送"。这一轮又踩到 6 个 bug——全部是**基础设施/埋点/用户体验**层面，单跑 watcher 测不出来。

### Bug 15: `dbay-logs` PG pipeline 静默中断 3 天
- **症状**：`log_search` MCP 不管查什么 component 都是 `rows=0`，但 lakeon-api 里 `log.info` 在跑
- **根因**：`dbay-logs` 这个 PG 数据库里 pageserver 和 safekeeper 的 WAL consensus 3 天前就断了（具体原因见 `project_pageserver_reattach_gap.md`），所有 insert 早就 fail 了，fluentbit 堆在本地不 flush
- **教训**：SRE agent 依赖的观测管道必须自带健康探针；2 分钟 cron 能查 agent 自己的 session 数据，但不能查 fluentbit 是否在丢数据
- **修复**：`POST /admin/infra/purge-database/db_logs` → 重建 db_logs；fluentbit 收集恢复正常

### Bug 16: fluentbit DaemonSet SA 被删 8 天，所有节点的日志都没采
- **症状**：`kubectl get pods -n lakeon -l app=lakeon-fluentbit` → 0 pods，所有 Node 的 container stdout 全都没进 dbay-logs
- **根因**：`ServiceAccount lakeon-fluentbit` 不知道何时被删，DaemonSet 持续 CreateContainerError 但没有告警
- **教训**：K8s Daemon 的 SA 依赖必须有 `kubectl get sa` 的健康探测；这次靠肉眼 `kubectl describe ds` 才看出来
- **修复**：`kubectl apply -f fluentbit-sa.yaml`，DaemonSet 当场恢复

### Bug 17: lakeon-api 0.9.224 冷启动埋点写错位置
- **症状**：生产跑了 `ComputeLifecycleService.ensureCompute` 里的 `"compute started in ..."` 日志，但这条日志从来没出现过
- **根因**：实际冷启动路径是 `ProxyAdapterController.coldStartBranch` → 直接调 `ComputePodManager.createComputePodForBranch`，**根本没走 ensureCompute**。最早的埋点是加错了位置
- **教训**：埋点放哪个方法要看 call graph，不能凭方法名猜；compute 冷启的入口是 Proxy adapter，不是 lifecycle service
- **修复**：`ProxyAdapterController.coldStartBranch` 里补 `log.info("compute started in {}ms ...")`，lakeon-api 重建为 0.9.239

### Bug 18: lakeon-api 回滚到 0.9.224 丢失 0.9.238 修复
- **症状**：埋点补好打成 0.9.238 后，集群 rollout 把老版本 0.9.224 又拉回来一次，埋点丢了
- **根因**：`deploy/cce/helm/values.yaml` 上层 `defaultImageTag` 没更新；`kubectl set image` 只改了 current pod，deploy spec 仍指向 0.9.224
- **教训**：用 helm values 管镜像版本时，`set image` 和 `helm upgrade` 不能混着用；要么全走 `helm upgrade --set image.tag=X`，要么全走 `set image`
- **修复**：重新 `build-and-push-api.sh` 打 0.9.239，`kubectl set image deployment/lakeon-api lakeon-api=...:0.9.239`，观察 30min 确认稳定

### Bug 19: `simulate_cold_start.py` 走 suspend 路径，compute 没被真销毁
- **症状**：脚本打印 "cold start took 315ms (watcher threshold = 5000ms)"——因为 compute 根本没被销毁，第二次连接是复用 warm pod
- **根因**：`POST /admin/databases/{id}/suspend` 只改 DB 状态位，Pool 里的 compute pod 仍在；第二次连接直接命中 warm 池子
- **教训**：想测冷启动必须真的删掉 compute pod；suspend 是 app-level 状态切换，不等于资源销毁
- **修复**：脚本改用 `POST /admin/infra/restart-pod/{compute_pod_name}`（用 admin API 拿到 `compute_pod_name`，硬删 pod）

### Bug 20: 飞书推送只有文件路径，用户要 SSH 进 Railway 才能看到诊断
- **症状**：用户截图 feishu DM："`[SRE] 冷启动告警已诊断，session=... 请查看 /data/hermes/data/<sid>/conclusion.md`"——问"这就是 agent 的建议吗？"
- **根因**：`run_cold_start_watcher` 的 DM 内容只拼了 session_id + 文件路径，没读 conclusion.md 内容
- **教训**：推送是 SRE agent 的用户界面，不能让操作者再跳一层去看诊断；DM 正文就应该是 root cause + 建议
- **修复**：`main.py` 读 manifest.trigger + read_conclusion(sid)，拼成："🔥 [SRE] <alert> — <tenant>/<db>\nsession=...\n────\n<conclusion.md body, 4000 字符内>\n────\n路径: ..."，超过 4000 字符截断（飞书消息上限 ~30KB，留余量）
- **Commit**: `6dbd0d67`

---

### Bug 15-20 的共同教训

这 6 个 bug 加起来花了一个下午定位。共同点：**watcher 的业务逻辑在单测/集测里都是绿的，真实环境里一路踩雷**。防御措施：
1. **每个依赖组件做 health probe**——fluentbit、pageserver、safekeeper、lakeon-api 埋点点位、simulate 脚本的清理路径
2. **飞书 DM 正文 = 最终用户界面**——内容必须自带，不能让人跳两层
3. **生产部署工具链统一**——helm values + rollout 不能混用

