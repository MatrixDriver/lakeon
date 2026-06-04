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
  })

  test('switches from database console to agent workbench', async ({ page }) => {
    await page.goto('/dashboard')

    await page.getByRole('link', { name: 'Agent 工作台' }).click()

    await expect(page).toHaveURL(/\/agent-state$/)
    await expect(page.locator('h1.page-title')).toHaveText('Agent 工作台')
    await expect(page.getByRole('button', { name: '全部任务' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'PaperBench' })).toBeVisible()
    await expect(page.locator('.apps-panel').getByText('论文复现实验助手')).toBeVisible()
    await expect(page.locator('.apps-panel').getByText('数据发布检查助手')).toBeVisible()
    await expect(page.locator('.task-panel').getByText('paperbench-toy-claim')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Evidence Packet' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Policy & Audit' })).toBeVisible()
  })
})
