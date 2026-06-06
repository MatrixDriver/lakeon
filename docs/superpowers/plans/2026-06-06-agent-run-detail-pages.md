# Agent Run Detail Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the agent-state console flow into a task list page and a dedicated task run detail page.

**Architecture:** Keep the existing agent-state API unchanged. Convert `/agent-state` into a list-only page and add `/agent-state/runs/:taskRunId` for details, reusing the existing branch graph component.

**Tech Stack:** Vue 3, Vue Router, TypeScript, Vitest, @vue/test-utils.

---

### Task 1: Routing and Tests

**Files:**
- Modify: `lakeon-console/src/router/index.ts`
- Modify: `lakeon-console/src/__tests__/AgentStateWorkbench.test.ts`

- [x] Add `/agent-state/runs/:taskRunId`.
- [x] Update tests so clicking a task navigates to the detail route.
- [x] Add detail tests for branch hash/tab rendering.

### Task 2: List Page

**Files:**
- Modify: `lakeon-console/src/views/agent-state/AgentStateWorkbench.vue`

- [x] Remove inline detail loading from the workbench.
- [x] Render KPI, app filters, app panel, and full-width task list.
- [x] Make each task row navigate to `/agent-state/runs/:taskRunId`.

### Task 3: Detail Page

**Files:**
- Create: `lakeon-console/src/views/agent-state/AgentTaskRunDetailView.vue`

- [x] Fetch the selected run by route param.
- [x] Render summary, tabs, branch graph, evidence, audit, and outputs.
- [x] Add a back link to `/agent-state`.

### Task 4: Navigation and Verification

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`

- [x] Point task run navigation to `/agent-state`.
- [x] Remove global hash links that only work inside a selected run.
- [x] Run focused tests and production build.
