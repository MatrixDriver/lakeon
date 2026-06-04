import { defineConfig } from '@playwright/test'

process.env.NO_PROXY = process.env.NO_PROXY
  ? `${process.env.NO_PROXY},127.0.0.1,localhost,::1`
  : '127.0.0.1,localhost,::1'
process.env.no_proxy = process.env.NO_PROXY

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 1,
  workers: 1,
  reporter: [['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    storageState: 'e2e/.auth/state.json',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    actionTimeout: 15000,
  },
  globalSetup: './e2e/fixtures/global-setup.ts',
  globalTeardown: './e2e/fixtures/global-teardown.ts',
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
    env: {
      NO_PROXY: process.env.NO_PROXY,
      no_proxy: process.env.no_proxy,
    },
  },
})
