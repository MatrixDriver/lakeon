import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AgentRunBranchGraph from '../components/agent-state/AgentRunBranchGraph.vue'
import type { TaskRunDetail } from '../api/agent-state'

const detail: TaskRunDetail = {
  task: {
    id: 'task_1',
    goal: 'Verify quicksort',
    harnessId: 'paperbench',
    status: 'completed',
    currentStageId: 'report_gate',
    workspaceId: 'aws_1',
    branchCount: 2,
    evidenceCount: 1,
  },
  workspace: {
    id: 'aws_1',
    taskRunId: 'task_1',
    rootBranchId: 'branch_main',
    createdAt: '2026-06-05T07:00:00Z',
  },
  stages: [
    { id: 'stage_parse', taskRunId: 'task_1', stageId: 'paper_parse', status: 'done', branchId: 'branch_main' },
    { id: 'stage_claim', taskRunId: 'task_1', stageId: 'claim_extract', status: 'done', branchId: 'branch_claim' },
  ],
  branches: [
    {
      id: 'branch_main',
      workspaceId: 'aws_1',
      parentBranchId: null,
      stageRunId: 'stage_parse',
      name: 'main',
      hypothesis: 'Initial parsed paper workspace',
      status: 'active',
      createdAt: '2026-06-05T07:01:00Z',
    },
    {
      id: 'branch_claim',
      workspaceId: 'aws_1',
      parentBranchId: 'branch_main',
      stageRunId: 'stage_claim',
      name: 'claim branch',
      hypothesis: 'Extract quicksort sorted and multiset claim',
      status: 'verified',
      createdAt: '2026-06-05T07:02:00Z',
    },
  ],
  commits: [
    {
      id: 'commit_1',
      taskRunId: 'task_1',
      stageRunId: 'stage_claim',
      branchId: 'branch_claim',
      summary: 'Generated TypeScript verifier',
      createdAt: '2026-06-05T07:03:00Z',
    },
  ],
  artifacts: [
    {
      id: 'artifact_1',
      taskRunId: 'task_1',
      stageRunId: 'stage_claim',
      branchId: 'branch_claim',
      kind: 'verifier',
      createdAt: '2026-06-05T07:04:00Z',
    },
  ],
  evidencePackets: [
    {
      id: 'evidence_1',
      taskRunId: 'task_1',
      branchId: 'branch_claim',
      claim: 'Quicksort returns sorted output and preserves the input multiset.',
      status: 'supported',
      evidenceRefs: ['artifact_1'],
      createdAt: '2026-06-05T07:05:00Z',
    },
  ],
  auditEvents: [
    {
      id: 'audit_1',
      taskRunId: 'task_1',
      branchId: 'branch_claim',
      action: 'report_gate',
      result: 'allowed',
      reason: 'Evidence packet passed gate.',
      createdAt: '2026-06-05T07:06:00Z',
    },
  ],
}

function mountGraph(overrides: Partial<TaskRunDetail> = {}) {
  return mount(AgentRunBranchGraph, {
    props: {
      detail: { ...detail, ...overrides },
      stageLabel: (value?: string | null) => ({ paper_parse: '论文解析', claim_extract: '主张抽取' }[value || ''] || value || '待处理'),
      evidenceStatusLabel: (value?: string | null) => ({ supported: '已支持' }[value || ''] || value || '--'),
      shortTime: (value?: string | null) => value?.slice(11, 16) || '--',
    },
  })
}

describe('AgentRunBranchGraph', () => {
  it('renders a left-to-right branch graph with a bottom inspector', () => {
    const wrapper = mountGraph()

    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="branch-graph-inspector"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('工作区分支图')
    expect(wrapper.text()).toContain('branch_main')
    expect(wrapper.text()).toContain('claim branch')
    expect(wrapper.text()).toContain('节点上下文')
  })

  it('updates the inspector when selecting a branch node', async () => {
    const wrapper = mountGraph()

    await wrapper.find('[data-test="branch-node-branch_claim"]').trigger('click')

    expect(wrapper.find('[data-test="branch-graph-inspector"]').text()).toContain('branch_claim')
    expect(wrapper.find('[data-test="branch-graph-inspector"]').text()).toContain('Generated TypeScript verifier')
    expect(wrapper.find('[data-test="branch-graph-inspector"]').text()).toContain('Quicksort returns sorted output')
    expect(wrapper.find('[data-test="branch-graph-inspector"]').text()).toContain('Evidence packet passed gate')
  })

  it('shows an empty state when the run has no branches', () => {
    const wrapper = mountGraph({ branches: [], commits: [], artifacts: [], evidencePackets: [], auditEvents: [] })

    expect(wrapper.text()).toContain('还没有工作区分支')
    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(false)
  })
})
