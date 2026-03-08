import { describe, it, expect } from 'vitest'
import { formatDuration, maskApiKey } from '../utils/format'

describe('formatDuration', () => {
  it('returns "-" for null', () => {
    expect(formatDuration(null)).toBe('-')
  })

  it('formats milliseconds', () => {
    expect(formatDuration(123)).toBe('123ms')
    expect(formatDuration(0)).toBe('0ms')
    expect(formatDuration(999)).toBe('999ms')
  })

  it('formats seconds', () => {
    expect(formatDuration(1000)).toBe('1.0s')
    expect(formatDuration(1500)).toBe('1.5s')
    expect(formatDuration(12345)).toBe('12.3s')
  })
})

describe('maskApiKey', () => {
  it('masks long keys', () => {
    const key = 'lk_abcdefghijklmnopqrstuvwxyz1234567890'
    const masked = maskApiKey(key)
    expect(masked).toBe('lk_abc...7890')
    expect(masked.length).toBeLessThan(key.length)
  })

  it('returns short keys unchanged', () => {
    expect(maskApiKey('short')).toBe('short')
    expect(maskApiKey('1234567890')).toBe('1234567890')
  })
})
