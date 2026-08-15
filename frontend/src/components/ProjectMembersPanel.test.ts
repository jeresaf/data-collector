import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectMembersPanel from './ProjectMembersPanel.vue'
import { useAuthStore } from '@/stores/auth'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  default: api,
  ACCESS_TOKEN_STORAGE_KEY: 'odk_access_token',
  REFRESH_TOKEN_STORAGE_KEY: 'odk_refresh_token',
  AUTH_USER_STORAGE_KEY: 'odk_auth_user',
}))

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

function adminUser() {
  return {
    id: 'admin',
    username: 'admin',
    email: 'admin@example.com',
    fullName: 'System Administrator',
    roles: ['SYSTEM_ADMIN'],
    permissions: ['PROJECT_UPDATE', 'USER_READ'],
  }
}

describe('ProjectMembersPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.stubGlobal('confirm', vi.fn(() => true))
  })

  it('loads members, users and project roles for an administrator', async () => {
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

    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = adminUser() as any

    const wrapper = mount(ProjectMembersPanel, {
      props: { projectId: 'p1' },
      global: { plugins: [pinia] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Users & Access')
    expect(wrapper.text()).toContain('Data Analyst')
    expect(wrapper.text()).toContain('analyst@example.com')
    expect(wrapper.get('[aria-label="Project user"]').exists()).toBe(true)
    expect(wrapper.get('[aria-label="Project role"]').exists()).toBe(true)
  })

  it('adds a selected user and role', async () => {
    api.get
      .mockResolvedValueOnce({ data: [] })
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
          description: null,
        }],
      })
    api.post.mockResolvedValue({ data: member })

    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = adminUser() as any

    const wrapper = mount(ProjectMembersPanel, {
      props: { projectId: 'p1' },
      global: { plugins: [pinia] },
    })
    await flushPromises()

    await wrapper.get('[aria-label="Project user"]').setValue('u1')
    await wrapper.get('[aria-label="Project role"]').setValue('r1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/projects/p1/members', {
      userId: 'u1',
      roleId: 'r1',
    })
    expect(wrapper.text()).toContain('Data Analyst added to the project.')
  })

  it('removes an existing role assignment', async () => {
    api.get
      .mockResolvedValueOnce({ data: [member] })
      .mockResolvedValueOnce({ data: [] })
      .mockResolvedValueOnce({ data: [] })
    api.delete.mockResolvedValue({})

    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().user = adminUser() as any

    const wrapper = mount(ProjectMembersPanel, {
      props: { projectId: 'p1' },
      global: { plugins: [pinia] },
    })
    await flushPromises()

    await wrapper.get('[data-member-id="m1"] button').trigger('click')
    await flushPromises()

    expect(api.delete).toHaveBeenCalledWith('/projects/p1/members/m1')
    expect(wrapper.text()).toContain('Data Analyst removed from Data Analyst.')
  })
})
