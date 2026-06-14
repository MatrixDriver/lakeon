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
          { path: 'agent-state', component: { template: '<div>agent</div>' } },
          { path: 'lbfs', component: { template: '<div>lbfs</div>' } },
          { path: 'knowledge', component: { template: '<div>knowledge</div>' } },
          { path: 'memory', component: { template: '<div>memory</div>' } },
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

  it('promotes LakebaseFS to a top-level workspace above memory', async () => {
    const router = makeRouter('/lbfs')
    await router.push('/lbfs')
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
    expect(railLabels).toEqual(['数据库', 'Agent', '知识库', 'LakebaseFS', '记忆库', '运维'])
    expect(railLabels.indexOf('LakebaseFS')).toBeLessThan(railLabels.indexOf('记忆库'))
    expect(wrapper.find('.side-title').text()).toContain('LakebaseFS')
    expect(wrapper.find('.sidebar-nav').text()).toContain('文件系统')
  })
})
