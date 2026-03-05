import { ref } from 'vue'

const locale = ref<'zh' | 'en'>((localStorage.getItem('lakeon_locale') as 'zh' | 'en') || 'zh')

export function useLocale() {
  function setLocale(l: 'zh' | 'en') {
    locale.value = l
    localStorage.setItem('lakeon_locale', l)
  }

  function t(zh: string, en: string): string {
    return locale.value === 'zh' ? zh : en
  }

  return { locale, setLocale, t }
}
