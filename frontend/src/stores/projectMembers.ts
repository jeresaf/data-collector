import { ref } from 'vue'
import { defineStore } from 'pinia'
import api from '@/services/api'

export interface ProjectMember {
  id: string
  projectId: string
  userId: string
  username: string
  email: string
  fullName: string
  roleId: string
  roleName: string
  roleDisplayName: string | null
  assignedAt: string | null
}

export interface ProjectMemberCandidate {
  id: string
  username: string
  email: string
  fullName: string
}

export interface ProjectMemberRole {
  id: string
  name: string
  displayName: string | null
  description: string | null
}

export const useProjectMembersStore = defineStore('projectMembers', () => {
  const members = ref<ProjectMember[]>([])
  const candidates = ref<ProjectMemberCandidate[]>([])
  const roles = ref<ProjectMemberRole[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  async function fetchMembers(projectId: string) {
    loading.value = true
    error.value = null

    try {
      const response = await api.get<ProjectMember[]>(
        `/projects/${projectId}/members`,
      )
      members.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to load project members'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function searchCandidates(projectId: string, query = '') {
    const response = await api.get<ProjectMemberCandidate[]>(
      `/projects/${projectId}/member-candidates`,
      { params: { q: query } },
    )
    candidates.value = response.data
    return response.data
  }

  async function fetchRoles(projectId: string) {
    const response = await api.get<ProjectMemberRole[]>(
      `/projects/${projectId}/member-roles`,
    )
    roles.value = response.data
    return response.data
  }

  async function loadManagementData(projectId: string) {
    await Promise.all([
      fetchMembers(projectId),
      searchCandidates(projectId),
      fetchRoles(projectId),
    ])
  }

  async function addMember(
    projectId: string,
    userId: string,
    roleId: string,
  ) {
    saving.value = true
    error.value = null

    try {
      const response = await api.post<ProjectMember>(
        `/projects/${projectId}/members`,
        { userId, roleId },
      )

      const index = members.value.findIndex(member => member.id === response.data.id)
      if (index >= 0) {
        members.value[index] = response.data
      } else {
        members.value = [...members.value, response.data].sort((a, b) =>
          `${a.fullName} ${a.roleName}`.localeCompare(`${b.fullName} ${b.roleName}`),
        )
      }

      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to add project member'
      throw err
    } finally {
      saving.value = false
    }
  }

  async function removeMember(projectId: string, memberId: string) {
    saving.value = true
    error.value = null

    try {
      await api.delete(`/projects/${projectId}/members/${memberId}`)
      members.value = members.value.filter(member => member.id !== memberId)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to remove project member'
      throw err
    } finally {
      saving.value = false
    }
  }

  return {
    members,
    candidates,
    roles,
    loading,
    saving,
    error,
    fetchMembers,
    searchCandidates,
    fetchRoles,
    loadManagementData,
    addMember,
    removeMember,
  }
})
