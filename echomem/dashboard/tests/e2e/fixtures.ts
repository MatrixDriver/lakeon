import { test as base, expect } from '@playwright/test'
import { spawn, ChildProcess } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

interface Fixtures {
  daemonReady: void
}

async function waitFor(url: string, ms = 30_000) {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    try {
      const r = await fetch(url)
      if (r.ok) return
    } catch { /* ignore */ }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error(`timeout waiting for ${url}`)
}

export const test = base.extend<Fixtures>({
  daemonReady: [async ({}, use) => {
    const dir = mkdtempSync(join(tmpdir(), 'echomem-e2e-'))
    const proc: ChildProcess = spawn(
      'echomem', ['start', '--data-dir', dir],
      { env: { ...process.env, ECHOMEM_DATA_DIR: dir }, stdio: 'pipe' }
    )
    await waitFor('http://127.0.0.1:8473/health')
    await use()
    proc.kill('SIGTERM')
    await new Promise((r) => proc.once('exit', r))
  }, { auto: true }],
})

export { expect }
