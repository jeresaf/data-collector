import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWebFormAssignmentsStore } from './webFormAssignments'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/services/api', () => ({ default: api }))

const assignment = {
  id: 'a1',
  projectId: 'p1',
  formId: 'f1',
  targetType: 'USER',
  targetId: 'u1',
  targetName: 'Alice Collector',
  targetDetail: 'alice@example.com',
  assignedAt: '2026-08-15T05:00:00Z',
}

describe('web form assignments store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads assignments, project members and project roles', async () => {
    api.get
      .mockResolvedValueOnce({ data: [assignment] })
      .mockResolvedValueOnce({
        data: [{
          id: 'u1',
          username: 'alice',
          email: 'alice@example.com',
          fullName: 'Alice Collector',
        }],
      })
      .mockResolvedValueOnce({
        data: [{
          id: 'r1',
          name: 'DATA_ANALYST',
          displayName: 'Data Analyst',
          description: 'Analyze project data',
        }],
      })

    const store = useWebFormAssignmentsStore()
    await store.loadManagementData('p1', 'f1')

    expect(api.get).toHaveBeenNthCalledWith(1, '/projects/p1/forms/f1/web-assignments')
    expect(api.get).toHaveBeenNthCalledWith(
      2,
      '/projects/p1/forms/f1/web-assignments/users',
      { params: { q: '' } },
    )
    expect(api.get).toHaveBeenNthCalledWith(3, '/projects/p1/forms/f1/web-assignments/roles')
    expect(store.assignments).toEqual([assignment])
    expect(store.users).toHaveLength(1)
    expect(store.roles).toHaveLength(1)
  })

  it('assigns users and roles and removes an assignment', async () => {
    const roleAssignment = {
      ...assignment,
      id: 'a2',
      targetType: 'ROLE',
      targetId: 'r1',
      targetName: 'Data Analyst',
      targetDetail: 'DATA_ANALYST',
    }

    api.post
      .mockResolvedValueOnce({ data: assignment })
      .mockResolvedValueOnce({ data: roleAssignment })
    api.delete.mockResolvedValue({})

    const store = useWebFormAssignmentsStore()

    await store.assignUser('p1', 'f1', 'u1')
    expect(api.post).toHaveBeenNthCalledWith(
      1,
      '/projects/p1/forms/f1/web-assignments/users',
      { userId: 'u1' },
    )

    await store.assignRole('p1', 'f1', 'r1')
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/projects/p1/forms/f1/web-assignments/roles',
      { roleId: 'r1' },
    )
    expect(store.assignments).toHaveLength(2)

    await store.removeAssignment('p1', 'f1', 'a1')
    expect(api.delete).toHaveBeenCalledWith('/projects/p1/forms/f1/web-assignments/a1')
    expect(store.assignments.map((item) => item.id)).toEqual(['a2'])
  })
})
