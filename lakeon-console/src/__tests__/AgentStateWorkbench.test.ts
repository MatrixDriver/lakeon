import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AgentStateWorkbench from '../views/agent-state/AgentStateWorkbench.vue'
import AgentTaskRunDetailView from '../views/agent-state/AgentTaskRunDetailView.vue'
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
  createdAt: '2026-06-05T07:00:00Z',
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

function createAgentStateRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/agent-state', component: AgentStateWorkbench },
      { path: '/agent-state/runs/:taskRunId', component: AgentTaskRunDetailView },
    ],
  })
}

async function mountWorkbench() {
  const router = createAgentStateRouter()
  router.push('/agent-state')
  await router.isReady()

  const wrapper = mount(AgentStateWorkbench, {
    global: {
      plugins: [router],
    },
  })
  await flushPromises()
  return { wrapper, router }
}

async function mountTaskDetail(path = '/agent-state/runs/task_1') {
  const router = createAgentStateRouter()
  router.push(path)
  await router.isReady()

  const wrapper = mount(AgentTaskRunDetailView, {
    global: {
      plugins: [router],
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('AgentStateWorkbench task list', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(agentStateApi.listApps).mockResolvedValue([])
    vi.mocked(agentStateApi.listTaskRuns).mockResolvedValue([task])
    vi.mocked(agentStateApi.getTaskRun).mockResolvedValue(detail)
  })

  it('shows task runs without loading the selected task detail', async () => {
    const { wrapper } = await mountWorkbench()

    expect(wrapper.text()).toContain('任务运行')
    expect(wrapper.text()).toContain('Verify quicksort')
    expect(wrapper.text()).toContain('PaperBench Agent')
    expect(wrapper.text()).toContain('开始')
    expect(agentStateApi.getTaskRun).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(false)
  })

  it('navigates to the task detail page when a task is selected', async () => {
    const { wrapper, router } = await mountWorkbench()

    await wrapper.find('.task-row').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/agent-state/runs/task_1')
  })
})

describe('AgentTaskRunDetailView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(agentStateApi.listApps).mockResolvedValue([])
    vi.mocked(agentStateApi.listTaskRuns).mockResolvedValue([task])
    vi.mocked(agentStateApi.getTaskRun).mockResolvedValue(detail)
  })

  it('loads a task run from the route and opens the branch graph tab from the hash', async () => {
    const { wrapper } = await mountTaskDetail('/agent-state/runs/task_1#branches')

    expect(agentStateApi.getTaskRun).toHaveBeenCalledWith('task_1')
    expect(wrapper.find('[data-test="branch-graph-canvas"]').exists()).toBe(true)
    expect(wrapper.find('.detail-tab.active').text()).toBe('分支图')
    expect(wrapper.text()).toContain('Verify quicksort')
  })
})
