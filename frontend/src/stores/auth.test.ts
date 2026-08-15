import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import api, {
  ACCESS_TOKEN_STORAGE_KEY,
  AUTH_USER_STORAGE_KEY,
  REFRESH_TOKEN_STORAGE_KEY,
} from '@/services/api'
import { useAuthStore } from './auth'

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
  ACCESS_TOKEN_STORAGE_KEY: 'odk_access_token',
  REFRESH_TOKEN_STORAGE_KEY: 'odk_refresh_token',
  AUTH_USER_STORAGE_KEY: 'odk_auth_user',
}))

const user = {
  id: 'user-1',
  username: 'admin',
  email: 'admin@odk-platform.local',
  fullName: 'System Administrator',
  roles: ['SYSTEM_ADMIN'],
  permissions: ['PROJECT_READ', 'SUBMISSION_READ'],
}

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('logs in and persists the backend session contract', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({
      data: {
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
        tokenType: 'Bearer',
        expiresIn: 3600000,
        userInfo: user,
      },
    } as any)

    const store = useAuthStore()
    await store.login('admin', 'admin123')

    expect(api.post).toHaveBeenCalledWith('/auth/login', {
      username: 'admin',
      password: 'admin123',
    })
    expect(store.isAuthenticated).toBe(true)
    expect(store.user?.username).toBe('admin')
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBe('access-token')
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('refresh-token')
    expect(JSON.parse(localStorage.getItem(AUTH_USER_STORAGE_KEY)!)).toMatchObject({
      username: 'admin',
      roles: ['SYSTEM_ADMIN'],
    })
  })

  it('hydrates the current user from /auth/me', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'access-token')
    vi.mocked(api.get).mockResolvedValueOnce({ data: user } as any)

    const store = useAuthStore()
    const restored = await store.restoreSession()

    expect(restored).toBe(true)
    expect(api.get).toHaveBeenCalledWith('/auth/me')
    expect(store.displayName).toBe('System Administrator')
  })

  it('clears local state when session restoration fails', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'expired-token')
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'refresh-token')
    vi.mocked(api.get).mockRejectedValueOnce(new Error('Unauthorized'))

    const store = useAuthStore()
    const restored = await store.restoreSession()

    expect(restored).toBe(false)
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('clears the browser session even if server logout fails', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'access-token')
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'refresh-token')
    vi.mocked(api.post).mockRejectedValueOnce(new Error('Network error'))

    const store = useAuthStore()
    await expect(store.logout()).rejects.toThrow('Network error')

    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('checks roles and permissions from current user state', async () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'access-token')
    vi.mocked(api.get).mockResolvedValueOnce({ data: user } as any)

    const store = useAuthStore()
    await store.fetchCurrentUser()

    expect(store.hasRole('SYSTEM_ADMIN')).toBe(true)
    expect(store.hasRole('VIEWER')).toBe(false)
    expect(store.hasPermission('PROJECT_READ')).toBe(true)
    expect(store.hasPermission('PROJECT_DELETE')).toBe(false)
  })
})
