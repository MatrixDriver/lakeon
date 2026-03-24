import { ref, watch } from 'vue'

const STORAGE_KEY = 'dbay-theme'
const stored = localStorage.getItem(STORAGE_KEY)
const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
const theme = ref<'light' | 'dark'>((stored as 'light' | 'dark') || (prefersDark ? 'dark' : 'light'))

function applyTheme(t: 'light' | 'dark') {
  document.documentElement.setAttribute('data-theme', t)
  localStorage.setItem(STORAGE_KEY, t)
}

applyTheme(theme.value)

watch(theme, applyTheme)

export function useTheme() {
  function toggle() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
  }
  return { theme, toggle }
}
