import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import QuickIngestDialog from '@/components/QuickIngestDialog.vue'
import { useUiStore } from '@/stores/ui'

describe('QuickIngestDialog', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('hidden when ui.quickIngestOpen is false', () => {
    const w = mount(QuickIngestDialog)
    expect(w.find('.dialog').exists()).toBe(false)
  })

  it('visible when ui.quickIngestOpen is true', async () => {
    const ui = useUiStore()
    ui.toggleQuickIngest(true)
    const w = mount(QuickIngestDialog)
    expect(w.find('.dialog').exists()).toBe(true)
  })

  it('submits via ApiClient.post', async () => {
    const ui = useUiStore()
    ui.toggleQuickIngest(true)
    const post = vi.fn().mockResolvedValue({ id: 'x', agent_id: 'cli', created_at: 1 })
    const w = mount(QuickIngestDialog, {
      global: { provide: { apiClient: { post } } },
    })
    await w.find('textarea').setValue('hello')
    await w.find('form').trigger('submit')
    expect(post).toHaveBeenCalledWith('/memory/ingest', expect.objectContaining({ text: 'hello' }))
  })
})
