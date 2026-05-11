import { describe, it, expect } from 'vitest'
import { formatDuration, formatOperationDuration, maskApiKey } from '../utils/format'

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

describe('formatOperationDuration', () => {
  it('uses durationMs for completed operations', () => {
    const op = { status: 'SUCCESS', durationMs: 5200, startedAt: '2026-05-11T03:00:00Z' }
    expect(formatOperationDuration(op, Date.now())).toBe('5.2s')
  })

  it('uses durationMs for failed operations', () => {
    const op = { status: 'FAILED', durationMs: 12000, startedAt: '2026-05-11T03:00:00Z' }
    expect(formatOperationDuration(op, Date.now())).toBe('12.0s')
  })

  it('computes live elapsed time for IN_PROGRESS operations', () => {
    const startedAt = '2026-05-11T03:00:00Z'
    const now = new Date('2026-05-11T03:00:08.500Z').getTime()
    const op = { status: 'IN_PROGRESS', durationMs: null, startedAt }
    expect(formatOperationDuration(op, now)).toBe('8.5s')
  })

  it('clamps negative elapsed (clock skew) to 0', () => {
    const startedAt = '2026-05-11T03:00:10Z'
    const now = new Date('2026-05-11T03:00:00Z').getTime()
    const op = { status: 'IN_PROGRESS', durationMs: null, startedAt }
    expect(formatOperationDuration(op, now)).toBe('0ms')
  })

  it('falls back to "-" when IN_PROGRESS has no startedAt', () => {
    const op = { status: 'IN_PROGRESS', durationMs: null, startedAt: null }
    expect(formatOperationDuration(op, Date.now())).toBe('-')
  })

  it('returns "-" when completed op has no durationMs', () => {
    const op = { status: 'SUCCESS', durationMs: null, startedAt: '2026-05-11T03:00:00Z' }
    expect(formatOperationDuration(op, Date.now())).toBe('-')
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
