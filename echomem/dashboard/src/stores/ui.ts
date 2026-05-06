import { defineStore } from 'pinia'
import { ref } from 'vue'

export type BannerKind = 'error' | 'warning' | 'info'
export interface Banner { kind: BannerKind; text: string; retry?: () => void }

export const useUiStore = defineStore('ui', () => {
  const banner = ref<Banner | null>(null)
  const quickIngestOpen = ref(false)
  const lineageOpen = ref(false)

  function setBanner(b: Banner) { banner.value = b }
  function clearBanner() { banner.value = null }
  function toggleQuickIngest(v: boolean) { quickIngestOpen.value = v }
  function toggleLineage(v: boolean) { lineageOpen.value = v }

  return { banner, quickIngestOpen, lineageOpen, setBanner, clearBanner, toggleQuickIngest, toggleLineage }
})
