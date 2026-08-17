import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FormFormModal from './FormFormModal.vue'

const existingForm = {
  id: 'form-1',
  name: 'Farm Registration',
  version: '2',
  description: 'Existing farm form',
  isPublished: false,
  createdAt: '2026-08-15T10:00:00Z',
  updatedAt: '2026-08-15T10:00:00Z',
}

describe('FormFormModal', () => {
  it('mounts in create mode without a missing watch runtime error', () => {
    const wrapper = mount(FormFormModal, {
      props: { form: null },
    })

    expect(wrapper.text()).toContain('Create Form')
    expect(wrapper.get('#name').element).toBeTruthy()
  })

  it('reacts to an existing form and populates edit fields', async () => {
    const wrapper = mount(FormFormModal, {
      props: { form: existingForm as any },
    })

    expect(wrapper.text()).toContain('Edit Form')
    expect((wrapper.get('#name').element as HTMLInputElement).value)
      .toBe('Farm Registration')
    expect((wrapper.get('#description').element as HTMLTextAreaElement).value)
      .toBe('Existing farm form')
    expect((wrapper.get('#version').element as HTMLInputElement).value)
      .toBe('2')

    await wrapper.setProps({
      form: {
        ...existingForm,
        name: 'Updated Farm Registration',
      } as any,
    })

    expect((wrapper.get('#name').element as HTMLInputElement).value)
      .toBe('Updated Farm Registration')
  })
})
