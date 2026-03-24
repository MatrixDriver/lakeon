import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import BlogPostView from '../views/blog/BlogPostView.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/blog/:slug', component: BlogPostView }],
})

describe('BlogPostView', () => {
  it('shows 404 message for unknown slug', async () => {
    router.push('/blog/nonexistent-slug')
    await router.isReady()
    const wrapper = mount(BlogPostView, { global: { plugins: [router] } })
    expect(wrapper.text()).toMatch(/404|找不到|not found/i)
  })

  it('does not render script tags (XSS prevention)', async () => {
    router.push('/blog/memory-architecture')
    await router.isReady()
    const wrapper = mount(BlogPostView, { global: { plugins: [router] } })
    // DOMPurify removes <script> tags — rendered HTML should not contain them
    expect(wrapper.html()).not.toContain('<script')
  })
})
