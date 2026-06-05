# Agent Run Branch Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a branch graph view for a selected Agent Run in DBay Console.

**Architecture:** Keep backend unchanged for phase one. Add one focused Vue component that derives a graph from existing task-run detail data, then embed it in the current Agent State workbench.

**Tech Stack:** Vue 3, TypeScript, Vitest, @vue/test-utils.

---

### Task 1: Component Test

**Files:**
- Create: `lakeon-console/src/__tests__/AgentRunBranchGraph.test.ts`

- [ ] Write tests for graph rendering, node selection, and empty state.
- [ ] Run `npm test -- AgentRunBranchGraph.test.ts` and confirm the tests fail before implementation.

### Task 2: Branch Graph Component

**Files:**
- Create: `lakeon-console/src/components/agent-state/AgentRunBranchGraph.vue`

- [ ] Implement derived graph nodes and edges from `TaskRunDetail.branches`.
- [ ] Render the graph as top canvas and selected-node inspector as bottom panel.
- [ ] Keep node text clipped and stable in fixed-size nodes.

### Task 3: Workbench Integration

**Files:**
- Modify: `lakeon-console/src/views/agent-state/AgentStateWorkbench.vue`

- [ ] Replace the old inline `branch-dag` strip with `AgentRunBranchGraph`.
- [ ] Remove obsolete branch strip styles.

### Task 4: Verification

**Files:**
- Test: `lakeon-console/src/__tests__/AgentRunBranchGraph.test.ts`

- [ ] Run `npm test -- AgentRunBranchGraph.test.ts`.
- [ ] Run `npm run build`.
