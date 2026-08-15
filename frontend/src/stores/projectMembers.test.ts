import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProjectMembersStore } from './projectMembers'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/services/api', () => ({ default: api }))

const member = {
  id: 'm1',
  projectId: 'p1',
  userId: 'u1',
  username: 'analyst',
  email: 'analyst@example.com',
  fullName: 'Data Analyst',
  roleId: 'r1',
  roleName: 'DATA_ANALYST',
  roleDisplayName: 'Data Analyst',
  assignedAt: '2026-08-15T04:00:00Z',
}

describe('project members store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads members, candidates and roles', async () => {
    api.get
      .mockResolvedValueOnce({ data: [member] })
      .mockResolvedValueOnce({
        data: [{
          id: 'u1',
          username: 'analyst',
          email: 'analyst@example.com',
          fullName: 'Data Analyst',
        }],
      })
      .mockResolvedValueOnce({
        data: [{
          id: 'r1',
          name: 'DATA_ANALYST',
          displayName: 'Data Analyst',
          description: 'Read and analyze project data',
        }],
      })

    const store = useProjectMembersStore()
    await store.loadManagementData('p1')

    expect(api.get).toHaveBeenNthCalledWith(1, '/projects/p1/members')
    expect(api.get).toHaveBeenNthCalledWith(
      2,
      '/projects/p1/member-candidates',
      { params: { q: '' } },
    )
    expect(api.get).toHaveBeenNthCalledWith(3, '/projects/p1/member-roles')
    expect(store.members).toEqual([member])
    expect(store.candidates).toHaveLength(1)
    expect(store.roles).toHaveLength(1)
  })

  it('adds and removes a project member', async () => {
    api.post.mockResolvedValue({ data: member })
    api.delete.mockResolvedValue({})

    const store = useProjectMembersStore()

    await store.addMember('p1', 'u1', 'r1')
    expect(api.post).toHaveBeenCalledWith('/projects/p1/members', {
      userId: 'u1',
      roleId: 'r1',
    })
    expect(store.members).toEqual([member])

    await store.removeMember('p1', 'm1')
    expect(api.delete).toHaveBeenCalledWith('/projects/p1/members/m1')
    expect(store.members).toEqual([])
  })
})
