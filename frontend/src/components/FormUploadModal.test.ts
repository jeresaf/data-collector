import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FormUploadModal from './FormUploadModal.vue'
import { useFormStore } from '@/stores/form'

vi.mock('@/services/api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

describe('FormUploadModal XLSForm mode', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('accepts xlsx by default and displays conversion warnings after upload', async () => {
    const store = useFormStore()
    vi.spyOn(store, 'uploadXlsForm').mockResolvedValue({
      success: true,
      message: 'XLSForm converted, validated and stored successfully',
      formId: 'form-1',
      formVersionId: 'version-1',
      xmlns: 'people',
      title: 'People',
      version: '1',
      formHash: 'abc',
      warnings: ['Column custom is preserved'],
      info: ['Generated XForm validated successfully with JavaRosa 4.3.2'],
      extraColumns: { survey: ['custom'] }
    })

    const wrapper = mount(FormUploadModal, { props: { projectId: 'project-1' } })
    const input = wrapper.get('input[type="file"]')
    const file = new File(['xlsx'], 'people.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(store.uploadXlsForm).toHaveBeenCalledOnce()
    expect(wrapper.emitted('uploaded')).toHaveLength(1)
    expect(wrapper.text()).toContain('XLSForm Converted Successfully')
    expect(wrapper.text()).toContain('Column custom is preserved')
    expect(wrapper.text()).toContain('survey: custom')
  })

  it('switches to XML mode and accepts .xml files', async () => {
    const store = useFormStore()
    vi.spyOn(store, 'uploadXmlForm').mockResolvedValue({ success: true, message: 'ok', title: 'XML' })
    const wrapper = mount(FormUploadModal, { props: { projectId: 'project-1' } })

    const xmlTab = wrapper.findAll('.mode-tabs button').find(button => button.text().includes('XForm XML'))!
    await xmlTab.trigger('click')
    const input = wrapper.get('input[type="file"]')
    expect(input.attributes('accept')).toContain('.xml')

    const file = new File(['<xml/>'], 'form.xml', { type: 'application/xml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(store.uploadXmlForm).toHaveBeenCalledOnce()
  })
})
