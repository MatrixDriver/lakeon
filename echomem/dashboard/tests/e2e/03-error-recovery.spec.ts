import { test as base, expect } from '@playwright/test'

const test = base

test('shows banner when daemon offline, recovers when banner retry succeeds', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('.banner.error')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText(/无法连接 echomem daemon/)).toBeVisible()

  const { spawn } = await import('node:child_process')
  const { mkdtempSync } = await import('node:fs')
  const { tmpdir } = await import('node:os')
  const { join } = await import('node:path')
  const dir = mkdtempSync(join(tmpdir(), 'echomem-e2e-recover-'))
  const proc = spawn('echomem', ['start', '--data-dir', dir], { stdio: 'pipe' })
  await new Promise((r) => setTimeout(r, 4_000))

  await page.getByRole('button', { name: /重试/ }).click()
  await expect(page.locator('.banner.error')).toBeHidden({ timeout: 15_000 })

  proc.kill('SIGTERM')
})
