import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useReportStore } from './report'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/services/api', () => ({ default: api }))

describe('report store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('discovers generic form fields and runs a saved report', async () => {
    api.get.mockResolvedValueOnce({ data: [
      { fieldPath: 'amount', label: 'Amount', fieldKind: 'ANSWER', dataType: 'decimal', media: false, calculated: false }
    ] })
    api.post.mockResolvedValueOnce({ data: {
      runId: 'run1', reportId: 'r1', reportName: 'Totals',
      columns: [{ key: 'amount::SUM', label: 'SUM Amount', fieldKind: 'ANSWER', aggregation: 'SUM', groupBy: false }],
      rows: [{ 'amount::SUM': 30 }], rowCount: 1, sourceSubmissionCount: 2, executedAt: '2026-08-14T00:00:00Z'
    } })

    const store = useReportStore()
    await store.discoverFields('p1', 'f1')
    const result = await store.runReport('p1', 'r1', {})

    expect(api.get).toHaveBeenCalledWith('/projects/p1/reports/forms/f1/fields')
    expect(api.post).toHaveBeenCalledWith('/projects/p1/reports/r1/run', {})
    expect(store.availableFields[0].fieldPath).toBe('amount')
    expect(result.rows[0]['amount::SUM']).toBe(30)
  })

  it('requests CSV and Excel exports through the same report definition', async () => {
    api.post.mockResolvedValue({ data: new Blob(['x']), headers: { 'content-disposition': 'attachment; filename="generic-report.csv"' } })
    const store = useReportStore()
    const result = await store.exportReport('p1', 'r1', 'csv')
    expect(api.post).toHaveBeenCalledWith('/projects/p1/reports/r1/export/csv', {}, { responseType: 'blob' })
    expect(result.filename).toBe('generic-report.csv')
  })
})
