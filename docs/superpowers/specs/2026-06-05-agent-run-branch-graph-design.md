# Agent Run Branch Graph Design

## Goal

在 DBay Console 的智能体数据平台里，用户打开某次 Agent Run 后，可以用横向分支图查看工作区分支的演化，并在下方查看选中节点的 Evidence、产物和治理审计摘要。

## UX

分支图放在现有任务详情里的「工作区分支」区块，不新增一级菜单。图区域使用上方全宽画布，保持从左到右的分支演化可读；选中节点详情放在下方三列检查器，分别展示节点上下文、Evidence 摘要、治理结果。节点点击后更新下方详情。

## Data

第一版复用现有 `/agent-state/task-runs/{id}` 返回的 `workspace`、`branches`、`stages`、`commits`、`artifacts`、`evidencePackets`、`auditEvents`，不新增后端 API。分支节点按 `parentBranchId` 构造层级；没有父分支的节点挂在 workspace root 下。

## Implementation

新增独立 Vue 组件 `AgentRunBranchGraph.vue`，由 `AgentStateWorkbench.vue` 传入 `TaskRunDetail` 和格式化函数。组件内部维护选中节点状态，计算节点位置、边、分支关联的产物/Evidence/审计摘要。

## Testing

新增组件单测覆盖：渲染横向画布与下方详情；点击分支节点后更新选中详情；空分支时展示空状态。
