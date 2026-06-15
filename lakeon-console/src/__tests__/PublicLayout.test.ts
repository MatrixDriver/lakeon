import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import PublicLayout from '../layouts/PublicLayout.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div>home</div>' } },
    { path: '/product', component: { template: '<div>product</div>' } },
    { path: '/docs', component: { template: '<div>docs</div>' } },
    { path: '/login', component: { template: '<div>login</div>' } },
    { path: '/dashboard', component: { template: '<div>dashboard</div>' } },
  ],
})

describe('PublicLayout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders nav brand', async () => {
    const wrapper = mount(PublicLayout, {
      global: {
        plugins: [router],
        stubs: { MobileNav: true },
      },
    })
    await router.isReady()
    expect(wrapper.text()).toContain('DBay')
  })

  it('renders the Harbor Editorial nav items', async () => {
    const wrapper = mount(PublicLayout, {
      global: {
        plugins: [router],
        stubs: { MobileNav: true },
      },
    })
    await router.isReady()
    const text = wrapper.text()
    // Nav: Products, Docs, Sign in, Get started
    expect(text).toMatch(/方案|Solution/)
    expect(text).toMatch(/文档|Docs/)
    expect(text).toMatch(/登录|Sign in/)
    expect(text).toMatch(/开始使用|Get started/)
  })
})
