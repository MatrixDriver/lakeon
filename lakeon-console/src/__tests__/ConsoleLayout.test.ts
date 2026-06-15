import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ConsoleLayout from '../layouts/ConsoleLayout.vue'

function makeRouter(initialPath: string) {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: ConsoleLayout,
        children: [
          { path: 'dashboard', component: { template: '<div>dashboard</div>' } },
          { path: 'timetravel', component: { template: '<div>timetravel</div>' } },
          { path: 'sql', component: { template: '<div>sql</div>' } },
          { path: 'import', component: { template: '<div>import</div>' } },
          { path: 'lbfs', component: { template: '<div>lbfs</div>' } },
          { path: 'monitor', component: { template: '<div>ops</div>' } },
          { path: 'docs', component: { template: '<div>docs</div>' } },
        ],
      },
    ],
  })
}

describe('ConsoleLayout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('narrows the console rail to Lakebase data, FS, and operations', async () => {
    const router = makeRouter('/dashboard')
    await router.push('/dashboard')
    const wrapper = mount(ConsoleLayout, {
      global: {
        plugins: [router],
        stubs: { CommandPalette: true },
      },
    })
    await router.isReady()

    const railLabels = wrapper
      .find('.workspace-rail')
      .findAll('.rail-label')
      .map((node) => node.text())
    expect(railLabels).toEqual(['数据', 'FS', '运维'])
    expect(wrapper.find('.side-title').text()).toContain('数据')
    expect(wrapper.find('.sidebar-nav').text()).toContain('数据目录')
    expect(wrapper.find('.sidebar-nav').text()).toContain('数据库')
    expect(wrapper.find('.sidebar-nav').text()).not.toContain('数据湖')
  })
})
