import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useProjectSettingsStore } from './projectSettings'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}))

vi.mock('@/services/api', () => ({ default: api }))

describe('project settings store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads the settings map returned by the backend', async () => {
    api.get.mockResolvedValue({
      data: {
        timezone: {
          value: 'UTC',
          description: 'Default timezone for the project',
        },
        max_submission_size_mb: {
          value: 10,
          description: 'Maximum submission size in megabytes',
        },
      },
    })

    const store = useProjectSettingsStore()
    await store.fetchSettings('p1')

    expect(api.get).toHaveBeenCalledWith('/projects/p1/settings')
    expect(store.settings.timezone.value).toBe('UTC')
    expect(store.settings.max_submission_size_mb.value).toBe(10)
    expect(store.isEmpty).toBe(false)
  })

  it('initializes defaults and reloads settings', async () => {
    api.post.mockResolvedValue({})
    api.get.mockResolvedValue({
      data: {
        allow_anonymous_submissions: {
          value: false,
          description: 'Allow anonymous form submissions without authentication',
        },
      },
    })

    const store = useProjectSettingsStore()
    await store.initializeDefaults('p1')

    expect(api.post).toHaveBeenCalledWith('/projects/p1/settings/initialize')
    expect(api.get).toHaveBeenCalledWith('/projects/p1/settings')
    expect(store.settings.allow_anonymous_submissions.value).toBe(false)
  })

  it('updates the nested setting value without losing its description', async () => {
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

    const store = useProjectSettingsStore()
    await store.fetchSettings('p1')
    await store.updateSetting(
      'p1',
      'timezone',
      'Africa/Kampala',
      'Default timezone for the project',
    )

    expect(api.put).toHaveBeenCalledWith('/projects/p1/settings/timezone', {
      value: {
        value: 'Africa/Kampala',
        description: 'Default timezone for the project',
      },
      description: 'Default timezone for the project',
    })
    expect(store.settings.timezone.value).toBe('Africa/Kampala')
  })
})
