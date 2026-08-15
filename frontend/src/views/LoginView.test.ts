import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import api from '@/services/api'
import LoginView from './LoginView.vue'

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
  ACCESS_TOKEN_STORAGE_KEY: 'odk_access_token',
  REFRESH_TOKEN_STORAGE_KEY: 'odk_refresh_token',
  AUTH_USER_STORAGE_KEY: 'odk_auth_user',
}))

const loginResponse = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresIn: 3600000,
  userInfo: {
    id: 'user-1',
    username: 'admin',
    email: 'admin@odk-platform.local',
    fullName: 'System Administrator',
    roles: ['SYSTEM_ADMIN'],
    permissions: ['PROJECT_READ'],
  },
}

async function mountLogin(initialUrl = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/', name: 'projects', component: { template: '<div>Projects</div>' } },
      { path: '/submissions', name: 'submissions', component: { template: '<div>Submissions</div>' } },
    ],
  })

  await router.push(initialUrl)
  await router.isReady()

  const wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia(), router],
    },
  })

  return { wrapper, router }
}

describe('LoginView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('submits username/password and returns to the requested route', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: loginResponse } as any)
    const { wrapper, router } = await mountLogin(
      '/login?redirect=%2Fsubmissions',
    )

    await wrapper.get('#username').setValue('admin')
    await wrapper.get('#password').setValue('admin123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/auth/login', {
      username: 'admin',
      password: 'admin123',
    })
    expect(router.currentRoute.value.fullPath).toBe('/submissions')
  })

  it('shows a friendly error when login fails', async () => {
    vi.mocked(api.post).mockRejectedValueOnce({
      response: {
        status: 401,
        data: { message: 'Invalid username or password' },
      },
    })

    const { wrapper } = await mountLogin()

    await wrapper.get('#username').setValue('admin')
    await wrapper.get('#password').setValue('wrong')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain(
      'Invalid username or password',
    )
  })

  it('rejects an external redirect target after a successful login', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: loginResponse } as any)
    const { wrapper, router } = await mountLogin(
      '/login?redirect=https%3A%2F%2Fevil.example',
    )

    await wrapper.get('#username').setValue('admin')
    await wrapper.get('#password').setValue('admin123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
  })
})
