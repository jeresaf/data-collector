import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import FormAssignmentsPanel from './FormAssignmentsPanel.vue'

const directAssignment = {
  id: 'a1',
  projectId: 'p1',
  formId: 'f1',
  targetType: 'USER',
  targetId: 'u1',
  targetName: 'Alice Collector',
  targetDetail: 'alice@example.com',
  assignedAt: '2026-08-15T05:00:00Z',
}

describe('FormAssignmentsPanel', () => {
  let getSpy: ReturnType<typeof vi.spyOn>
  let postSpy: ReturnType<typeof vi.spyOn>
  let deleteSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
    localStorage.clear()

    getSpy = vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url.endsWith('/users')) {
        return {
          data: [{
            id: 'u1',
            username: 'alice',
            email: 'alice@example.com',
            fullName: 'Alice Collector',
          }],
        } as any
      }

      if (url.endsWith('/roles')) {
        return {
          data: [{
            id: 'r1',
            name: 'DATA_ANALYST',
            displayName: 'Data Analyst',
            description: 'Analyze project data',
          }],
        } as any
      }

      return { data: [directAssignment] } as any
    })

    postSpy = vi.spyOn(api, 'post')
    deleteSpy = vi.spyOn(api, 'delete')

    useAuthStore().user = {
      id: 'admin',
      username: 'admin',
      email: 'admin@example.com',
      fullName: 'System Administrator',
      roles: ['SYSTEM_ADMIN'],
      permissions: ['PROJECT_UPDATE'],
    } as any
  })

  it('loads and renders current user assignments and candidates', async () => {
    const wrapper = mount(FormAssignmentsPanel, {
      props: { projectId: 'p1', formId: 'f1' },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Web form access')
    expect(wrapper.text()).toContain('Alice Collector')
    expect(wrapper.text()).toContain('Data Analyst')

    expect(getSpy).toHaveBeenCalledWith(
      '/projects/p1/forms/f1/web-assignments',
    )

    expect(getSpy).toHaveBeenCalledWith(
      '/projects/p1/forms/f1/web-assignments/users',
      { params: { q: '' } },
    )

    expect(getSpy).toHaveBeenCalledWith(
      '/projects/p1/forms/f1/web-assignments/roles',
    )
  })

  it('grants a user and a role and can remove an assignment', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    const roleAssignment = {
      ...directAssignment,
      id: 'a2',
      targetType: 'ROLE',
      targetId: 'r1',
      targetName: 'Data Analyst',
      targetDetail: 'DATA_ANALYST',
    }

    postSpy
      .mockResolvedValueOnce({ data: directAssignment } as any)
      .mockResolvedValueOnce({ data: roleAssignment } as any)

    deleteSpy.mockResolvedValue({} as any)

    const wrapper = mount(FormAssignmentsPanel, {
      props: { projectId: 'p1', formId: 'f1' },
    })
    await flushPromises()

    await wrapper.get('select[aria-label="Web form user"]').setValue('u1')
    const userForm = wrapper.findAll('form')
      .find((form) => form.text().includes('Specific web user'))

    expect(userForm).toBeTruthy()
    await userForm!.trigger('submit')
    await flushPromises()

    expect(postSpy).toHaveBeenCalledWith(
      '/projects/p1/forms/f1/web-assignments/users',
      { userId: 'u1' },
    )

    await wrapper.get('select[aria-label="Web form role"]').setValue('r1')
    const roleForm = wrapper.findAll('form')
      .find((form) => form.text().includes('Project role'))

    expect(roleForm).toBeTruthy()
    await roleForm!.trigger('submit')
    await flushPromises()

    expect(postSpy).toHaveBeenCalledWith(
      '/projects/p1/forms/f1/web-assignments/roles',
      { roleId: 'r1' },
    )

    const removeButton = wrapper.findAll('button')
      .find((button) => button.text() === 'Remove')

    expect(removeButton).toBeTruthy()
    await removeButton!.trigger('click')
    await flushPromises()

    expect(deleteSpy).toHaveBeenCalledWith(
      '/projects/p1/forms/f1/web-assignments/a1',
    )
  })
})
