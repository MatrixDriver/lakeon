import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BranchTreeView from '../components/BranchTreeView.vue'
import type { BranchTreeNode } from '../api/branch'

const mainNode: BranchTreeNode = {
  id: 'br_main',
  name: 'main',
  parent_branch_id: null,
  is_default: true,
  ancestor_lsn: null,
  last_record_lsn: '0/5000',
  current_logical_size_bytes: 10240,
  created_at: '2026-03-01T00:00:00Z',
}

const featureNode: BranchTreeNode = {
  id: 'br_feat',
  name: 'feature-branch',
  parent_branch_id: 'br_main',
  is_default: false,
  ancestor_lsn: '0/3000',
  last_record_lsn: '0/4000',
  current_logical_size_bytes: 5120,
  created_at: '2026-03-02T00:00:00Z',
}

describe('BranchTreeView', () => {
  it('renders SVG with correct number of nodes', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    const nodeGroups = wrapper.findAll('.tree-node')
    expect(nodeGroups).toHaveLength(2)
  })

  it('shows branch names in text elements', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    const nameTexts = wrapper.findAll('.node-name')
    expect(nameTexts[0].text()).toBe('main')
    expect(nameTexts[1].text()).toBe('feature-branch')
  })

  it('shows LSN in text elements', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    const lsnTexts = wrapper.findAll('.node-lsn')
    expect(lsnTexts[0].text()).toBe('0/5000')
    expect(lsnTexts[1].text()).toBe('0/4000')
  })

  it('shows default badge on main branch', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    const badges = wrapper.findAll('.node-badge')
    expect(badges).toHaveLength(1)
    expect(badges[0].text()).toBe('main')
  })

  it('draws connection lines between parent and child', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    const paths = wrapper.findAll('path')
    expect(paths.length).toBeGreaterThanOrEqual(1)
  })

  it('emits select event on node click', async () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    await wrapper.findAll('.tree-node')[1].trigger('click')

    expect(wrapper.emitted('select')).toBeTruthy()
    expect(wrapper.emitted('select')![0]).toEqual(['br_feat'])
  })

  it('shows action buttons when node is selected', async () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode, featureNode],
        activeBranchId: 'br_main',
      },
    })

    // Click feature branch to select it
    await wrapper.findAll('.tree-node')[1].trigger('click')

    const actions = wrapper.find('.node-actions')
    expect(actions.exists()).toBe(true)
  })

  it('handles empty nodes gracefully', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [],
        activeBranchId: '',
      },
    })

    const nodeGroups = wrapper.findAll('.tree-node')
    expect(nodeGroups).toHaveLength(0)
  })

  it('handles single node (root only)', () => {
    const wrapper = mount(BranchTreeView, {
      props: {
        nodes: [mainNode],
        activeBranchId: 'br_main',
      },
    })

    const nodeGroups = wrapper.findAll('.tree-node')
    expect(nodeGroups).toHaveLength(1)
    // No connection lines for single node
    const paths = wrapper.findAll('path')
    expect(paths).toHaveLength(0)
  })
})
