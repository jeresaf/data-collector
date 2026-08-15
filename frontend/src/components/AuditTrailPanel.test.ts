import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AuditTrailPanel from './AuditTrailPanel.vue'
import { useAuditStore } from '@/stores/audit'

vi.mock('@/services/api', () => ({ default: { get: vi.fn() } }))

describe('AuditTrailPanel', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows actor/action/target/request context and applies generic filters', async () => {
    const store = useAuditStore()
    store.entries = [{
      id: 'a1', action: 'REPORT_EXPORTED', resourceType: 'REPORT', resourceId: 'r1', resourceName: 'Monthly totals',
      actorType: 'USER', actorId: 'u1', actorName: 'admin', ipAddress: '203.0.113.10', userAgent: 'Mozilla/Test',
      requestId: 'req-1', sessionId: 'sess-1', status: 'SUCCESS', createdAt: '2026-08-14T10:00:00Z',
      details: [{ id: 'd1', key: 'format', oldValue: null, newValue: 'XLSX' }]
    }]
    store.totalElements = 1
    store.totalPages = 1
    const fetch = vi.spyOn(store, 'fetchLogs').mockResolvedValue({ items: store.entries, page: 0, size: 50, totalElements: 1, totalPages: 1 })

    const wrapper = mount(AuditTrailPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('REPORT_EXPORTED')
    expect(wrapper.text()).toContain('Monthly totals')
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('203.0.113.10')

    await wrapper.get('[data-testid="audit-action"]').setValue('FORM_PUBLISHED')
    await wrapper.get('[data-testid="audit-actor"]').setValue('reviewer')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetch).toHaveBeenLastCalledWith(expect.objectContaining({
      action: 'FORM_PUBLISHED', actor: 'reviewer', page: 0
    }))

    await wrapper.findAll('button').find(button => button.text() === 'Details')!.trigger('click')
    expect(wrapper.text()).toContain('req-1')
    expect(wrapper.text()).toContain('XLSX')
  })
})
