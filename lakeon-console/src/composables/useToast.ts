import { ref } from 'vue'

type ToastType = 'success' | 'error' | 'warning' | 'info'

interface ToastItem {
  id: number
  message: string
  type: ToastType
}

const toasts = ref<ToastItem[]>([])
let nextId = 0

export function useToast() {
  function add(message: string, type: ToastType = 'info', duration = 3000) {
    const id = nextId++
    toasts.value.push({ id, message, type })
    if (duration > 0) {
      setTimeout(() => remove(id), duration)
    }
  }

  function remove(id: number) {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }

  function success(message: string, duration?: number) { add(message, 'success', duration) }
  function error(message: string, duration?: number) { add(message, 'error', duration ?? 5000) }
  function warning(message: string, duration?: number) { add(message, 'warning', duration) }
  function info(message: string, duration?: number) { add(message, 'info', duration) }

  return { toasts, add, remove, success, error, warning, info }
}
