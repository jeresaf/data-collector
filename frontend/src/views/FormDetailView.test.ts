import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import FormDetailView from './FormDetailView.vue'

const routerPush = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { projectId: 'project-1', formId: 'form-1' } }),
  useRouter: () => ({ push: routerPush }),
}))

vi.mock('@/services/api', async () => {
  const actual = await vi.importActual<any>('@/services/api')
  return {
    ...actual,
    default: {
      get: vi.fn(),
      post: vi.fn(),
      delete: vi.fn(),
    },
  }
})

const mockedApi = vi.mocked(api)

const form = {
  id: 'form-1',
  name: 'Household Survey',
  title: 'Household Survey',
  description: 'Household registration form',
  xmlns: 'household_registration',
  version: '2',
  isPublished: true,
  isActive: true,
  formVersionId: 'version-2',
  updatedAt: '2026-08-15T10:00:00Z',
}

const versions = [
  {
    id: 'version-2', version: '2', status: 'PUBLISHED', isPublished: true, isLatest: true,
    changelog: 'Second version', publishedAt: '2026-08-15T10:00:00Z', createdAt: '2026-08-15T09:00:00Z',
  },
  {
    id: 'version-1', version: '1', status: 'VALIDATED', isPublished: false, isLatest: false,
    changelog: 'Initial version', createdAt: '2026-08-14T09:00:00Z',
  },
]

const history = [
  {
    id: 'history-1', formVersionId: 'version-2', version: '2', action: 'PUBLISHED',
    performedById: 'user-1', performedByUsername: 'admin', performedByName: 'System Administrator',
    performedAt: '2026-08-15T10:00:00Z', notes: 'Published version 2 from project administration',
  },
]

const webAssignment = {
  id: 'assignment-1',
  projectId: 'project-1',
  formId: 'form-1',
  targetType: 'USER',
  targetId: 'user-1',
  targetName: 'Alice Collector',
  targetDetail: 'alice@example.com',
  assignedAt: '2026-08-15T10:00:00Z',
}

function mockReads() {
  mockedApi.get.mockImplementation(async (url: string) => {
    if (url.endsWith('/lifecycle/versions')) return { data: versions } as any
    if (url.endsWith('/lifecycle/history')) return { data: history } as any
    if (url.endsWith('/web-assignments/users')) {
      return { data: [{ id: 'user-1', username: 'alice', email: 'alice@example.com', fullName: 'Alice Collector' }] } as any
    }
    if (url.endsWith('/web-assignments/roles')) {
      return { data: [{ id: 'role-1', name: 'DATA_ANALYST', displayName: 'Data Analyst', description: 'Analyze data' }] } as any
    }
    if (url.endsWith('/web-assignments')) return { data: [webAssignment] } as any
    return { data: form } as any
  })
}

describe('FormDetailView', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockReads()
    mockedApi.post.mockResolvedValue({ data: {} } as any)
    mockedApi.delete.mockResolvedValue({ data: {} } as any)

    useAuthStore().user = {
      id: 'admin', username: 'admin', email: 'admin@example.com', fullName: 'System Administrator',
      roles: ['SYSTEM_ADMIN'], permissions: ['PROJECT_UPDATE'],
    } as any
  })

  it('renders lifecycle versions and publish history', async () => {
    const wrapper = mount(FormDetailView)
    await flushPromises()

    expect(wrapper.text()).toContain('Household Survey')
    expect(wrapper.text()).toContain('Versions (2)')

    const versionsTab = wrapper.findAll('nav.tabs button').find((button) => button.text().startsWith('Versions'))
    expect(versionsTab).toBeTruthy()
    await versionsTab!.trigger('click')
    expect(wrapper.text()).toContain('Version 2')
    expect(wrapper.text()).toContain('Second version')

    const historyTab = wrapper.findAll('nav.tabs button').find((button) => button.text().includes('Publish history'))
    expect(historyTab).toBeTruthy()
    await historyTab!.trigger('click')
    expect(wrapper.text()).toContain('System Administrator')
    expect(wrapper.text()).toContain('Published version 2 from project administration')
  })

  it('publishes a selected version and archives the form', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mount(FormDetailView)
    await flushPromises()

    const versionsTab = wrapper.findAll('nav.tabs button').find((button) => button.text().startsWith('Versions'))
    await versionsTab!.trigger('click')

    const publishButton = wrapper.get('button[aria-label="Publish version 1"]')
    await publishButton.trigger('click')
    await flushPromises()

    expect(mockedApi.post).toHaveBeenCalledWith(
      '/projects/project-1/forms/form-1/publish',
      null,
      { params: { versionId: 'version-1' } },
    )

    const archiveButton = wrapper.findAll('button').find((button) => button.text() === 'Archive form')
    expect(archiveButton).toBeTruthy()
    await archiveButton!.trigger('click')
    await flushPromises()

    expect(mockedApi.post).toHaveBeenCalledWith('/projects/project-1/forms/form-1/archive')
  })

  it('opens web access management from the form detail page', async () => {
    const wrapper = mount(FormDetailView)
    await flushPromises()

    const accessTab = wrapper.findAll('nav.tabs button').find((button) => button.text() === 'Web access')
    expect(accessTab).toBeTruthy()
    await accessTab!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Web form access')
    expect(wrapper.text()).toContain('Alice Collector')
    expect(mockedApi.get).toHaveBeenCalledWith(
      '/projects/project-1/forms/form-1/web-assignments',
    )
  })
})
