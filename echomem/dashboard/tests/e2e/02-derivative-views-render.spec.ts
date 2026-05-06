import { test, expect } from './fixtures'
import { seedFixture } from './fixtures-data'

test.beforeEach(async () => { await seedFixture() })

test('memory page shows seeded rows', async ({ page }) => {
  await page.goto('/#/memory')
  await expect(page.locator('table tbody tr')).toHaveCount(8, { timeout: 10_000 })
})

test('cognition shells render their empty-state when worker idle', async ({ page }) => {
  await page.goto('/#/cognition/timeline')
  const empty = page.getByText('AI worker 待机中')
  const cards = page.locator('.timeline .row')
  await expect(empty.or(cards.first())).toBeVisible({ timeout: 10_000 })
})

test('graph view shows the empty-state when no seed', async ({ page }) => {
  await page.goto('/#/cognition/graph')
  await expect(page.getByText('输入种子节点开始查询')).toBeVisible()
})
