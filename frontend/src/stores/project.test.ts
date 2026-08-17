import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProjectStore } from './project'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn()
}))

vi.mock('@/services/api', () => ({ default: api }))

const project = {
  id: 'p1',
  name: 'Certification',
  description: 'Project',
  isActive: true,
  createdAt: '2026-08-14T08:00:00Z',
  updatedAt: '2026-08-14T08:00:00Z'
}

describe('project store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads and updates projects while keeping currentProject synchronized', async () => {
    api.get.mockResolvedValue({ data: [project] })
    const store = useProjectStore()
    await store.fetchProjects()
    expect(store.projects).toEqual([project])

    store.currentProject = project
    api.put.mockResolvedValue({ data: { ...project, name: 'Updated' } })
    const updated = await store.updateProject('p1', { name: 'Updated' })

    expect(api.put).toHaveBeenCalledWith('/projects/p1', { name: 'Updated' })
    expect(updated.name).toBe('Updated')
    expect(store.projects[0].name).toBe('Updated')
    expect(store.currentProject?.name).toBe('Updated')
  })


  it('unwraps projects from the Spring Page content array', async () => {
    api.get.mockResolvedValue({
      data: {
        content: [project],
        pageable: {
          pageNumber: 0,
          pageSize: 20,
        },
        totalElements: 1,
        totalPages: 1,
        size: 20,
        number: 0,
        first: true,
        last: true,
        numberOfElements: 1,
        empty: false,
        sort: {
          empty: true,
          sorted: false,
          unsorted: true,
        },
      },
    })

    const store = useProjectStore()
    const result = await store.fetchProjects()

    expect(result).toEqual([project])
    expect(store.projects).toEqual([project])
  })

  it('treats an empty Spring Page as an empty project list', async () => {
    api.get.mockResolvedValue({
      data: {
        content: [],
        pageable: {
          pageNumber: 0,
          pageSize: 20,
        },
        totalElements: 0,
        totalPages: 0,
        size: 20,
        number: 0,
        first: true,
        last: true,
        numberOfElements: 0,
        empty: true,
        sort: {
          empty: true,
          sorted: false,
          unsorted: true,
        },
      },
    })

    const store = useProjectStore()
    const result = await store.fetchProjects()

    expect(result).toEqual([])
    expect(store.projects).toEqual([])
  })

  it('removes deleted projects from local state', async () => {
    api.get.mockResolvedValue({ data: [project] })
    api.delete.mockResolvedValue({})
    const store = useProjectStore()
    await store.fetchProjects()
    store.currentProject = project

    await store.deleteProject('p1')

    expect(api.delete).toHaveBeenCalledWith('/projects/p1')
    expect(store.projects).toEqual([])
    expect(store.currentProject).toBeNull()
  })
})
