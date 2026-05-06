import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach } from 'vitest'
import { useUiStore } from '@/stores/ui'

describe('useUiStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts with no banner and dialogs closed', () => {
    const ui = useUiStore()
    expect(ui.banner).toBeNull()
    expect(ui.quickIngestOpen).toBe(false)
    expect(ui.lineageOpen).toBe(false)
  })

  it('setBanner / clearBanner', () => {
    const ui = useUiStore()
    ui.setBanner({ kind: 'error', text: 'down' })
    expect(ui.banner?.text).toBe('down')
    ui.clearBanner()
    expect(ui.banner).toBeNull()
  })

  it('toggleQuickIngest opens and closes', () => {
    const ui = useUiStore()
    ui.toggleQuickIngest(true); expect(ui.quickIngestOpen).toBe(true)
    ui.toggleQuickIngest(false); expect(ui.quickIngestOpen).toBe(false)
  })
})
