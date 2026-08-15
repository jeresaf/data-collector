import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProjectSettingsPanel from './ProjectSettingsPanel.vue'
import { useAuthStore } from '@/stores/auth'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  default: api,
  ACCESS_TOKEN_STORAGE_KEY: 'odk_access_token',
  REFRESH_TOKEN_STORAGE_KEY: 'odk_refresh_token',
  AUTH_USER_STORAGE_KEY: 'odk_auth_user',
}))

function adminUser() {
  return {
    id: 'u1',
    username: 'admin',
    email: 'admin@example.com',
    fullName: 'System Administrator',
    roles: ['SYSTEM_ADMIN'],
    permissions: ['PROJECT_READ', 'PROJECT_UPDATE'],
  }
}

describe('ProjectSettingsPanel', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    const pinia = createPinia()
    setActivePinia(pinia)
  })

  it('renders the backend defaults as typed controls', async () => {
    api.get.mockResolvedValue({
      data: {
        allow_anonymous_submissions: {
          value: false,
          description: 'Allow anonymous form submissions without authentication',
        },
        default_form_status: {
          value: 'draft',
          description: 'Default status for new forms',
        },
        max_submission_size_mb: {
          value: 10,
          description: 'Maximum submission size in megabytes',
        },
        supported_languages: {
          value: ['en'],
          description: 'Supported languages for forms in this project',
        },
      },
    })

    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = adminUser() as any

    const wrapper = mount(ProjectSettingsPanel, {
      props: { projectId: 'p1' },
      global: { plugins: [pinia] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Allow anonymous submissions')
    expect(wrapper.get('[data-setting-key="allow_anonymous_submissions"] input[type="checkbox"]').exists()).toBe(true)
    expect(wrapper.get('[data-setting-key="default_form_status"] select').element).toBeTruthy()
    expect(wrapper.get('[data-setting-key="max_submission_size_mb"] input[type="number"]').exists()).toBe(true)
    expect((wrapper.get('[aria-label="Supported languages"]').element as HTMLInputElement).value).toBe('en')
  })

  it('saves an edited project setting', async () => {
    api.get.mockResolvedValue({
      data: {
        timezone: {
          value: 'UTC',
          description: 'Default timezone for the project',
        },
      },
    })
    api.put.mockResolvedValue({
      data: {
        settingKey: 'timezone',
        settingValue: {
          value: 'Africa/Kampala',
          description: 'Default timezone for the project',
        },
        description: 'Default timezone for the project',
      },
    })

    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = adminUser() as any

    const wrapper = mount(ProjectSettingsPanel, {
      props: { projectId: 'p1' },
      global: { plugins: [pinia] },
    })
    await flushPromises()

    const input = wrapper.get('[aria-label="Project timezone"]')
    await input.setValue('Africa/Kampala')
    await wrapper.get('[data-setting-key="timezone"] button').trigger('click')
    await flushPromises()

    expect(api.put).toHaveBeenCalledWith('/projects/p1/settings/timezone', {
      value: {
        value: 'Africa/Kampala',
        description: 'Default timezone for the project',
      },
      description: 'Default timezone for the project',
    })
    expect(wrapper.text()).toContain('Saved')
  })

  it('offers initialization when no settings exist', async () => {
    api.get
      .mockResolvedValueOnce({ data: {} })
      .mockResolvedValueOnce({
        data: {
          timezone: {
            value: 'UTC',
            description: 'Default timezone for the project',
          },
        },
      })
    api.post.mockResolvedValue({})

    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.user = adminUser() as any

    const wrapper = mount(ProjectSettingsPanel, {
      props: { projectId: 'p1' },
      global: { plugins: [pinia] },
    })
    await flushPromises()

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/projects/p1/settings/initialize')
    expect(wrapper.text()).toContain('Project timezone')
  })
})
