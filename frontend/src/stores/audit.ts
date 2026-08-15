import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface AuditDetail {
  id: string
  key: string
  oldValue?: string | null
  newValue?: string | null
  valueType?: string | null
  metadata?: string | null
  createdAt?: string | null
}

export interface AuditEntry {
  id: string
  action: string
  resourceType?: string | null
  resourceId?: string | null
  resourceName?: string | null
  actorType: 'USER' | 'APP_USER' | 'SYSTEM' | string
  actorId?: string | null
  actorName?: string | null
  ipAddress?: string | null
  userAgent?: string | null
  requestId?: string | null
  sessionId?: string | null
  status: string
  errorMessage?: string | null
  oldValues?: string | null
  newValues?: string | null
  metadata?: string | null
  createdAt: string
  details: AuditDetail[]
}

export interface AuditPage {
  items: AuditEntry[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AuditFilters {
  action?: string
  resourceType?: string
  resourceId?: string
  status?: string
  actor?: string
  ipAddress?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export const useAuditStore = defineStore('audit', () => {
  const entries = ref<AuditEntry[]>([])
  const page = ref(0)
  const size = ref(50)
  const totalElements = ref(0)
  const totalPages = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchLogs(filters: AuditFilters = {}) {
    loading.value = true
    error.value = null
    try {
      const params: Record<string, string | number> = {
        page: filters.page ?? page.value,
        size: filters.size ?? size.value
      }
      for (const key of ['action', 'resourceType', 'resourceId', 'status', 'actor', 'ipAddress', 'from', 'to'] as const) {
        const value = filters[key]
        if (value !== undefined && value !== null && String(value).trim() !== '') params[key] = value
      }
      const response = await api.get<AuditPage>('/audit-logs', { params })
      entries.value = response.data.items || []
      page.value = response.data.page
      size.value = response.data.size
      totalElements.value = response.data.totalElements
      totalPages.value = response.data.totalPages
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to load audit trail'
      throw err
    } finally {
      loading.value = false
    }
  }

  return { entries, page, size, totalElements, totalPages, loading, error, fetchLogs }
})
