import { test, expect } from '@playwright/test'

const database = {
  id: 'db_cdf_1',
  name: 'orders-prod',
  status: 'RUNNING',
  connection_uri: 'postgres://example',
  compute_size: '1cu',
  suspend_timeout: '2m',
  storage_limit_gb: 10,
  storage_used_gb: 0.1,
  active_connections: 1,
  neon_timeline_id: 'tl_1',
  created_at: '2026-06-25T00:00:00Z',
  branches: [
    {
      id: 'br_main',
      name: 'main',
      is_default: true,
      status: 'ACTIVE',
      compute_status: 'RUNNING',
    },
  ],
}

function stream(status = 'RUNNING', exportStatus = 'NOT_MATERIALIZED', overrides = {}) {
  return {
    id: 'cdf_1',
    database_id: 'db_cdf_1',
    branch_id: 'br_main',
    source_schema: 'public',
    source_table: 'orders',
    target_namespace: 'public',
    target_table: 'orders_cdf',
    mode: 'APPEND_CHANGELOG',
    status,
    backfill_status: 'SUCCEEDED',
    backfill_lsn: '0/1000',
    last_commit_lsn: '0/2000',
    last_snapshot_id: 42,
    export_status: exportStatus,
    observed_lag_ms: 850,
    last_error: null,
    readable: true,
    ...overrides,
  }
}

test.describe('CDF console page', () => {
  test('lists, creates, controls, and exports CDF streams', async ({ page }) => {
    let streams = [
      stream(),
      stream('FAILED', 'FAILED', {
        id: 'cdf_failed',
        source_table: 'shipments',
        target_table: 'shipments_cdf',
        last_error: 'failed to commit CDF batch: writer failed',
        observed_lag_ms: 2300,
      }),
    ]

    await page.route('**/api/v1/databases', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ json: [database] })
        return
      }
      await route.fallback()
    })

    await page.route('**/api/v1/databases/db_cdf_1/cdf-streams', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await route.fulfill({ json: streams })
        return
      }
      if (method === 'POST') {
        streams = [
          ...streams,
          {
            ...stream('PAUSED'),
            id: 'cdf_2',
            source_table: 'payments',
            target_table: 'payments_cdf',
            last_snapshot_id: null,
            export_status: 'NOT_MATERIALIZED',
          },
        ]
        await route.fulfill({ json: streams[1] })
        return
      }
      await route.fallback()
    })

    await page.route('**/api/v1/databases/db_cdf_1/cdf-streams/cdf_1/pause', async (route) => {
      streams = [stream('PAUSED')]
      await route.fulfill({ json: streams[0] })
    })

    await page.route('**/api/v1/databases/db_cdf_1/cdf-streams/cdf_1/resume', async (route) => {
      streams = [stream('RUNNING')]
      await route.fulfill({ json: streams[0] })
    })

    await page.route('**/api/v1/databases/db_cdf_1/cdf-streams/cdf_1/export', async (route) => {
      if (route.request().method() === 'POST') {
        streams = [stream('RUNNING', 'MATERIALIZED')]
        await route.fulfill({ json: { status: 'MATERIALIZED', metadata_location: 'obs://lake/metadata.json' } })
        return
      }
      await route.fulfill({ json: { status: 'MATERIALIZED', metadata_location: 'obs://lake/metadata.json' } })
    })

    await page.goto('/cdf')

    await expect(page.getByRole('heading', { name: 'CDF Streams' })).toBeVisible()
    await expect(page.getByLabel('数据库')).toHaveValue('db_cdf_1')
    await expect(page.getByText('public.orders', { exact: true })).toBeVisible()
    await expect(page.getByText('public.orders_cdf', { exact: true })).toBeVisible()
    const ordersRow = page.getByRole('row', { name: /public\.orders/ })
    await expect(ordersRow.getByText('0/2000')).toBeVisible()
    await expect(ordersRow.getByText('850 ms')).toBeVisible()
    await expect(page.getByRole('row', { name: /public\.shipments/ }).getByText('failed to commit CDF batch: writer failed')).toBeVisible()

    await page.getByLabel('源 Table').fill('payments')
    await expect(page.getByLabel('目标 Table')).toHaveValue('payments_cdf')
    await page.getByRole('button', { name: '创建' }).click()
    await expect(page.getByText('public.payments', { exact: true })).toBeVisible()

    await page.getByRole('row', { name: /public\.orders/ }).getByRole('button', { name: 'Pause' }).click()
    await expect(page.getByRole('row', { name: /public\.orders/ }).getByText('PAUSED')).toBeVisible()

    await page.getByRole('row', { name: /public\.orders/ }).getByRole('button', { name: 'Resume' }).click()
    await expect(page.getByRole('row', { name: /public\.orders/ }).getByText('RUNNING')).toBeVisible()

    await page.getByRole('row', { name: /public\.orders/ }).getByRole('button', { name: 'Export' }).click()
    await expect(page.getByRole('row', { name: /public\.orders/ }).getByText('MATERIALIZED')).toBeVisible()
  })
})
