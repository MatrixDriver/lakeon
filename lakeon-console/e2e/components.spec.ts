import { test, expect } from '@playwright/test'

test.describe('Component library page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/datalake/components')
    await page.waitForLoadState('networkidle')
  })

  test('renders with "组件库" title', async ({ page }) => {
    await expect(page.locator('h1.page-title')).toHaveText('组件库')
  })

  test('displays at least 12 preset components', async ({ page }) => {
    const cards = page.locator('.comp-card')
    await expect(cards.first()).toBeVisible({ timeout: 10000 })
    const count = await cards.count()
    expect(count).toBeGreaterThanOrEqual(12)
  })

  test('filter by TEXT data type shows only TEXT components', async ({ page }) => {
    await expect(page.locator('.comp-card').first()).toBeVisible({ timeout: 10000 })

    // Click the "文本" pill in the data type filter
    const dataTypeSection = page.locator('.filter-section').first()
    await dataTypeSection.getByRole('button', { name: '文本' }).click()

    // All visible cards should have the TEXT data type tag
    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
    for (let i = 0; i < count; i++) {
      await expect(cards.nth(i).locator('.data-type-tag')).toHaveText('文本')
    }
  })

  test('filter by VIDEO data type shows only VIDEO components', async ({ page }) => {
    await expect(page.locator('.comp-card').first()).toBeVisible({ timeout: 10000 })

    const dataTypeSection = page.locator('.filter-section').first()
    await dataTypeSection.getByRole('button', { name: '视频' }).click()

    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
    for (let i = 0; i < count; i++) {
      await expect(cards.nth(i).locator('.data-type-tag')).toHaveText('视频')
    }
  })

  test('filter by "清洗" category shows CLEAN components', async ({ page }) => {
    await expect(page.locator('.comp-card').first()).toBeVisible({ timeout: 10000 })

    // The category filter is the second .filter-section
    const categorySection = page.locator('.filter-section').nth(1)
    await categorySection.getByRole('button', { name: '清洗' }).click()

    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
    // Each card should have a category tag "清洗"
    for (let i = 0; i < count; i++) {
      await expect(cards.nth(i).locator('.cat-tag')).toHaveText('清洗')
    }
  })

  test('search "清洗" filters results', async ({ page }) => {
    await expect(page.locator('.comp-card').first()).toBeVisible({ timeout: 10000 })
    const totalBefore = await page.locator('.comp-card').count()

    await page.locator('.filter-search').fill('清洗')

    // Wait for filtering to take effect
    await page.waitForTimeout(300)

    const cards = page.locator('.comp-card')
    const countAfter = await cards.count()
    // Search should reduce results (or show matching ones)
    expect(countAfter).toBeGreaterThan(0)
    expect(countAfter).toBeLessThanOrEqual(totalBefore)
  })

  test('click component card opens detail panel', async ({ page }) => {
    await expect(page.locator('.comp-card').first()).toBeVisible({ timeout: 10000 })

    // Click the first component card
    await page.locator('.comp-card').first().click()

    // The detail panel should appear
    await expect(page.locator('.detail-panel')).toBeVisible({ timeout: 10000 })
    // Panel should have a title
    await expect(page.locator('.panel-title')).toBeVisible()
    // Panel should have "基本信息" section
    await expect(page.getByText('基本信息')).toBeVisible()

    // Close the panel
    await page.locator('.panel-close').click()
    await expect(page.locator('.detail-panel')).toBeHidden()
  })
})
