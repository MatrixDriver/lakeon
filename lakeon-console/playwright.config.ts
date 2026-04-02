import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 1,
  workers: 1,
  reporter: [['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    storageState: 'e2e/.auth/state.json',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    actionTimeout: 15000,
  },
  globalSetup: './e2e/fixtures/global-setup.ts',
  globalTeardown: './e2e/fixtures/global-teardown.ts',
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
})
