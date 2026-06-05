import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AgentStateWorkbench from '../views/agent-state/AgentStateWorkbench.vue'
import { agentStateApi } from '../api/agent-state'
import type { TaskRunDetail, TaskRunSummary } from '../api/agent-state'

vi.mock('../api/agent-state', () => ({
  agentStateApi: {
    listApps: vi.fn(),
    listTaskRuns: vi.fn(),
    getTaskRun: vi.fn(),
  },
}))

const task: TaskRunSummary = {
  id: 'task_1',
  goal: 'Verify quicksort',
  harnessId: 'paperbench',
  status: 'completed',
  currentStageId: 'report_gate',
  workspaceId: 'workspace_1',
  branchCount: 2,
  evidenceCount: 1,
  latestBranchId: 'branch_child',
  latestEvidencePacketId: 'evidence_1',
  latestAuditResult: 'allowed',
}

const detail: TaskRunDetail = {
  task,
  workspace: { id: 'workspace_1', taskRunId: 'task_1', rootBranchId: 'branch_root' },
  stages: [
    { id: 'stage_root', taskRunId: 'task_1', stageId: 'paper_parse', status: 'done', branchId: 'branch_root' },
    { id: 'stage_child', taskRunId: 'task_1', stageId: 'report_gate', status: 'done', branchId: 'branch_child' },
  ],
  branches: [
    { id: 'branch_root', workspaceId: 'workspace_1', parentBranchId: null, stageRunId: 'stage_root', name: 'root', status: 'active' },
    { id: 'branch_child', workspaceId: 'workspace_1', parentBranchId: 'branch_root', stageRunId: 'stage_child', name: 'branch', status: 'verified' },
  ],
  commits: [],
  artifacts: [],
  evidencePackets: [
    {
      id: 'evidence_1',
      taskRunId: 'task_1',
      branchId: 'branch_child',
      claim: 'Branch evidence claim',
      status: 'pending',
      evidenceRefs: [],
    },
  ],
  auditEvents: [
    { id: 'audit_1', taskRunId: 'task_1', branchId: 'branch_child', action: 'report_gate', result: 'allowed' },
  ],
}

async function mountWorkbench(hash = '') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/agent-state', component: AgentStateWorkbench }],
  })
  router.push(`/agent-state${hash}`)
  await router.isReady()

  const wrapper = mount(AgentStateWorkbench, {
    global: {
      plugins: [router],
    },
  })
  await flushPromises()
  return wrapper
}

describe('AgentStateWorkbench detail tabs', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(agentStateApi.listApps).mockResolvedValue([])
    vi.mocked(agentStateApi.listTaskRuns).mockResolvedValue([task])
    vi.mocked(agentStateApi.getTaskRun).mockResolvedValue(detail)
  })

  it('opens the branch graph workspace when the route hash is #branches', async () => {
    const wrapper = await mountWorkbench('#branches')

    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(true)
    expect(wrapper.find('.detail-tab.active').text()).toBe('分支图')
    expect(wrapper.find('#evidence').exists()).toBe(false)
    expect(wrapper.find('#audit').exists()).toBe(false)
  })

  it('switches from overview to evidence without showing the branch graph', async () => {
    const wrapper = await mountWorkbench()

    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(false)
    await wrapper.findAll('.detail-tab').find((button) => button.text() === '证据')?.trigger('click')
    await flushPromises()

    expect(wrapper.find('#evidence').exists()).toBe(true)
    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(false)
  })
})
