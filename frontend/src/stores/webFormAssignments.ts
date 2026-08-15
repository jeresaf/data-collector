import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface WebFormAssignment {
  id: string
  projectId: string
  formId: string
  targetType: 'USER' | 'ROLE'
  targetId: string
  targetName: string
  targetDetail: string | null
  assignedAt: string | null
}

export interface WebUserCandidate {
  id: string
  username: string
  email: string
  fullName: string
}

export interface WebRoleCandidate {
  id: string
  name: string
  displayName: string | null
  description: string | null
}

export const useWebFormAssignmentsStore = defineStore('webFormAssignments', () => {
  const assignments = ref<WebFormAssignment[]>([])
  const users = ref<WebUserCandidate[]>([])
  const roles = ref<WebRoleCandidate[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  function base(projectId: string, formId: string): string {
    return `/projects/${projectId}/forms/${formId}/web-assignments`
  }

  async function fetchAssignments(projectId: string, formId: string): Promise<WebFormAssignment[]> {
    const response = await api.get<WebFormAssignment[]>(base(projectId, formId))
    assignments.value = response.data
    return response.data
  }

  async function searchUsers(
    projectId: string,
    formId: string,
    query = '',
  ): Promise<WebUserCandidate[]> {
    const response = await api.get<WebUserCandidate[]>(`${base(projectId, formId)}/users`, {
      params: { q: query },
    })
    users.value = response.data
    return response.data
  }

  async function fetchRoles(projectId: string, formId: string): Promise<WebRoleCandidate[]> {
    const response = await api.get<WebRoleCandidate[]>(`${base(projectId, formId)}/roles`)
    roles.value = response.data
    return response.data
  }

  async function loadAssignments(projectId: string, formId: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await fetchAssignments(projectId, formId)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to load web form assignments'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function loadManagementData(projectId: string, formId: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await Promise.all([
        fetchAssignments(projectId, formId),
        searchUsers(projectId, formId),
        fetchRoles(projectId, formId),
      ])
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to load web form access settings'
      throw err
    } finally {
      loading.value = false
    }
  }

  function upsert(assignment: WebFormAssignment): void {
    const index = assignments.value.findIndex((item) => item.id === assignment.id)
    if (index >= 0) assignments.value[index] = assignment
    else assignments.value.push(assignment)
  }

  async function assignUser(
    projectId: string,
    formId: string,
    userId: string,
  ): Promise<WebFormAssignment> {
    saving.value = true
    error.value = null
    try {
      const response = await api.post<WebFormAssignment>(`${base(projectId, formId)}/users`, {
        userId,
      })
      upsert(response.data)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to assign web user'
      throw err
    } finally {
      saving.value = false
    }
  }

  async function assignRole(
    projectId: string,
    formId: string,
    roleId: string,
  ): Promise<WebFormAssignment> {
    saving.value = true
    error.value = null
    try {
      const response = await api.post<WebFormAssignment>(`${base(projectId, formId)}/roles`, {
        roleId,
      })
      upsert(response.data)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to assign project role'
      throw err
    } finally {
      saving.value = false
    }
  }

  async function removeAssignment(
    projectId: string,
    formId: string,
    assignmentId: string,
  ): Promise<void> {
    saving.value = true
    error.value = null
    try {
      await api.delete(`${base(projectId, formId)}/${assignmentId}`)
      assignments.value = assignments.value.filter((item) => item.id !== assignmentId)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to remove web form assignment'
      throw err
    } finally {
      saving.value = false
    }
  }

  function clearError(): void {
    error.value = null
  }

  return {
    assignments,
    users,
    roles,
    loading,
    saving,
    error,
    fetchAssignments,
    searchUsers,
    fetchRoles,
    loadAssignments,
    loadManagementData,
    assignUser,
    assignRole,
    removeAssignment,
    clearError,
  }
})
