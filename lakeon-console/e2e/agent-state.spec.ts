import { test, expect } from '@playwright/test'

test.describe('Agent state workbench', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/agent-state/apps', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'app_paper',
            key: 'paperbench',
            display_name: '论文复现实验助手',
            displayName: '论文复现实验助手',
            type: 'benchmark',
            version: '0.1.0',
            status: 'active',
            stage_schema: ['paper_parse', 'claim_extract', 'experiment_run', 'evidence_pack', 'report_gate'],
            stageSchema: ['paper_parse', 'claim_extract', 'experiment_run', 'evidence_pack', 'report_gate'],
          },
          {
            id: 'app_data',
            key: 'data',
            display_name: '数据发布检查助手',
            displayName: '数据发布检查助手',
            type: 'data',
            version: '0.1.0',
            status: 'active',
            stage_schema: ['schema_resolve', 'context_pack', 'sql_validate', 'policy_check', 'publish_gate'],
            stageSchema: ['schema_resolve', 'context_pack', 'sql_validate', 'policy_check', 'publish_gate'],
          },
        ]),
      })
    })

    await page.route('**/api/v1/agent-state/task-runs', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'task_4771e5fb',
            goal: 'verify quicksort partition implementation matches the paper',
            harness_id: 'paperbench',
            status: 'running',
            current_stage_id: 'evidence_pack',
            workspace_id: 'ws_259d6501',
            branch_count: 1,
            evidence_count: 1,
            latest_branch_id: 'awb_99d05c8b',
            latest_evidence_packet_id: 'evidence_2f0781e1',
            latest_audit_result: 'allowed',
            created_at: '2026-06-04T08:30:00Z',
          },
        ]),
      })
    })

    await page.route('**/api/v1/agent-state/task-runs/task_4771e5fb', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          task: {
            id: 'task_4771e5fb',
            goal: 'verify quicksort partition implementation matches the paper',
            harness_id: 'paperbench',
            status: 'running',
            current_stage_id: 'evidence_pack',
            workspace_id: 'ws_259d6501',
            branch_count: 1,
            evidence_count: 1,
            latest_branch_id: 'awb_99d05c8b',
            latest_evidence_packet_id: 'evidence_2f0781e1',
            latest_audit_result: 'allowed',
            created_at: '2026-06-04T08:30:00Z',
          },
          stages: [
            { id: 'stage_parse', task_run_id: 'task_4771e5fb', stage_id: 'paper_parse', status: 'done', created_at: '2026-06-04T08:30:01Z' },
            { id: 'stage_claim', task_run_id: 'task_4771e5fb', stage_id: 'claim_extract', status: 'done', created_at: '2026-06-04T08:30:02Z' },
            { id: 'stage_pack', task_run_id: 'task_4771e5fb', stage_id: 'evidence_pack', status: 'running', branch_id: 'awb_99d05c8b', created_at: '2026-06-04T08:30:03Z' },
          ],
          workspace: { id: 'ws_259d6501', task_run_id: 'task_4771e5fb', root_branch_id: 'awb_99d05c8b', created_at: '2026-06-04T08:30:00Z' },
          branches: [
            { id: 'awb_99d05c8b', workspace_id: 'ws_259d6501', name: 'root', status: 'active', hypothesis: 'baseline verification', created_at: '2026-06-04T08:30:00Z' },
          ],
          commits: [],
          artifacts: [],
          evidence_packets: [
            {
              id: 'evidence_2f0781e1',
              task_run_id: 'task_4771e5fb',
              branch_id: 'awb_99d05c8b',
              claim: 'All 100 quicksort tests passed.',
              status: 'supported',
              evidence_refs: ['generated/paperbench-agent-check.ts', 'verification.log'],
              created_at: '2026-06-04T08:31:00Z',
            },
          ],
          audit_events: [
            { id: 'audit_076494be', task_run_id: 'task_4771e5fb', branch_id: 'awb_99d05c8b', action: 'report_gate', result: 'allowed', reason: 'supported', created_at: '2026-06-04T08:31:01Z' },
          ],
        }),
      })
    })
  })

  test('switches from database console to agent workbench', async ({ page }) => {
    await page.goto('/dashboard')

    await expect(page.locator('.workspace-rail')).toBeVisible()
    await expect(page.locator('.workspace-rail a')).toHaveText(['数据', '智能体', '知识', '记忆', '运维'])
    await expect(page.locator('.sidebar-nav .side-title')).toContainText('数据库工作台')

    await page.locator('.workspace-rail a[href="/agent-state"]').click()

    await expect(page).toHaveURL(/\/agent-state$/)
    await expect(page.locator('.sidebar-nav .side-title')).toContainText('智能体数据平台')
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '数据库' })).toHaveCount(0)
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '任务运行' })).toBeVisible()
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '任务概览' })).toBeVisible()
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '执行阶段' })).toBeVisible()
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '证据包' })).toBeVisible()
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '工作区分支' })).toBeVisible()
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '治理审计' })).toBeVisible()
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '运行输出' })).toBeVisible()
    await expect(page.locator('h1.page-title')).toHaveText('智能体工作台')
    await expect(page.getByRole('button', { name: '全部任务' })).toBeVisible()
    await expect(page.locator('.agent-tabs').getByRole('button', { name: 'PaperBench' })).toBeVisible()
    await expect(page.locator('.agent-tabs').getByRole('button', { name: '数据智能体' })).toBeVisible()
    await expect(page.locator('.apps-panel').getByText('论文复现实验助手')).toBeVisible()
    await expect(page.locator('.apps-panel').getByText('数据发布检查助手')).toBeVisible()
    await expect(page.locator('.task-panel').getByText('task_4771e5fb')).toBeVisible()
    await expect(page.locator('.task-detail-stack').getByText('All 100 quicksort tests passed.')).toBeVisible()
    await expect(page.locator('.branch-dag').getByText('root')).toBeVisible()
    await expect(page.getByRole('heading', { name: '任务概览' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '执行阶段' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '证据包' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '工作区分支' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '治理审计' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '运行输出' })).toBeVisible()

    await page.locator('.sidebar-nav').getByRole('link', { name: '证据包' }).click()
    await expect(page).toHaveURL(/\/agent-state#evidence$/)
    await page.locator('.sidebar-nav').getByRole('link', { name: '工作区分支' }).click()
    await expect(page).toHaveURL(/\/agent-state#branches$/)
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '工作区分支' })).toHaveClass(/active/)
    await page.locator('.sidebar-nav').getByRole('link', { name: '治理审计' }).click()
    await expect(page).toHaveURL(/\/agent-state#audit$/)
    await expect(page.locator('.sidebar-nav').getByRole('link', { name: '治理审计' })).toHaveClass(/active/)
    await page.waitForFunction(() => {
      const main = document.querySelector('.console-main')
      return main instanceof HTMLElement && main.scrollTop > 0
    })
  })
})
