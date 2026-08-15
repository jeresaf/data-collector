import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectCard from './ProjectCard.vue'

const project = {
  id: 'p1',
  name: 'Certification',
  description: 'Project description',
  isActive: true,
  createdAt: '2026-08-14T08:00:00Z',
  updatedAt: '2026-08-14T09:00:00Z'
}

describe('ProjectCard', () => {
  it('provides a real route to project detail', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'projects', component: { template: '<div />' } },
        { path: '/projects/:id', name: 'project-detail', component: { template: '<div />' } }
      ]
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(ProjectCard, {
      props: { project },
      global: { plugins: [router] }
    })

    const open = wrapper.get('a[aria-label="Open Certification"]')
    expect(open.attributes('href')).toBe('/projects/p1')
    expect(wrapper.text()).toContain('Active')
  })
})
