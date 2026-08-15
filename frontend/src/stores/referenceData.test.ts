import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useReferenceDataStore } from './referenceData'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('@/services/api', () => ({ default: api }))

describe('reference data store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads lists and items through project-scoped endpoints', async () => {
    api.get
      .mockResolvedValueOnce({ data: [{ id: 'l1', name: 'regions', active: true, itemCount: 1 }] })
      .mockResolvedValueOnce({ data: [{ id: 'i1', name: 'central', label: 'Central', sortOrder: 0, active: true, properties: {}, parents: [] }] })
    const store = useReferenceDataStore()
    await store.fetchLists('p1')
    await store.fetchItems('p1', 'l1')

    expect(api.get).toHaveBeenNthCalledWith(1, '/projects/p1/reference-lists')
    expect(api.get).toHaveBeenNthCalledWith(2, '/projects/p1/reference-lists/l1/items')
    expect(store.itemsByList.l1[0].name).toBe('central')
  })

  it('imports CSV and refreshes the selected list', async () => {
    api.post.mockResolvedValue({ data: { created: 2, updated: 0, relationshipsCreated: 1, warnings: [] } })
    api.get.mockResolvedValue({ data: [] })
    const store = useReferenceDataStore()
    const file = new File(['name,label\na,Alpha\n'], 'items.csv', { type: 'text/csv' })

    const result = await store.importCsv('p1', 'l1', file)

    expect(api.post).toHaveBeenCalledWith(
      '/projects/p1/reference-lists/l1/import',
      expect.any(FormData),
      expect.objectContaining({ headers: { 'Content-Type': 'multipart/form-data' } })
    )
    expect(result.created).toBe(2)
    expect(api.get).toHaveBeenCalledWith('/projects/p1/reference-lists/l1/items')
  })
})
