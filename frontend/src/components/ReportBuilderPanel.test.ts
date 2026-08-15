import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReportBuilderPanel from './ReportBuilderPanel.vue'
import { useReportStore } from '@/stores/report'
import { useFormStore } from '@/stores/form'

vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

describe('ReportBuilderPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('builds a report from arbitrary form fields without domain-specific dimensions', async () => {
    const reportStore = useReportStore()
    const formStore = useFormStore()
    formStore.forms = [{ id: 'f1', name: 'Inspection', version: '1', description: '', isPublished: true, createdAt: '', updatedAt: '' }]
    vi.spyOn(reportStore, 'fetchDefinitions').mockResolvedValue([])
    vi.spyOn(reportStore, 'discoverFields').mockImplementation(async () => {
      reportStore.availableFields = [
        { fieldPath: 'category', label: 'Category', fieldKind: 'ANSWER', dataType: 'string', media: false, calculated: false },
        { fieldPath: 'amount', label: 'Amount', fieldKind: 'ANSWER', dataType: 'decimal', media: false, calculated: false },
        { fieldPath: '$submission.source', label: 'Source Client', fieldKind: 'SYSTEM', dataType: 'string', media: false, calculated: false }
      ]
      return reportStore.availableFields
    })
    const create = vi.spyOn(reportStore, 'createDefinition').mockResolvedValue({ id: 'r1' } as any)

    const wrapper = mount(ReportBuilderPanel, { props: { projectId: 'p1' } })
    await flushPromises()
    await wrapper.get('[data-testid="report-name"]').setValue('Generic totals')
    await wrapper.get('[data-testid="source-form"]').setValue('f1')
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    expect(wrapper.text()).toContain('Category')
    expect(wrapper.text()).toContain('Amount')
    expect(wrapper.text()).toContain('Source Client')
    expect(wrapper.text()).not.toContain('District')

    await rows[0].find('input[type="checkbox"]').setValue(true)
    await rows[1].find('input[type="checkbox"]').setValue(true)
    await flushPromises()
    const selectedRows = wrapper.findAll('tbody tr')
    await selectedRows[0].findAll('input[type="checkbox"]')[1].setValue(true)
    await selectedRows[1].find('select').setValue('SUM')
    await wrapper.findAll('button').find(button => button.text().includes('Save Report'))!.trigger('click')
    await flushPromises()

    expect(create).toHaveBeenCalledWith('p1', expect.objectContaining({
      name: 'Generic totals',
      formId: 'f1',
      fields: expect.arrayContaining([
        expect.objectContaining({ fieldPath: 'category', groupBy: true }),
        expect.objectContaining({ fieldPath: 'amount', aggregation: 'SUM' })
      ])
    }))
  })
})
