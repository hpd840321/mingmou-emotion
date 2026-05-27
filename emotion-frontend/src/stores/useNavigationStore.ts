import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface BreadcrumbItem { label: string; to?: string }

export const useNavigationStore = defineStore('navigation', () => {
  const breadcrumbs = ref<BreadcrumbItem[]>([])
  const activeNavItem = ref('')
  function setBreadcrumbs(items: BreadcrumbItem[]) { breadcrumbs.value = items }
  function setActiveNav(item: string) { activeNavItem.value = item }
  return { breadcrumbs, activeNavItem, setBreadcrumbs, setActiveNav }
})
