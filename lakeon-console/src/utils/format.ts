export function formatDuration(ms: number | null): string {
  if (ms === null) return '-'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

export function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

export function maskApiKey(key: string): string {
  if (key.length <= 10) return key
  return key.substring(0, 6) + '...' + key.substring(key.length - 4)
}
