import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import api, {
  ACCESS_TOKEN_STORAGE_KEY,
  AUTH_USER_STORAGE_KEY,
  REFRESH_TOKEN_STORAGE_KEY,
} from '@/services/api'

export interface AuthUser {
  id: string
  username: string
  email: string
  fullName: string | null
  roles: string[]
  permissions: string[]
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  userInfo: AuthUser
}

function normalizeUser(user: AuthUser): AuthUser {
  return {
    ...user,
    fullName: user.fullName ?? null,
    roles: Array.isArray(user.roles) ? user.roles : [],
    permissions: Array.isArray(user.permissions) ? user.permissions : [],
  }
}

export function getStoredAuthUser(): AuthUser | null {
  const raw = localStorage.getItem(AUTH_USER_STORAGE_KEY)

  if (!raw) {
    return null
  }

  try {
    return normalizeUser(JSON.parse(raw) as AuthUser)
  } catch {
    localStorage.removeItem(AUTH_USER_STORAGE_KEY)
    return null
  }
}

export function hasStoredAccessToken(): boolean {
  return Boolean(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY))
}

export function clearStoredAuth(): void {
  localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY)
  localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY)
  localStorage.removeItem(AUTH_USER_STORAGE_KEY)
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(
    localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY),
  )
  const refreshToken = ref<string | null>(
    localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY),
  )
  const user = ref<AuthUser | null>(getStoredAuthUser())
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => Boolean(accessToken.value))
  const displayName = computed(
    () => user.value?.fullName || user.value?.username || '',
  )

  function persistSession(response: LoginResponse): void {
    const normalizedUser = normalizeUser(response.userInfo)

    accessToken.value = response.accessToken
    refreshToken.value = response.refreshToken
    user.value = normalizedUser

    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, response.accessToken)
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, response.refreshToken)
    localStorage.setItem(AUTH_USER_STORAGE_KEY, JSON.stringify(normalizedUser))
  }

  function clearSession(): void {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    error.value = null
    clearStoredAuth()
  }

  async function login(username: string, password: string): Promise<AuthUser> {
    loading.value = true
    error.value = null

    try {
      const response = await api.post<LoginResponse>('/auth/login', {
        username,
        password,
      })

      persistSession(response.data)
      return response.data.userInfo
    } catch (err: any) {
      clearSession()
      error.value =
        err.response?.data?.message ||
        err.response?.data?.error ||
        'Unable to sign in. Check your username and password.'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchCurrentUser(): Promise<AuthUser> {
    const response = await api.get<AuthUser>('/auth/me')
    const currentUser = normalizeUser(response.data)

    user.value = currentUser
    localStorage.setItem(AUTH_USER_STORAGE_KEY, JSON.stringify(currentUser))

    return currentUser
  }

  async function restoreSession(): Promise<boolean> {
    if (!accessToken.value) {
      clearSession()
      return false
    }

    loading.value = true

    try {
      await fetchCurrentUser()
      return true
    } catch {
      clearSession()
      return false
    } finally {
      loading.value = false
    }
  }

  async function refreshAccessToken(): Promise<boolean> {
    if (!refreshToken.value) {
      clearSession()
      return false
    }

    try {
      const response = await api.post<LoginResponse>('/auth/refresh', {
        refreshToken: refreshToken.value,
      })

      persistSession(response.data)
      return true
    } catch {
      clearSession()
      return false
    }
  }

  async function logout(): Promise<void> {
    try {
      if (accessToken.value) {
        await api.post('/auth/logout')
      }
    } finally {
      clearSession()
    }
  }

  function hasRole(role: string): boolean {
    return user.value?.roles.includes(role) ?? false
  }

  function hasPermission(permission: string): boolean {
    return user.value?.permissions.includes(permission) ?? false
  }

  return {
    accessToken,
    refreshToken,
    user,
    loading,
    error,
    isAuthenticated,
    displayName,
    login,
    logout,
    fetchCurrentUser,
    restoreSession,
    refreshAccessToken,
    clearSession,
    hasRole,
    hasPermission,
  }
})
