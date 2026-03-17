import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SchemaDiffView from '../components/SchemaDiffView.vue'
import type { SchemaDiffResponse } from '../api/diff'

const emptyDiff: SchemaDiffResponse = {
  tables: { added: [], removed: [], modified: [] },
  indexes: { added: [], removed: [] },
}

describe('SchemaDiffView', () => {
  it('renders "no changes" badge when diff is empty', () => {
    const wrapper = mount(SchemaDiffView, {
      props: { diff: emptyDiff },
    })

    expect(wrapper.find('.badge-empty').exists()).toBe(true)
    expect(wrapper.find('.badge-empty').text()).toBe('无差异')
  })

  it('shows added table count badge', () => {
    const diff: SchemaDiffResponse = {
      tables: {
        added: [
          { name: 'users', schema: 'public', columns: [{ name: 'id', data_type: 'int4', is_nullable: false, column_default: null }] },
          { name: 'orders', schema: 'public', columns: [] },
        ],
        removed: [],
        modified: [],
      },
      indexes: { added: [], removed: [] },
    }

    const wrapper = mount(SchemaDiffView, { props: { diff } })

    const badge = wrapper.find('.badge-added')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('2')
    expect(badge.text()).toContain('新增表')
  })

  it('shows removed table count badge', () => {
    const diff: SchemaDiffResponse = {
      tables: {
        added: [],
        removed: [
          { name: 'old_table', schema: 'public', columns: [] },
        ],
        modified: [],
      },
      indexes: { added: [], removed: [] },
    }

    const wrapper = mount(SchemaDiffView, { props: { diff } })

    const badge = wrapper.find('.badge-removed')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('1')
    expect(badge.text()).toContain('删除表')
  })

  it('shows modified table section with column changes', () => {
    const diff: SchemaDiffResponse = {
      tables: {
        added: [],
        removed: [],
        modified: [
          {
            name: 'products',
            schema: 'public',
            columns: {
              added: [{ name: 'price', data_type: 'numeric', is_nullable: true, column_default: null }],
              removed: [{ name: 'cost', data_type: 'int4', is_nullable: false, column_default: null }],
              modified: [],
            },
          },
        ],
      },
      indexes: { added: [], removed: [] },
    }

    const wrapper = mount(SchemaDiffView, { props: { diff } })

    const badge = wrapper.find('.badge-modified')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('1')
    expect(badge.text()).toContain('修改表')

    // Check the modified section header shows table name
    const modifiedSection = wrapper.find('.diff-modified')
    expect(modifiedSection.exists()).toBe(true)
    expect(modifiedSection.find('.diff-section-header').text()).toContain('products')

    // Added column shows with + marker
    const addedCol = modifiedSection.find('.diff-col-added')
    expect(addedCol.exists()).toBe(true)
    expect(addedCol.text()).toContain('price')

    // Removed column shows with - marker
    const removedCol = modifiedSection.find('.diff-col-removed')
    expect(removedCol.exists()).toBe(true)
    expect(removedCol.text()).toContain('cost')
  })

  it('shows added index section', () => {
    const diff: SchemaDiffResponse = {
      tables: { added: [], removed: [], modified: [] },
      indexes: {
        added: [
          { name: 'idx_users_email', table_name: 'users', definition: 'CREATE INDEX idx_users_email ON users(email)' },
        ],
        removed: [],
      },
    }

    const wrapper = mount(SchemaDiffView, { props: { diff } })

    const badge = wrapper.find('.badge-index')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('1')
    expect(badge.text()).toContain('新增索引')

    // The added indexes section header
    const addedSections = wrapper.findAll('.diff-added')
    const indexSection = addedSections.find(s => s.find('.diff-section-header').text().includes('新增索引'))
    expect(indexSection).toBeDefined()
    expect(indexSection!.text()).toContain('idx_users_email')
  })

  it('does not show empty badge when there are changes', () => {
    const diff: SchemaDiffResponse = {
      tables: {
        added: [{ name: 'new_table', schema: 'public', columns: [] }],
        removed: [],
        modified: [],
      },
      indexes: { added: [], removed: [] },
    }

    const wrapper = mount(SchemaDiffView, { props: { diff } })

    expect(wrapper.find('.badge-empty').exists()).toBe(false)
  })
})
