import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReferenceDataPanel from './ReferenceDataPanel.vue'
import { useReferenceDataStore } from '@/stores/referenceData'

vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

describe('ReferenceDataPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders generic lists and item properties without domain-specific assumptions', async () => {
    const store = useReferenceDataStore()
    vi.spyOn(store, 'fetchLists').mockResolvedValue([
      { id: 'l1', name: 'species', displayName: 'Species', active: true, itemCount: 1 }
    ] as any)
    vi.spyOn(store, 'fetchItems').mockImplementation(async (_project, listId) => {
      store.itemsByList[listId] = [{
        id: 'i1', name: 'acacia', label: 'Acacia', sortOrder: 0, active: true,
        properties: { family: 'Fabaceae' }, parents: []
      }]
      return store.itemsByList[listId]
    })
    store.lists = [{ id: 'l1', name: 'species', displayName: 'Species', active: true, itemCount: 1 }] as any

    const wrapper = mount(ReferenceDataPanel, { props: { projectId: 'p1' } })
    await flushPromises()

    expect(wrapper.text()).toContain('Species')
    expect(wrapper.text()).toContain('Acacia')
    expect(wrapper.text()).toContain('Fabaceae')
    expect(wrapper.text()).not.toContain('District')
  })

  it('creates a list from the inline form', async () => {
    const store = useReferenceDataStore()
    vi.spyOn(store, 'fetchLists').mockResolvedValue([])
    const create = vi.spyOn(store, 'createList').mockResolvedValue({
      id: 'l2', name: 'teams', displayName: 'Teams', active: true, itemCount: 0
    } as any)

    const wrapper = mount(ReferenceDataPanel, { props: { projectId: 'p1' } })
    await flushPromises()
    const inputs = wrapper.findAll('.create-list input')
    await inputs[0].setValue('teams')
    await inputs[1].setValue('Teams')
    await wrapper.find('.create-list button').trigger('click')

    expect(create).toHaveBeenCalledWith('p1', expect.objectContaining({ name: 'teams', displayName: 'Teams' }))
  })
})
