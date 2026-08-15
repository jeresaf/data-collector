import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface GlobalRole {
  id: string
  name: string
  displayName: string | null
  description: string | null
  system: boolean
  active: boolean
}

export interface WebUserAdmin {
  id: string
  username: string
  email: string
  firstName: string | null
  lastName: string | null
  fullName: string
  phoneNumber: string | null
  active: boolean
  verified: boolean
  roles: string[]
  lastLoginAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface AppUserAdmin {
  id: string
  username: string
  email: string
  fullName: string | null
  phoneNumber: string | null
  active: boolean
  verified: boolean
  lastLoginAt: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface WebUserInput {
  username: string
  email: string
  password?: string
  firstName?: string
  lastName?: string
  phoneNumber?: string
  roleNames?: string[]
}

export interface AppUserInput {
  username: string
  email: string
  password?: string
  fullName?: string
  phoneNumber?: string
}

function messageFor(error: any, fallback: string) {
  return error?.response?.data?.error
    || error?.response?.data?.message
    || fallback
}

export const useIdentityAdminStore = defineStore('identityAdmin', () => {
  const users = ref<WebUserAdmin[]>([])
  const appUsers = ref<AppUserAdmin[]>([])
  const roles = ref<GlobalRole[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchAll() {
    loading.value = true
    error.value = null
    try {
      const [usersResponse, appUsersResponse, rolesResponse] = await Promise.all([
        api.get<WebUserAdmin[]>('/admin/identity/users'),
        api.get<AppUserAdmin[]>('/admin/identity/app-users'),
        api.get<GlobalRole[]>('/admin/identity/roles'),
      ])
      users.value = usersResponse.data
      appUsers.value = appUsersResponse.data
      roles.value = rolesResponse.data
    } catch (err: any) {
      error.value = messageFor(err, 'Unable to load identity administration.')
      throw err
    } finally {
      loading.value = false
    }
  }

  function replaceUser(updated: WebUserAdmin) {
    const index = users.value.findIndex(user => user.id === updated.id)
    if (index >= 0) users.value[index] = updated
    else users.value.push(updated)
  }

  function replaceAppUser(updated: AppUserAdmin) {
    const index = appUsers.value.findIndex(user => user.id === updated.id)
    if (index >= 0) appUsers.value[index] = updated
    else appUsers.value.push(updated)
  }

  async function createUser(input: WebUserInput) {
    const response = await api.post<WebUserAdmin>('/admin/identity/users', input)
    replaceUser(response.data)
    return response.data
  }

  async function updateUser(userId: string, input: WebUserInput) {
    const response = await api.put<WebUserAdmin>(
      `/admin/identity/users/${userId}`,
      input,
    )
    replaceUser(response.data)
    return response.data
  }

  async function replaceRoles(userId: string, roleNames: string[]) {
    const response = await api.put<WebUserAdmin>(
      `/admin/identity/users/${userId}/roles`,
      { roleNames },
    )
    replaceUser(response.data)
    return response.data
  }

  async function setUserActive(userId: string, active: boolean) {
    const response = await api.patch<WebUserAdmin>(
      `/admin/identity/users/${userId}/status`,
      { active },
    )
    replaceUser(response.data)
    return response.data
  }

  async function createAppUser(input: AppUserInput) {
    const response = await api.post<AppUserAdmin>('/admin/identity/app-users', input)
    replaceAppUser(response.data)
    return response.data
  }

  async function updateAppUser(appUserId: string, input: AppUserInput) {
    const response = await api.put<AppUserAdmin>(
      `/admin/identity/app-users/${appUserId}`,
      input,
    )
    replaceAppUser(response.data)
    return response.data
  }

  async function setAppUserActive(appUserId: string, active: boolean) {
    const response = await api.patch<AppUserAdmin>(
      `/admin/identity/app-users/${appUserId}/status`,
      { active },
    )
    replaceAppUser(response.data)
    return response.data
  }

  return {
    users,
    appUsers,
    roles,
    loading,
    error,
    fetchAll,
    createUser,
    updateUser,
    replaceRoles,
    setUserActive,
    createAppUser,
    updateAppUser,
    setAppUserActive,
  }
})
