import { ref } from 'vue'

const theme = ref<'light'>('light')

document.documentElement.setAttribute('data-theme', 'light')

export function useTheme() {
  return { theme, toggle: () => {} }
}
