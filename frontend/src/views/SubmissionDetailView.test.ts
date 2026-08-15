import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import api from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import SubmissionDetailView from './SubmissionDetailView.vue'

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
  },
  ACCESS_TOKEN_STORAGE_KEY: 'odk_access_token',
  REFRESH_TOKEN_STORAGE_KEY: 'odk_refresh_token',
  AUTH_USER_STORAGE_KEY: 'odk_auth_user',
}))

const detail = {
  id: 's1',
  instanceId: 'uuid:s1',
  formId: 'f1',
  formTitle: 'Registration',
  formXmlns: 'registration',
  formVersionId: 'fv1',
  formVersion: '1',
  xmlContent: '<data><name>Amina</name></data>',
  jsonContent: '{"name":"Amina","members":[{"name":"Ali"}]}',
  status: 'SUBMITTED',
  metadata: { sourceClient: 'ODK Collect', deviceId: 'device-1' },
  submittedAt: '2026-08-15T06:00:00Z',
  submittedById: 'app-1',
  submittedByName: 'Field Tester',
  reviewedAt: null,
  reviewedById: null,
  reviewNotes: null,
  attachments: [
    { id: 'a1', fileName: 'photo.jpg', contentType: 'image/jpeg', fileSize: 1024, answerName: 'photo' },
  ],
  answers: [
    { id: 'ans1', questionName: 'name', questionPath: '/data/name', value: 'Amina', repeatIndex: null },
    { id: 'ans2', questionName: 'member_name', questionPath: '/data/members/member/name', value: 'Ali', repeatIndex: 1 },
  ],
  events: [
    { id: 'e1', eventType: 'RECEIVED', eventData: { sourceClient: 'ODK Collect' }, createdAt: '2026-08-15T06:00:00Z', createdById: null },
  ],
}

async function mountView(roles: string[]) {
  const pinia = createPinia()
  setActivePinia(pinia)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/submissions', component: { template: '<div />' } },
      { path: '/submissions/:id', component: SubmissionDetailView },
    ],
  })
  await router.push('/submissions/s1')
  await router.isReady()

  useAuthStore().user = {
    id: 'u1',
    username: 'reviewer',
    email: 'reviewer@example.com',
    fullName: 'Reviewer',
    roles,
    permissions: [],
  }

  const wrapper = mount(SubmissionDetailView, {
    global: { plugins: [pinia, router] },
  })
  await flushPromises()
  return wrapper
}

describe('SubmissionDetailView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/submissions/s1') return { data: detail } as any
      return { data: {} } as any
    })
  })

  it('renders every submission fidelity layer', async () => {
    const wrapper = await mountView(['DATA_ANALYST'])

    expect(wrapper.get('h1').text()).toBe('Registration')
    expect(wrapper.text()).toContain('ODK Collect')
    expect(wrapper.text()).toContain('Amina')
    expect(wrapper.text()).toContain('/data/members/member/name')

    const tab = (label: string) =>
      wrapper.findAll('nav.tabs button').find(button => button.text().startsWith(label))!

    await tab('Raw XML').trigger('click')
    expect(wrapper.get('[data-testid="xml-panel"]').text()).toContain('<data><name>Amina</name></data>')

    await tab('JSON').trigger('click')
    expect(wrapper.get('[data-testid="json-panel"]').text()).toContain('"members": [')

    await tab('Attachments').trigger('click')
    expect(wrapper.get('[data-testid="attachments-panel"]').text()).toContain('photo.jpg')

    await tab('Audit / Events').trigger('click')
    expect(wrapper.get('[data-testid="events-panel"]').text()).toContain('RECEIVED')

    await tab('Metadata').trigger('click')
    expect(wrapper.get('[data-testid="metadata-panel"]').text()).toContain('device-1')
  })

  it('hides review mutation controls from read-only roles', async () => {
    const wrapper = await mountView(['DATA_ANALYST'])
    expect(wrapper.find('[data-testid="update-status"]').exists()).toBe(false)
  })

  it('allows managers to update status and notes from the returned API detail', async () => {
    vi.mocked(api.put).mockResolvedValue({
      data: {
        ...detail,
        status: 'APPROVED',
        reviewedAt: '2026-08-15T06:30:00Z',
        reviewNotes: 'Checked and approved',
      },
    } as any)

    const wrapper = await mountView(['PROJECT_MANAGER'])

    await wrapper.get('[data-testid="update-status"]').trigger('click')
    await wrapper.get('#review-status').setValue('APPROVED')
    await wrapper.get('#review-notes').setValue('Checked and approved')
    await wrapper.get('[data-testid="confirm-status"]').trigger('click')
    await flushPromises()

    expect(api.put).toHaveBeenCalledWith('/submissions/s1/status', {
      status: 'APPROVED',
      reviewNotes: 'Checked and approved',
    })
    expect(wrapper.text()).toContain('APPROVED')
    expect(wrapper.text()).toContain('Checked and approved')
  })
})
