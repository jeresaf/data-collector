import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import FormList from './FormList.vue'

const routerPush = vi.hoisted(() => vi.fn())
const fetchForms = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush }),
}))

vi.mock('@/stores/form', () => ({
  useFormStore: () => ({
    fetchForms,
    createForm: vi.fn(),
    updateForm: vi.fn(),
    deleteForm: vi.fn(),
  }),
}))

describe('FormList lifecycle navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchForms.mockResolvedValue([
      {
        id: 'form-1', name: 'Household Survey', version: '2', description: '',
        isPublished: false, createdAt: '2026-08-15T09:00:00Z', updatedAt: '2026-08-15T09:00:00Z',
      },
    ])
  })

  it('opens form lifecycle detail instead of blindly publishing without a version id', async () => {
    const wrapper = mount(FormList, {
      props: { projectId: 'project-1' },
      global: {
        stubs: {
          FormCard: {
            props: ['form'],
            emits: ['publish', 'edit', 'delete'],
            template: '<button class="publish-trigger" @click="$emit(\'publish\', form.id)">Publish</button>',
          },
          FormFormModal: true,
          FormUploadModal: true,
        },
      },
    })
    await flushPromises()

    await wrapper.get('.publish-trigger').trigger('click')

    expect(routerPush).toHaveBeenCalledWith({
      name: 'form-detail',
      params: { projectId: 'project-1', formId: 'form-1' },
    })
  })
})
