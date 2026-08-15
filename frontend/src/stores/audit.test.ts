import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import { useAuditStore } from './audit'

vi.mock('@/services/api', () => ({ default: { get: vi.fn() } }))

describe('audit store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads a filtered paged audit trail without sending blank filters', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: {
        items: [{ id: 'a1', action: 'FORM_PUBLISHED', actorType: 'USER', actorName: 'admin', status: 'SUCCESS', createdAt: '2026-08-14T10:00:00Z', details: [] }],
        page: 1,
        size: 25,
        totalElements: 26,
        totalPages: 2
      }
    } as any)

    const store = useAuditStore()
    await store.fetchLogs({ action: 'FORM_PUBLISHED', actor: 'admin', resourceType: '', page: 1, size: 25 })

    expect(api.get).toHaveBeenCalledWith('/audit-logs', {
      params: { page: 1, size: 25, action: 'FORM_PUBLISHED', actor: 'admin' }
    })
    expect(store.entries[0].action).toBe('FORM_PUBLISHED')
    expect(store.totalElements).toBe(26)
  })
})
