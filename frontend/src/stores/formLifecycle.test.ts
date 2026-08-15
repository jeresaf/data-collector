import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import { useFormLifecycleStore } from './formLifecycle'

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const mockedApi = vi.mocked(api)

const form = {
  id: 'form-1',
  name: 'Household Survey',
  title: 'Household Survey',
  isPublished: true,
  isActive: true,
  formVersionId: 'version-2',
}

const versions = [
  {
    id: 'version-2',
    version: '2',
    status: 'PUBLISHED',
    isPublished: true,
    isLatest: true,
  },
]

const history = [
  {
    id: 'history-1',
    formVersionId: 'version-2',
    version: '2',
    action: 'PUBLISHED',
    performedById: 'user-1',
    performedByUsername: 'admin',
    performedByName: 'System Administrator',
    performedAt: '2026-08-15T10:00:00Z',
    notes: 'Published version 2',
  },
]

function mockReads() {
  mockedApi.get.mockImplementation(async (url: string) => {
    if (url.endsWith('/lifecycle/versions')) return { data: versions } as any
    if (url.endsWith('/lifecycle/history')) return { data: history } as any
    return { data: form } as any
  })
}

describe('formLifecycle store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    mockReads()
    mockedApi.post.mockResolvedValue({ data: {} } as any)
  })

  it('loads form, version summaries, and publish history', async () => {
    const store = useFormLifecycleStore()

    await store.load('project-1', 'form-1')

    expect(store.form?.name).toBe('Household Survey')
    expect(store.versions).toEqual(versions)
    expect(store.history).toEqual(history)
    expect(mockedApi.get).toHaveBeenCalledWith('/projects/project-1/forms/form-1')
    expect(mockedApi.get).toHaveBeenCalledWith('/projects/project-1/forms/form-1/lifecycle/versions')
    expect(mockedApi.get).toHaveBeenCalledWith('/projects/project-1/forms/form-1/lifecycle/history')
  })

  it('publishes an explicit version using the backend contract', async () => {
    const store = useFormLifecycleStore()

    await store.publishVersion('project-1', 'form-1', 'version-2')

    expect(mockedApi.post).toHaveBeenCalledWith(
      '/projects/project-1/forms/form-1/publish',
      null,
      { params: { versionId: 'version-2' } },
    )
    expect(mockedApi.get).toHaveBeenCalled()
  })

  it('archives the form then refreshes lifecycle state', async () => {
    const store = useFormLifecycleStore()

    await store.archiveForm('project-1', 'form-1')

    expect(mockedApi.post).toHaveBeenCalledWith('/projects/project-1/forms/form-1/archive')
    expect(mockedApi.get).toHaveBeenCalled()
  })
})
