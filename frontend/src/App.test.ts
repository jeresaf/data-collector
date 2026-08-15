import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'

describe('App.vue', () => {
  it('renders without crashing', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/login',
          name: 'login',
          component: { template: '<div>Login</div>' },
        },
        {
          path: '/',
          name: 'projects',
          component: { template: '<div>Projects</div>' },
        },
        {
          path: '/submissions',
          name: 'submissions',
          component: { template: '<div>Submissions</div>' },
        },
      ],
    })

    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
      },
    })

    expect(wrapper.exists()).toBe(true)
    expect(wrapper.text()).toContain('Login')
  })
})
