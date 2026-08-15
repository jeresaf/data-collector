import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import { useIdentityAdminStore } from './identityAdmin'

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
  },
}))

describe('identity admin store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads users, app users and global roles', async () => {
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: [{ id: 'u1', username: 'admin' }] } as any)
      .mockResolvedValueOnce({ data: [{ id: 'a1', username: 'collector' }] } as any)
      .mockResolvedValueOnce({ data: [{ id: 'r1', name: 'VIEWER' }] } as any)

    const store = useIdentityAdminStore()
    await store.fetchAll()

    expect(store.users).toHaveLength(1)
    expect(store.appUsers).toHaveLength(1)
    expect(store.roles).toHaveLength(1)
    expect(api.get).toHaveBeenCalledWith('/admin/identity/users')
    expect(api.get).toHaveBeenCalledWith('/admin/identity/app-users')
    expect(api.get).toHaveBeenCalledWith('/admin/identity/roles')
  })

  it('updates global roles and account status in local state', async () => {
    const store = useIdentityAdminStore()
    store.users.push({
      id: 'u1',
      username: 'analyst',
      email: 'analyst@example.org',
      firstName: null,
      lastName: null,
      fullName: 'analyst',
      phoneNumber: null,
      active: true,
      verified: true,
      roles: ['VIEWER'],
      lastLoginAt: null,
      createdAt: null,
      updatedAt: null,
    })

    vi.mocked(api.put).mockResolvedValueOnce({
      data: { ...store.users[0], roles: ['DATA_ANALYST'] },
    } as any)
    vi.mocked(api.patch).mockResolvedValueOnce({
      data: { ...store.users[0], roles: ['DATA_ANALYST'], active: false },
    } as any)

    await store.replaceRoles('u1', ['DATA_ANALYST'])
    await store.setUserActive('u1', false)

    expect(store.users[0].roles).toEqual(['DATA_ANALYST'])
    expect(store.users[0].active).toBe(false)
  })
})
