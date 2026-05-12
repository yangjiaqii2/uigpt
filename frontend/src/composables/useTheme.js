import { ref } from 'vue'

/** 产品固定深色主题，不再提供明暗切换 */
const isDark = ref(true)

function applyDark() {
  if (typeof document === 'undefined') return
  isDark.value = true
  document.documentElement.setAttribute('data-theme', 'dark')
}

export function useTheme() {
  function initTheme() {
    if (typeof window === 'undefined') return
    applyDark()
    try {
      localStorage.removeItem('uigpt_theme')
    } catch {
      /* ignore */
    }
  }

  return { isDark, initTheme }
}
