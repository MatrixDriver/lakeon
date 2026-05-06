import { test, expect } from './fixtures'

test('empty → ingest → first cognition appears with lineage', async ({ page }) => {
  test.skip(!process.env.ECHOMEM_E2E_REQUIRE_OLLAMA,
            'requires real ollama; gated by ECHOMEM_E2E_REQUIRE_OLLAMA=1')

  await page.goto('/')
  await expect(page.getByText('echomem 是空的')).toBeVisible()

  await page.getByRole('button', { name: /\+ Quick ingest/ }).click()
  await page.locator('textarea').fill('今天我决定用 Hub-and-Spoke 布局')
  await page.getByRole('button', { name: 'Ingest' }).click()

  await expect.poll(async () => {
    return await page.locator('[data-testid="tile-mem"], .tile').first().textContent()
  }, { timeout: 90_000 }).not.toContain('0')

  await expect(page.locator('h2').first()).toBeVisible({ timeout: 120_000 })

  await page.getByRole('button', { name: /查看完整来源/ }).click()
  await expect(page.locator('aside.drawer')).toBeVisible()
  await expect(page.locator('aside.drawer .col')).toHaveCount(3)
})
