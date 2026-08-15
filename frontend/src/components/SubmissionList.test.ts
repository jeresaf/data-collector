import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SubmissionList from './SubmissionList.vue'
import { useSubmissionStore } from '@/stores/submission'

vi.mock('@/services/api', () => ({ default: { get: vi.fn(), put: vi.fn() } }))

const submission = {
  id: 's1',
  instanceId: 'uuid:s1',
  formId: 'f1',
  formTitle: 'Registration',
  formXmlns: 'registration',
  formVersionId: 'fv1',
  formVersion: '1',
  status: 'SUBMITTED',
  source: 'ODK Collect',
  submittedAt: '2026-08-14T10:00:00Z',
  submittedById: 'u1',
  submittedByName: 'Tester',
  reviewedAt: null,
  reviewedById: null,
  attachmentCount: 2
}

describe('SubmissionList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('loads only the selected project and navigates to detail', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/submissions/:id', component: { template: '<div />' } }
      ]
    })
    await router.push('/')
    await router.isReady()

    const store = useSubmissionStore()
    const fetch = vi.spyOn(store, 'fetchSubmissions').mockResolvedValue({
      content: [submission],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
      first: true,
      last: true,
      numberOfElements: 1
    } as any)

    const wrapper = mount(SubmissionList, {
      props: { projectId: 'p1' },
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith(expect.objectContaining({ projectId: 'p1' }))
    expect(wrapper.text()).toContain('Registration')
    expect(wrapper.text()).toContain('ODK Collect')
    expect(wrapper.findAll('thead th')).toHaveLength(8)

    await wrapper.get('button[aria-label="View submission details"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/submissions/s1')
  })

  it('wires XML and JSON download actions', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    await router.push('/')
    await router.isReady()

    const store = useSubmissionStore()
    vi.spyOn(store, 'fetchSubmissions').mockResolvedValue({
      content: [submission], totalElements: 1, totalPages: 1, size: 20, number: 0,
      first: true, last: true, numberOfElements: 1
    } as any)
    const xml = vi.spyOn(store, 'downloadXml').mockResolvedValue()
    const json = vi.spyOn(store, 'downloadJson').mockResolvedValue()

    const wrapper = mount(SubmissionList, { props: { projectId: 'p1' }, global: { plugins: [router] } })
    await flushPromises()

    await wrapper.get('button[aria-label="Download submission XML"]').trigger('click')
    await wrapper.get('button[aria-label="Download submission JSON"]').trigger('click')
    expect(xml).toHaveBeenCalledWith('s1')
    expect(json).toHaveBeenCalledWith('s1')
  })
})
