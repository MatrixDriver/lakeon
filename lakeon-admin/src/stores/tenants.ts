import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminApi } from '../api/admin'

export const useTenantStore = defineStore('tenants', () => {
  const tenantMap = ref<Record<string, string>>({})
  const loaded = ref(false)

  async function load() {
    if (loaded.value) return
    try {
      const res = await adminApi.listTenants()
      const list = res.data as Array<{ id: string; name: string }>
      const map: Record<string, string> = {}
      for (const t of list) {
        map[t.id] = t.name
      }
      tenantMap.value = map
      loaded.value = true
    } catch (e) {
      console.error('Failed to load tenants for name resolution', e)
    }
  }

  function name(tenantId: string): string {
    return tenantMap.value[tenantId] || tenantId
  }

  function refresh() {
    loaded.value = false
    return load()
  }

  return { tenantMap, loaded, load, name, refresh }
})
